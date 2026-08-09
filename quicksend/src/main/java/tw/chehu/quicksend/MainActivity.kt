package tw.chehu.quicksend

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.chehu.quicksend.model.RootKind
import tw.chehu.quicksend.model.ScanResult
import tw.chehu.quicksend.model.BackupMode
import tw.chehu.quicksend.model.DestinationType
import tw.chehu.quicksend.model.TransferStage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val nearbyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.discoverDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            nearbyPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        setContent {
            MaterialTheme {
                QuickSendScreen(viewModel)
            }
        }
    }
}

private enum class EditSheet {
    SOURCES,
    DESTINATION,
    FILTERS,
    BACKUP_SETTINGS,
    RECEIVER,
    ADVANCED,
    PRIVACY,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickSendScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var activeSheet by rememberSaveable { mutableStateOf<EditSheet?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.send()
    }
    val dcimPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let { uri -> viewModel.grantRoot(RootKind.DCIM, uri) }
    }
    val picturesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let { uri -> viewModel.grantRoot(RootKind.PICTURES, uri) }
    }
    val usbDestinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) {
        it?.let(viewModel::grantUsbDestination)
    }
    val busy = state.progress.stage in setOf(
        TransferStage.SCANNING,
        TransferStage.DISCOVERING,
        TransferStage.PREPARING,
        TransferStage.SENDING,
    )
    val selectedDevice = state.devices.firstOrNull { it.key == state.selectedDeviceKey }
    val sourcesReady = state.roots.isNotEmpty()
    val grantedSourceNames = RootKind.entries
        .filter(state.roots::containsKey)
        .joinToString("、") { it.title }
    val destinationReady = when (state.destinationType) {
        DestinationType.LOCALSEND -> selectedDevice != null
        DestinationType.USB -> state.usbDestination != null
    }
    val ready = sourcesReady && destinationReady

    Scaffold(
        containerColor = Color(0xFFFCF9FF),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFCFBFF),
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "影音快速備份",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { (context as? Activity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回測試工具箱")
                    }
                },
                actions = {
                    IconButton(onClick = { activeSheet = EditSheet.ADVANCED }) {
                        Icon(Icons.Default.Settings, "進階設定")
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = Color(0xFFFCFBFF)) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.send()
                        }
                    },
                    enabled = !busy && ready &&
                        (!state.useTargetFolder || state.targetFolder.isNotBlank()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .height(64.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Default.Send, null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("開始備份", style = MaterialTheme.typography.titleLarge)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp),
        ) {
            ReadyStatusLine(
                ready = ready,
                sourcesReady = sourcesReady,
            )
            Spacer(Modifier.height(10.dp))
            LargeActionCard(
                icon = Icons.Default.Folder,
                title = "備份來源",
                subtitle = when {
                    sourcesReady -> "$grantedSourceNames 已授權"
                    state.roots.isEmpty() -> "尚未授權資料夾"
                    else -> "尚未授權資料夾"
                },
                onClick = { activeSheet = EditSheet.SOURCES },
            )
            Spacer(Modifier.height(14.dp))
            val dayText = when (state.days) {
                1 -> "最近 1 天"
                3 -> "最近 3 天"
                7 -> "最近 7 天"
                else -> "全部檔案"
            }
            val sizeText = state.maxMbText.takeIf { it.isNotBlank() }?.let { "≤ $it MB" } ?: "不限大小"
            BackupSettingsCard(
                folderName = if (state.useTargetFolder) {
                    state.targetFolder.ifBlank { "Android" }
                } else {
                    "直接存至目的地"
                },
                dayText = dayText,
                sizeText = sizeText,
                modeText = if (state.backupMode == BackupMode.TIMESTAMP) {
                    "時間戳記"
                } else {
                    "增量比對"
                },
                onClick = { activeSheet = EditSheet.BACKUP_SETTINGS },
            )
            Spacer(Modifier.height(14.dp))
            DestinationCard(
                state = state,
                onTypeSelected = { type ->
                    viewModel.setDestinationType(type)
                    if (type == DestinationType.USB && state.usbDestination == null) {
                        usbDestinationPicker.launch(null)
                    }
                },
                onLocalSendClick = { activeSheet = EditSheet.RECEIVER },
                onUsbClick = { usbDestinationPicker.launch(null) },
            )
            Spacer(Modifier.height(8.dp))
            CompactProgress(
                state = state,
                busy = busy,
                onRetryFailed = viewModel::retryFailed,
                onCancel = viewModel::cancelTransfer,
            )
        }
    }

    activeSheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = { activeSheet = null }) {
            when (sheet) {
                EditSheet.SOURCES -> SourcesSheet(
                    state = state,
                    viewModel = viewModel,
                    onDcimGrant = { dcimPicker.launch(initialDocumentUri("primary:DCIM")) },
                    onPicturesGrant = { picturesPicker.launch(initialDocumentUri("primary:Pictures")) },
                    onDone = { activeSheet = null },
                )
                EditSheet.DESTINATION -> DestinationSheet(
                    state = state,
                    viewModel = viewModel,
                    onDone = { activeSheet = null },
                )
                EditSheet.FILTERS -> FiltersSheet(
                    state = state,
                    viewModel = viewModel,
                    busy = busy,
                    onDone = { activeSheet = null },
                )
                EditSheet.BACKUP_SETTINGS -> BackupSettingsSheet(
                    state = state,
                    viewModel = viewModel,
                    busy = busy,
                    onDone = { activeSheet = null },
                )
                EditSheet.RECEIVER -> ReceiverSheet(
                    state = state,
                    viewModel = viewModel,
                    busy = busy,
                    onAdvanced = { activeSheet = EditSheet.ADVANCED },
                    onDone = { activeSheet = null },
                )
                EditSheet.ADVANCED -> AdvancedSheet(
                    state = state,
                    viewModel = viewModel,
                    busy = busy,
                    onPrivacy = { activeSheet = EditSheet.PRIVACY },
                    onDone = { activeSheet = null },
                )
                EditSheet.PRIVACY -> PrivacySheet(
                    onDone = { activeSheet = EditSheet.ADVANCED },
                )
            }
        }
    }

    state.browser?.let { FileBrowserDialog(state, viewModel) }
    ConfirmationDialogs(state, viewModel)
}

@Composable
private fun ReadyStatusLine(
    ready: Boolean,
    sourcesReady: Boolean,
) {
    val success = Color(0xFF2E7D32)
    val color = if (ready) success else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.75f))
        Surface(
            shape = CircleShape,
            color = color,
        ) {
            Icon(
                if (ready) Icons.Default.Check else Icons.Default.Folder,
                null,
                modifier = Modifier.padding(5.dp).size(18.dp),
                tint = Color.White,
            )
        }
        Text(
            when {
                ready -> "已準備完成"
                sourcesReady -> "請選擇備份目的地"
                else -> "需要資料夾授權"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = 0.75f))
    }
}

@Composable
private fun LargeActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 126.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFFFFCFF),
        border = BorderStroke(1.dp, Color(0xFFE8E2EC)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                color = Color(0xFFF0EAF7),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        null,
                        modifier = Modifier.size(38.dp),
                        tint = Color(0xFF6650A4),
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                "編輯",
                modifier = Modifier.size(32.dp),
                tint = Color(0xFF6650A4),
            )
        }
    }
}

@Composable
private fun BackupSettingsCard(
    folderName: String,
    dayText: String,
    sizeText: String,
    modeText: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 142.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFFFFCFF),
        border = BorderStroke(1.dp, Color(0xFFE8E2EC)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                color = Color(0xFFF0EAF7),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Tune,
                        null,
                        modifier = Modifier.size(38.dp),
                        tint = Color(0xFF6650A4),
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "備份設定",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    folderName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniTag(
                        if (sizeText == "不限大小") dayText else "$dayText・$sizeText"
                    )
                    MiniTag(modeText)
                }
            }
            Icon(
                Icons.Default.ChevronRight,
                "編輯備份設定",
                modifier = Modifier.size(32.dp),
                tint = Color(0xFF6650A4),
            )
        }
    }
}

@Composable
private fun MiniTag(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFF0ECF4),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun DestinationCard(
    state: MainUiState,
    onTypeSelected: (DestinationType) -> Unit,
    onLocalSendClick: () -> Unit,
    onUsbClick: () -> Unit,
) {
    val selectedDevice = state.devices.firstOrNull { it.key == state.selectedDeviceKey }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFFFFCFF),
        border = BorderStroke(1.dp, Color(0xFFE8E2EC)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                color = Color(0xFFF0EAF7),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Devices,
                        null,
                        modifier = Modifier.size(38.dp),
                        tint = Color(0xFF6650A4),
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "備份目的地",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DestinationSegment(
                        modifier = Modifier.weight(1f),
                        selected = state.destinationType == DestinationType.LOCALSEND,
                        icon = Icons.Default.Devices,
                        text = "LocalSend",
                        onClick = { onTypeSelected(DestinationType.LOCALSEND) },
                    )
                    DestinationSegment(
                        modifier = Modifier.weight(1f),
                        selected = state.destinationType == DestinationType.USB,
                        icon = Icons.Default.Usb,
                        text = "USB 外接磁碟",
                        onClick = { onTypeSelected(DestinationType.USB) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClick = if (state.destinationType == DestinationType.USB) {
                                onUsbClick
                            } else {
                                onLocalSendClick
                            }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (state.destinationType == DestinationType.USB) {
                            Icons.Default.Usb
                        } else {
                            Icons.Default.Devices
                        },
                        null,
                        tint = Color(0xFF6650A4),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.destinationType == DestinationType.USB) {
                                state.usbDestination?.displayName ?: "選擇 USB 外接磁碟"
                            } else {
                                selectedDevice?.alias ?: "選擇 LocalSend 接收端"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (state.destinationType == DestinationType.USB) {
                                if (state.usbDestination == null) {
                                    "插入磁碟後指定目的地資料夾"
                                } else {
                                    "已授權；點選可重新選擇"
                                }
                            } else {
                                selectedDevice?.let { "${it.protocol}://${it.ip}:${it.port}" }
                                    ?: "點選搜尋附近裝置"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        "選擇目的地",
                        tint = Color(0xFF6650A4),
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationSegment(
    modifier: Modifier,
    selected: Boolean,
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(46.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFFEDE4FF) else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) Color(0xFF6650A4) else Color(0xFFC9C2CE),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = Color(0xFF6650A4))
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CompactProgress(
    state: MainUiState,
    busy: Boolean,
    onRetryFailed: () -> Unit,
    onCancel: () -> Unit,
) {
    val progress = state.progress
    val fraction = if (progress.totalBytes > 0) {
        (progress.sentBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
    } else 0f
    val complete = progress.stage == TransferStage.COMPLETED &&
        state.failedFilesCount == 0
    val showLiveProgress = progress.stage in setOf(
        TransferStage.PREPARING,
        TransferStage.SENDING,
        TransferStage.ERROR,
    )
    val canCancel = progress.stage in setOf(
        TransferStage.PREPARING,
        TransferStage.SENDING,
    )
    val summary = state.lastBackupSummary
    val title = when {
        showLiveProgress -> progress.message.ifBlank { "就緒" }
        summary != null -> "上次備份 ${formatTimestamp(summary.finishedAt)}"
        else -> progress.message.ifBlank { "就緒" }
    }
    val detail = when {
        showLiveProgress && progress.totalFiles > 0 ->
            "${progress.completedFiles}/${progress.totalFiles} 個檔案"
        summary?.status == "no_changes" ->
            "沒有新檔案需要傳送"
        summary != null ->
            "成功 ${summary.successfulFiles}・失敗 ${summary.failedFiles}・${formatBytes(summary.successfulBytes)}"
        else -> null
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = when {
            busy -> MaterialTheme.colorScheme.surfaceVariant
            progress.stage == TransferStage.ERROR -> MaterialTheme.colorScheme.errorContainer
            else -> Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when {
                    busy -> Icons.Default.InsertDriveFile
                    progress.stage == TransferStage.ERROR -> Icons.Default.InsertDriveFile
                    else -> Icons.Default.AccessTime
                },
                null,
                modifier = Modifier.size(30.dp),
                tint = if (complete) Color(0xFF2E7D32) else Color(0xFF6650A4),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy && progress.totalBytes > 0) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${formatBytes(progress.sentBytes)} / ${formatBytes(progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (canCancel) {
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
            } else if (!busy && state.failedFilesCount > 0) {
                TextButton(onClick = onRetryFailed) {
                    Text("重試 ${state.failedFilesCount}")
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onDone) { Text("完成") }
    }
}

@Composable
private fun SourcesSheet(
    state: MainUiState,
    viewModel: MainViewModel,
    onDcimGrant: () -> Unit,
    onPicturesGrant: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        SheetHeader("備份來源", onDone)
        RootSourceRow(
            kind = RootKind.DCIM,
            grantedName = state.roots[RootKind.DCIM]?.displayName,
            includeAll = state.includeAll[RootKind.DCIM] != false,
            selectedCount = state.selections.count { it.kind == RootKind.DCIM },
            onGrant = onDcimGrant,
            onIncludeAll = { viewModel.setIncludeAll(RootKind.DCIM, it) },
            onBrowse = { viewModel.openBrowser(RootKind.DCIM) },
        )
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        RootSourceRow(
            kind = RootKind.PICTURES,
            grantedName = state.roots[RootKind.PICTURES]?.displayName,
            includeAll = state.includeAll[RootKind.PICTURES] != false,
            selectedCount = state.selections.count { it.kind == RootKind.PICTURES },
            onGrant = onPicturesGrant,
            onIncludeAll = { viewModel.setIncludeAll(RootKind.PICTURES, it) },
            onBrowse = { viewModel.openBrowser(RootKind.PICTURES) },
        )
    }
}

@Composable
private fun DestinationSheet(
    state: MainUiState,
    viewModel: MainViewModel,
    onDone: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        SheetHeader("資料夾名稱", onDone)
        OutlinedTextField(
            value = state.targetFolder,
            onValueChange = viewModel::setTargetFolder,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("資料夾名稱") },
            supportingText = {
                Text("${state.targetFolder.ifBlank { "Android" }}/DCIM 與 Pictures")
            },
        )
    }
}

@Composable
private fun BackupSettingsSheet(
    state: MainUiState,
    viewModel: MainViewModel,
    busy: Boolean,
    onDone: () -> Unit,
) {
    val activeSourceNames = RootKind.entries
        .filter(state.roots::containsKey)
        .joinToString("、") { it.title }
        .ifBlank { "DCIM、Pictures" }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        SheetHeader("備份設定", onDone)
        Text("資料夾", fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("建立機型資料夾", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.useTargetFolder) {
                        "目的地會建立指定名稱，再放入 $activeSourceNames"
                    } else {
                        "直接將 $activeSourceNames 存到目的地"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.useTargetFolder,
                onCheckedChange = viewModel::setUseTargetFolder,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.targetFolder,
            onValueChange = viewModel::setTargetFolder,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.useTargetFolder,
            singleLine = true,
            label = { Text("資料夾名稱") },
            supportingText = {
                Text(
                    if (state.useTargetFolder) {
                        "${state.targetFolder.ifBlank { "Android" }}/$activeSourceNames"
                    } else {
                        activeSourceNames
                    }
                )
            },
        )
        HorizontalDivider(Modifier.padding(vertical = 14.dp))
        Text("檔案條件", fontWeight = FontWeight.SemiBold)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("略過大於")
            OutlinedTextField(
                value = state.maxMbText,
                onValueChange = viewModel::setMaxMb,
                modifier = Modifier.width(110.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                placeholder = { Text("不限") },
            )
            Text("MB")
        }
        Spacer(Modifier.height(8.dp))
        Text("最近修改", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "1 天", 3 to "3 天", 7 to "7 天", 0 to "全部").forEach { (days, label) ->
                FilterChip(
                    selected = state.days == days,
                    onClick = { viewModel.setDays(days) },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("重複備份檢查", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.backupMode == BackupMode.TIMESTAMP,
                onClick = { viewModel.setBackupMode(BackupMode.TIMESTAMP) },
                label = { Text("時間戳記") },
            )
            FilterChip(
                selected = state.backupMode == BackupMode.INCREMENTAL,
                onClick = { viewModel.setBackupMode(BackupMode.INCREMENTAL) },
                label = { Text("增量比對") },
            )
        }
        Text(
            if (state.backupMode == BackupMode.TIMESTAMP) {
                state.lastCheckpoint?.let {
                    "上次備份起點：${formatTimestamp(it)}"
                } ?: "首次會完整備份，成功後記錄時間"
            } else {
                "依路徑、大小與修改時間略過未變更檔案"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = viewModel::analyze,
            enabled = !busy && state.roots.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("分析檔案")
        }
        state.scanResult?.let { ScanSummary(it) }
    }
}

@Composable
private fun FiltersSheet(
    state: MainUiState,
    viewModel: MainViewModel,
    busy: Boolean,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        SheetHeader("過濾條件", onDone)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("略過大於")
            OutlinedTextField(
                value = state.maxMbText,
                onValueChange = viewModel::setMaxMb,
                modifier = Modifier.width(110.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                placeholder = { Text("不限") },
            )
            Text("MB")
        }
        Spacer(Modifier.height(10.dp))
        Text("最近修改", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "1 天", 3 to "3 天", 7 to "7 天", 0 to "全部").forEach { (days, label) ->
                FilterChip(
                    selected = state.days == days,
                    onClick = { viewModel.setDays(days) },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("重複備份檢查", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.backupMode == BackupMode.TIMESTAMP,
                onClick = { viewModel.setBackupMode(BackupMode.TIMESTAMP) },
                label = { Text("時間戳記") },
            )
            FilterChip(
                selected = state.backupMode == BackupMode.INCREMENTAL,
                onClick = { viewModel.setBackupMode(BackupMode.INCREMENTAL) },
                label = { Text("增量比對") },
            )
        }
        Text(
            if (state.backupMode == BackupMode.TIMESTAMP) {
                state.lastCheckpoint?.let {
                    "上次完整備份起點：${formatTimestamp(it)}；只傳送之後修改的檔案"
                } ?: "首次會完整備份；成功後記錄時間，之後只傳送新修改檔案"
            } else {
                "依路徑、大小與修改時間略過未變更的已備份檔案"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = viewModel::analyze,
            enabled = !busy && state.roots.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("分析檔案") }
        state.scanResult?.let { ScanSummary(it) }
    }
}

@Composable
private fun ReceiverSheet(
    state: MainUiState,
    viewModel: MainViewModel,
    busy: Boolean,
    onAdvanced: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        SheetHeader("LocalSend 接收端", onDone)
        Button(
            onClick = viewModel::discoverDevices,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("搜尋附近接收端")
        }
        state.devices.forEach { device ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectDevice(device.key) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.selectedDeviceKey == device.key,
                    onClick = { viewModel.selectDevice(device.key) },
                )
                Column(Modifier.weight(1f)) {
                    Text(device.alias, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${device.protocol}://${device.ip}:${device.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onClick = onAdvanced, modifier = Modifier.align(Alignment.End)) {
            Text("手動位址、PIN 與備份紀錄")
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun AdvancedSheet(
    state: MainUiState,
    viewModel: MainViewModel,
    busy: Boolean,
    onPrivacy: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        SheetHeader("進階設定", onDone)
        if (state.destinationType == DestinationType.LOCALSEND) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.manualAddress,
                    onValueChange = viewModel::setManualAddress,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("手動位址") },
                )
                OutlinedButton(onClick = viewModel::addManualDevice) { Text("加入") }
            }
            OutlinedTextField(
                value = state.pin,
                onValueChange = viewModel::setPin,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                label = { Text("接收 PIN（若有）") },
            )
        }
        val destinationReady =
            (state.destinationType == DestinationType.LOCALSEND &&
                state.selectedDeviceKey != null) ||
                (state.destinationType == DestinationType.USB &&
                    state.usbDestination != null)
        if (destinationReady) {
            Spacer(Modifier.height(8.dp))
            if (state.backupMode == BackupMode.TIMESTAMP ||
                state.destinationType == DestinationType.LOCALSEND
            ) {
                OutlinedButton(
                    onClick = viewModel::requestCreateBaseline,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.backupMode == BackupMode.TIMESTAMP) {
                            "以目前時間建立備份起點"
                        } else {
                            "以接收端現有檔案建立基準"
                        }
                    )
                }
            }
            TextButton(
                onClick = viewModel::requestClearHistory,
                enabled = !busy,
                modifier = Modifier.align(Alignment.End),
            ) { Text("清除目前目的地的備份紀錄") }
        }
        Text(
            if (state.destinationType == DestinationType.USB) {
                "USB 模式會覆寫相同路徑的既有檔案，避免重試產生重複副本。"
            } else {
                "接收端請關閉「儲存至相簿」，改用一般資料夾儲存，才能保留目錄結構。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        TextButton(
            onClick = onPrivacy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("隱私權政策")
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ChevronRight, null)
        }
        Text(
            "影音快速備份（QuickSend 0.1.12）",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrivacySheet(onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        SheetHeader("隱私權政策", onDone)
        Text("最後更新：2026 年 8 月 6 日", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("資料存取", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "QuickSend 只會在你透過 Android 系統明確授權後，讀取所選的 DCIM、Pictures 或 USB 資料夾。檔案只會在你按下開始備份後，傳送到你選擇的區域網路接收端或 USB 儲存裝置。"
        )
        Spacer(Modifier.height(12.dp))
        Text("不收集使用者資料", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "本 App 沒有開發者伺服器，不含廣告、分析或追蹤 SDK。開發者不會收集、上傳、儲存或販售你的照片、影片、檔案或其他個人資料。"
        )
        Spacer(Modifier.height(12.dp))
        Text("本機設定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "來源與目的地、篩選條件、備份紀錄、接收端資訊及選填 PIN 只保存在你的裝置。你可以清除 App 儲存空間或解除安裝來刪除。"
        )
        Spacer(Modifier.height(12.dp))
        Text("傳輸安全", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "LocalSend 傳輸在可用時使用 HTTPS 並驗證接收端憑證。若你自行指定 HTTP 接收端，安全性取決於所使用的區域網路。"
        )
    }
}

@Composable
private fun RootSourceRow(
    kind: RootKind,
    grantedName: String?,
    includeAll: Boolean,
    selectedCount: Int,
    onGrant: () -> Unit,
    onIncludeAll: (Boolean) -> Unit,
    onBrowse: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(kind.title, fontWeight = FontWeight.SemiBold)
            Text(
                grantedName?.let { "已授權：$it" } ?: "尚未授權",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onGrant) { Text(if (grantedName == null) "授權" else "重選") }
    }
    if (grantedName != null) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onIncludeAll(!includeAll) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(includeAll, onIncludeAll)
            Text("包含此資料夾下全部檔案")
        }
        if (!includeAll) {
            OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                Text("選擇項目（已選 $selectedCount 項）")
            }
        }
    }
}

@Composable
private fun ScanSummary(result: ScanResult) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("${result.files.size} 個待傳・${formatBytes(result.totalBytes)}", fontWeight = FontWeight.Bold)
            Text(
                "已備份略過 ${result.skippedAlreadyBackedUp}・大小 ${result.skippedBySize}・日期 ${result.skippedByDate}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FileBrowserDialog(state: MainUiState, viewModel: MainViewModel) {
    val browser = state.browser ?: return
    Dialog(onDismissRequest = viewModel::closeBrowser) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = viewModel::browserBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(browser.title, fontWeight = FontWeight.Bold)
                        Text(
                            browser.currentPath.ifBlank { browser.kind.title },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(onClick = viewModel::closeBrowser) { Text("完成") }
                }
                HorizontalDivider()
                if (browser.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("讀取中…") }
                } else if (browser.nodes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("這個資料夾是空的") }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(browser.nodes, key = { it.uri.toString() }) { node ->
                            val checked = state.selections.any {
                                it.kind == browser.kind && it.uri == node.uri
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleSelection(node) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked, { viewModel.toggleSelection(node) })
                                Icon(
                                    if (node.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    null,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(node.name, modifier = Modifier.weight(1f), maxLines = 2)
                                if (node.isDirectory) {
                                    IconButton(onClick = { viewModel.enterDirectory(node) }) {
                                        Icon(Icons.Default.ChevronRight, "開啟資料夾")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmationDialogs(state: MainUiState, viewModel: MainViewModel) {
    if (state.confirmClearHistory) {
        AlertDialog(
            onDismissRequest = viewModel::cancelClearHistory,
            title = { Text("清除備份紀錄？") },
            text = { Text("不會刪除目的地檔案，但下次會重新備份目前資料夾內的檔案。") },
            confirmButton = { TextButton(onClick = viewModel::clearHistory) { Text("清除") } },
            dismissButton = { TextButton(onClick = viewModel::cancelClearHistory) { Text("取消") } },
        )
    }
    if (state.confirmCreateBaseline) {
        AlertDialog(
            onDismissRequest = viewModel::cancelCreateBaseline,
            title = {
                Text(
                    if (state.backupMode == BackupMode.TIMESTAMP) {
                        "建立時間戳記起點？"
                    } else {
                        "建立現有備份基準？"
                    }
                )
            },
            text = {
                Text(
                    if (state.backupMode == BackupMode.TIMESTAMP) {
                        "不會傳送檔案；之後只備份目前時間以後修改的檔案。"
                    } else {
                        "將目前符合條件的檔案標記為已備份，但不會實際傳送。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::createBaseline) {
                    Text("建立基準")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelCreateBaseline) { Text("取消") } },
        )
    }
}

private fun initialDocumentUri(documentId: String): Uri =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    do {
        value /= 1024.0
        index++
    } while (value >= 1024 && index < units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
