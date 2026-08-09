package tw.chehu.quicksend.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tw.chehu.quicksend.MainActivity
import tw.chehu.quicksend.R
import tw.chehu.quicksend.localsend.LocalSendClient
import tw.chehu.quicksend.model.BackupSummary
import tw.chehu.quicksend.model.DestinationType
import tw.chehu.quicksend.model.LocalSendDevice
import tw.chehu.quicksend.model.TransferFile
import tw.chehu.quicksend.model.TransferProgress
import tw.chehu.quicksend.model.TransferStage
import tw.chehu.quicksend.storage.BackupHistoryRepository
import tw.chehu.quicksend.storage.UsbCopyClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TransferRequest(
    val device: LocalSendDevice,
    val targetFolder: String,
    val files: List<TransferFile>,
    val pin: String,
    val retryOnly: Boolean,
    val destinationType: DestinationType = DestinationType.LOCALSEND,
    val usbTreeUri: Uri? = null,
    val checkpointCandidate: Long? = null,
)

object TransferRuntime {
    private val _progress = MutableStateFlow(TransferProgress())
    val progress = _progress.asStateFlow()

    fun update(value: TransferProgress) {
        _progress.value = value
    }

    val isBusy: Boolean
        get() = _progress.value.stage in setOf(
            TransferStage.PREPARING,
            TransferStage.SENDING,
        )
}

private object TransferJobRegistry {
    private val jobs = ConcurrentHashMap<String, TransferRequest>()

    fun put(request: TransferRequest): String =
        UUID.randomUUID().toString().also { jobs[it] = request }

    fun take(id: String): TransferRequest? = jobs.remove(id)
}

class TransferService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transferJob: Job? = null
    private var request: TransferRequest? = null
    private var timeoutMessage: String? = null
    private lateinit var notifications: NotificationManager
    private lateinit var history: BackupHistoryRepository
    private lateinit var client: LocalSendClient
    private lateinit var usbCopy: UsbCopyClient
    private var lastNotificationAt = 0L
    @Volatile private var cancelRequested = false

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        history = BackupHistoryRepository(this)
        client = LocalSendClient(contentResolver)
        usbCopy = UsbCopyClient(contentResolver)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested = true
            client.abort()
            transferJob?.cancel(CancellationException("使用者取消備份"))
            return START_NOT_STICKY
        }
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        val nextRequest = requestId?.let(TransferJobRegistry::take)
        startAsForeground(
            buildNotification(
                title = "正在準備備份",
                text = "正在建立傳輸工作…",
                indeterminate = true,
            )
        )
        if (nextRequest == null) {
            finishWithError("傳輸工作已失效，請重新按下傳送")
            return START_NOT_STICKY
        }
        if (transferJob?.isActive == true) {
            finishWithError("已有一個備份正在傳送")
            return START_NOT_STICKY
        }
        request = nextRequest
        cancelRequested = false
        transferJob = serviceScope.launch { performTransfer(nextRequest) }
        return START_NOT_STICKY
    }

    private suspend fun performTransfer(request: TransferRequest) {
        var completedFiles = 0
        var completedBytes = 0L
        val totalBytes = request.files.sumOf { it.size }
        try {
            if (!request.retryOnly) {
                history.replaceFailures(request.device, request.targetFolder, request.files)
                if (request.checkpointCandidate != null) {
                    history.beginCheckpoint(
                        request.device,
                        request.targetFolder,
                        request.checkpointCandidate,
                    )
                } else {
                    history.clearPendingCheckpoint(request.device, request.targetFolder)
                }
            }
            publish(
                TransferProgress(
                    stage = TransferStage.PREPARING,
                    totalFiles = request.files.size,
                    totalBytes = totalBytes,
                    message = when {
                        request.retryOnly -> "正在準備重試失敗檔案…"
                        request.destinationType == DestinationType.USB -> "正在準備 USB 外接磁碟…"
                        else -> "等待接收端確認…"
                    },
                ),
                forceNotification = true,
            )
            val onFileStarted: (Int, TransferFile) -> Unit = { index, file ->
                publish(
                    TransferProgress(
                        stage = TransferStage.SENDING,
                        currentFile = file.destinationPath,
                        completedFiles = completedFiles,
                        totalFiles = request.files.size,
                        sentBytes = completedBytes,
                        totalBytes = totalBytes,
                        message = if (request.destinationType == DestinationType.USB) {
                            "正在複製 ${index + 1}/${request.files.size}"
                        } else {
                            "正在傳送 ${index + 1}/${request.files.size}"
                        },
                    ),
                    forceNotification = true,
                )
            }
            val onBytesProgress: (Int, Long) -> Unit = { _, bytes ->
                val current = TransferRuntime.progress.value
                publish(
                    current.copy(sentBytes = completedBytes + bytes),
                    forceNotification = false,
                )
            }
            val onFileCompleted: (TransferFile) -> Unit = { file ->
                history.recordSuccessful(request.device, request.targetFolder, file)
                completedFiles += 1
                completedBytes += file.size
            }
            if (request.destinationType == DestinationType.USB) {
                usbCopy.copy(
                    treeUri = requireNotNull(request.usbTreeUri) { "USB 目的地授權已失效" },
                    files = request.files,
                    onFileStarted = onFileStarted,
                    onBytesCopied = onBytesProgress,
                    onFileCompleted = onFileCompleted,
                )
            } else {
                client.send(
                    device = request.device,
                    files = request.files,
                    pin = request.pin,
                    onFileStarted = onFileStarted,
                    onBytesSent = onBytesProgress,
                    onFileCompleted = onFileCompleted,
                )
            }
            val remainingFailures = history.failedCount(request.device, request.targetFolder)
            if (remainingFailures > 0) {
                history.saveSummary(
                    request.device,
                    request.targetFolder,
                    BackupSummary(
                        finishedAt = System.currentTimeMillis(),
                        successfulFiles = completedFiles,
                        failedFiles = remainingFailures,
                        successfulBytes = completedBytes,
                        status = "partial",
                    )
                )
                publish(
                    TransferProgress(
                        stage = TransferStage.ERROR,
                        completedFiles = completedFiles,
                        totalFiles = request.files.size,
                        sentBytes = completedBytes,
                        totalBytes = totalBytes,
                        message = "仍有 $remainingFailures 個檔案未完成，可稍後重試",
                    ),
                    forceNotification = true,
                )
                finishNotification("備份部分完成", "$remainingFailures 個檔案可稍後重試")
                return
            }
            val summary = BackupSummary(
                finishedAt = System.currentTimeMillis(),
                successfulFiles = completedFiles,
                failedFiles = 0,
                successfulBytes = completedBytes,
                status = "completed",
            )
            history.saveSummary(request.device, request.targetFolder, summary)
            request.checkpointCandidate?.let {
                history.completeCheckpoint(request.device, request.targetFolder, it)
            }
            val finalProgress = TransferProgress(
                stage = TransferStage.COMPLETED,
                completedFiles = completedFiles,
                totalFiles = request.files.size,
                sentBytes = completedBytes,
                totalBytes = totalBytes,
                message = when {
                    request.retryOnly -> "失敗檔案已全部重試完成"
                    request.destinationType == DestinationType.USB -> "USB 備份完成"
                    else -> "備份傳送完成"
                },
            )
            publish(finalProgress, forceNotification = true)
            finishNotification("備份完成", "成功傳送 $completedFiles 個檔案")
        } catch (error: Throwable) {
            val failed = history.failedCount(request.device, request.targetFolder)
            val message = when {
                cancelRequested -> "備份已取消"
                timeoutMessage != null -> timeoutMessage!!
                error is CancellationException -> "備份已停止"
                else -> error.message ?: "傳輸失敗"
            }
            history.saveSummary(
                request.device,
                request.targetFolder,
                BackupSummary(
                    finishedAt = System.currentTimeMillis(),
                    successfulFiles = completedFiles,
                    failedFiles = failed,
                    successfulBytes = completedBytes,
                    status = if (cancelRequested) "cancelled" else "failed",
                )
            )
            publish(
                TransferProgress(
                    stage = TransferStage.ERROR,
                    completedFiles = completedFiles,
                    totalFiles = request.files.size,
                    sentBytes = completedBytes,
                    totalBytes = totalBytes,
                    message = "$message；可僅重試 $failed 個失敗檔案",
                ),
                forceNotification = true,
            )
            finishNotification(
                if (cancelRequested) "備份已取消" else "備份未完成",
                "$failed 個檔案可稍後重試",
            )
        } finally {
            this@TransferService.request = null
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun publish(progress: TransferProgress, forceNotification: Boolean) {
        TransferRuntime.update(progress)
        val now = System.currentTimeMillis()
        if (!forceNotification && now - lastNotificationAt < 500) return
        lastNotificationAt = now
        val percent = if (progress.totalBytes > 0) {
            ((progress.sentBytes * 100) / progress.totalBytes).toInt().coerceIn(0, 100)
        } else 0
        notifications.notify(
            NOTIFICATION_ID,
            buildNotification(
                title = progress.message,
                text = progress.currentFile.ifBlank {
                    "${progress.completedFiles}/${progress.totalFiles} 個檔案"
                },
                progress = percent,
                indeterminate = progress.totalBytes <= 0,
            )
        )
    }

    private fun finishNotification(title: String, text: String) {
        notifications.notify(
            NOTIFICATION_ID,
            buildNotification(title, text, progress = 100, indeterminate = false, ongoing = false)
        )
    }

    private fun finishWithError(message: String) {
        TransferRuntime.update(TransferProgress(stage = TransferStage.ERROR, message = message))
        finishNotification("無法開始備份", message)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int = 0,
        indeterminate: Boolean,
        ongoing: Boolean = true,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quicksend)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setProgress(100, progress, indeterminate)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (ongoing) {
            val cancelTransfer = PendingIntent.getService(
                this,
                1,
                Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "取消", cancelTransfer)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "備份傳輸",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "顯示影音快速備份傳輸進度"
            }
        )
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timeoutMessage = "已達 Android 背景資料同步時間限制"
        transferJob?.cancel()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "backup_transfer"
        private const val NOTIFICATION_ID = 1031
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val ACTION_CANCEL = "tw.chehu.quicksend.action.CANCEL_TRANSFER"

        fun start(context: Context, request: TransferRequest) {
            check(!TransferRuntime.isBusy) { "已有一個備份正在傳送" }
            TransferRuntime.update(
                TransferProgress(
                    stage = TransferStage.PREPARING,
                    totalFiles = request.files.size,
                    totalBytes = request.files.sumOf { it.size },
                    message = when {
                        request.retryOnly -> "正在啟動失敗檔案重試…"
                        request.destinationType == DestinationType.USB -> "正在啟動 USB 備份…"
                        else -> "正在啟動背景傳輸…"
                    },
                )
            )
            val requestId = TransferJobRegistry.put(request)
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TransferService::class.java)
                        .putExtra(EXTRA_REQUEST_ID, requestId),
                )
            } catch (error: Throwable) {
                TransferJobRegistry.take(requestId)
                TransferRuntime.update(
                    TransferProgress(
                        stage = TransferStage.ERROR,
                        message = error.message ?: "無法啟動背景傳輸",
                    )
                )
                throw error
            }
        }

        fun cancel(context: Context) {
            if (!TransferRuntime.isBusy) return
            context.startService(
                Intent(context, TransferService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
