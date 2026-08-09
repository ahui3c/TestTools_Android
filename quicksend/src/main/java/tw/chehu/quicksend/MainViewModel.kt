package tw.chehu.quicksend

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tw.chehu.quicksend.localsend.LocalSendDiscovery
import tw.chehu.quicksend.model.BackupFilter
import tw.chehu.quicksend.model.BackupMode
import tw.chehu.quicksend.model.BackupSummary
import tw.chehu.quicksend.model.DocumentNode
import tw.chehu.quicksend.model.DestinationType
import tw.chehu.quicksend.model.GrantedRoot
import tw.chehu.quicksend.model.LocalSendDevice
import tw.chehu.quicksend.model.RootKind
import tw.chehu.quicksend.model.ScanResult
import tw.chehu.quicksend.model.SourceSelection
import tw.chehu.quicksend.model.TransferProgress
import tw.chehu.quicksend.model.TransferStage
import tw.chehu.quicksend.model.UsbDestination
import tw.chehu.quicksend.storage.DocumentRepository
import tw.chehu.quicksend.storage.BackupHistoryRepository
import tw.chehu.quicksend.transfer.TransferRequest
import tw.chehu.quicksend.transfer.TransferRuntime
import tw.chehu.quicksend.transfer.TransferService
import java.util.concurrent.TimeUnit

data class BrowserState(
    val kind: RootKind,
    val currentUri: Uri,
    val currentPath: String,
    val title: String,
    val nodes: List<DocumentNode> = emptyList(),
    val backStack: List<Triple<Uri, String, String>> = emptyList(),
    val loading: Boolean = true,
)

data class MainUiState(
    val roots: Map<RootKind, GrantedRoot> = emptyMap(),
    val includeAll: Map<RootKind, Boolean> = RootKind.entries.associateWith { true },
    val selections: List<SourceSelection> = emptyList(),
    val useTargetFolder: Boolean = true,
    val targetFolder: String = Build.MODEL.ifBlank { "Android" },
    val maxMbText: String = "",
    val days: Int = 0,
    val scanResult: ScanResult? = null,
    val devices: List<LocalSendDevice> = emptyList(),
    val selectedDeviceKey: String? = null,
    val destinationType: DestinationType = DestinationType.LOCALSEND,
    val usbDestination: UsbDestination? = null,
    val manualAddress: String = "",
    val pin: String = "",
    val showManualOptions: Boolean = false,
    val backupMode: BackupMode = BackupMode.TIMESTAMP,
    val confirmClearHistory: Boolean = false,
    val confirmCreateBaseline: Boolean = false,
    val browser: BrowserState? = null,
    val progress: TransferProgress = TransferProgress(),
    val lastBackupSummary: BackupSummary? = null,
    val failedFilesCount: Int = 0,
    val lastCheckpoint: Long? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val documents = DocumentRepository(application)
    private val discovery = LocalSendDiscovery(application)
    private val history = BackupHistoryRepository(application)
    private val preferences = application.getSharedPreferences("quicksend", Context.MODE_PRIVATE)
    private val rememberedDevice = loadRememberedDevice()
    private val rememberedDestinationType = runCatching {
        DestinationType.valueOf(
            preferences.getString("destination_type", DestinationType.LOCALSEND.name)
                ?: DestinationType.LOCALSEND.name
        )
    }.getOrDefault(DestinationType.LOCALSEND)
    private val restoredUsbDestination = documents.restoreUsbDestination()
    private val rememberedBackupMode = runCatching {
        BackupMode.valueOf(
            preferences.getString("backup_mode", BackupMode.TIMESTAMP.name)
                ?: BackupMode.TIMESTAMP.name
        )
    }.getOrDefault(BackupMode.TIMESTAMP)

    private val _state = MutableStateFlow(
        MainUiState(
            roots = RootKind.entries.mapNotNull { kind ->
                documents.restoreRoot(kind)?.let { kind to it }
            }.toMap(),
            includeAll = RootKind.entries.associateWith { kind ->
                preferences.getBoolean("include_all_${kind.name}", true)
            },
            selections = restoreSelections(),
            useTargetFolder = preferences.getBoolean("use_target_folder", true),
            targetFolder = if (preferences.contains("target_folder")) {
                preferences.getString("target_folder", "").orEmpty()
            } else {
                Build.MODEL.ifBlank { "Android" }
            },
            maxMbText = preferences.getString("max_mb", "").orEmpty(),
            days = preferences.getInt("days", 0).takeIf { it in setOf(0, 1, 3, 7) } ?: 0,
            devices = rememberedDevice?.let(::listOf).orEmpty(),
            selectedDeviceKey = rememberedDevice?.key,
            destinationType = rememberedDestinationType,
            usbDestination = restoredUsbDestination,
            manualAddress = preferences.getString("manual_address", "").orEmpty(),
            pin = preferences.getString("receiver_pin", "").orEmpty(),
            showManualOptions = preferences.getBoolean("show_manual_options", false),
            backupMode = rememberedBackupMode,
        )
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            TransferRuntime.progress.collect { progress ->
                if (progress.stage != TransferStage.IDLE) {
                    _state.update { it.copy(progress = progress) }
                    if (progress.stage in setOf(TransferStage.COMPLETED, TransferStage.ERROR)) {
                        refreshBackupStatus()
                    }
                }
            }
        }
        if (rememberedDestinationType == DestinationType.LOCALSEND &&
            rememberedDevice != null &&
            !TransferRuntime.isBusy
        ) {
            discoverDevices()
        } else {
            refreshBackupStatus()
        }
    }

    fun grantRoot(kind: RootKind, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { documents.saveRoot(kind, uri) }
            }.onSuccess { root ->
                _state.update {
                    it.copy(
                        roots = it.roots + (kind to root),
                        scanResult = null,
                        progress = TransferProgress(message = "${kind.title} 已授權"),
                    )
                }
            }.onFailure(::showError)
        }
    }

    fun grantUsbDestination(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { documents.saveUsbDestination(uri) }
            }.onSuccess { destination ->
                preferences.edit()
                    .putString("destination_type", DestinationType.USB.name)
                    .apply()
                _state.update {
                    it.copy(
                        destinationType = DestinationType.USB,
                        usbDestination = destination,
                        scanResult = null,
                        progress = TransferProgress(
                            message = "${destination.displayName} 已授權寫入",
                        ),
                    )
                }
                refreshBackupStatus()
            }.onFailure(::showError)
        }
    }

    fun setDestinationType(value: DestinationType) {
        preferences.edit().putString("destination_type", value.name).apply()
        _state.update {
            it.copy(destinationType = value, scanResult = null)
        }
        refreshBackupStatus()
        if (value == DestinationType.LOCALSEND &&
            _state.value.devices.isEmpty() &&
            !TransferRuntime.isBusy
        ) {
            discoverDevices()
        }
    }

    fun setTargetFolder(value: String) {
        preferences.edit().putString("target_folder", value).apply()
        _state.update { it.copy(targetFolder = value, scanResult = null) }
        refreshBackupStatus()
    }
    fun setUseTargetFolder(value: Boolean) {
        preferences.edit().putBoolean("use_target_folder", value).apply()
        _state.update { it.copy(useTargetFolder = value, scanResult = null) }
        refreshBackupStatus()
    }
    fun setMaxMb(value: String) {
        if (value.isBlank() || value.matches(Regex("""\d{0,6}([.]\d{0,2})?"""))) {
            preferences.edit().putString("max_mb", value).apply()
            _state.update { it.copy(maxMbText = value, scanResult = null) }
        }
    }
    fun setDays(value: Int) {
        preferences.edit().putInt("days", value).apply()
        _state.update { it.copy(days = value, scanResult = null) }
    }
    fun setPin(value: String) {
        val normalized = value.filter(Char::isDigit).take(12)
        preferences.edit().putString("receiver_pin", normalized).apply()
        _state.update { it.copy(pin = normalized) }
    }
    fun setManualAddress(value: String) {
        preferences.edit().putString("manual_address", value).apply()
        _state.update { it.copy(manualAddress = value) }
    }
    fun toggleManualOptions() = _state.update {
        val next = !it.showManualOptions
        preferences.edit().putBoolean("show_manual_options", next).apply()
        it.copy(showManualOptions = next)
    }
    fun setBackupMode(value: BackupMode) {
        preferences.edit().putString("backup_mode", value.name).apply()
        _state.update {
            it.copy(backupMode = value, scanResult = null)
        }
        refreshBackupStatus()
    }
    fun requestClearHistory() = _state.update { it.copy(confirmClearHistory = true) }
    fun cancelClearHistory() = _state.update { it.copy(confirmClearHistory = false) }
    fun requestCreateBaseline() = _state.update { it.copy(confirmCreateBaseline = true) }
    fun cancelCreateBaseline() = _state.update { it.copy(confirmCreateBaseline = false) }
    fun setIncludeAll(kind: RootKind, value: Boolean) {
        preferences.edit().putBoolean("include_all_${kind.name}", value).apply()
        _state.update {
            it.copy(includeAll = it.includeAll + (kind to value), scanResult = null)
        }
    }
    fun selectDevice(key: String) {
        val device = _state.value.devices.firstOrNull { it.key == key } ?: return
        rememberDevice(device)
        _state.update {
            it.copy(selectedDeviceKey = key, scanResult = null)
        }
        refreshBackupStatus()
    }

    fun analyze() {
        val snapshot = _state.value
        if (snapshot.roots.isEmpty()) {
            showError(IllegalStateException("請先授權 DCIM 或 Pictures"))
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(progress = TransferProgress(stage = TransferStage.SCANNING, message = "正在分析檔案…"))
            }
            val device = destinationIdentity(snapshot)
            runCatching { scan(snapshot, device) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            scanResult = result,
                            progress = TransferProgress(
                                stage = TransferStage.IDLE,
                                totalFiles = result.files.size,
                                totalBytes = result.totalBytes,
                                message = "分析完成",
                            )
                        )
                    }
                }
                .onFailure(::showError)
        }
    }

    fun discoverDevices() {
        if (_state.value.destinationType != DestinationType.LOCALSEND) return
        viewModelScope.launch {
            _state.update {
                it.copy(progress = TransferProgress(stage = TransferStage.DISCOVERING, message = "搜尋 LocalSend 接收端…"))
            }
            runCatching { discovery.discover() }
                .onSuccess { devices ->
                    _state.update { current ->
                        val remembered = loadRememberedDevice()
                        val selected = devices.firstOrNull { candidate ->
                            remembered?.fingerprint?.isNotBlank() == true &&
                                normalizeFingerprint(candidate.fingerprint) ==
                                normalizeFingerprint(remembered.fingerprint)
                        } ?: devices.firstOrNull { candidate ->
                            remembered?.fingerprint?.isBlank() == true &&
                                candidate.key == remembered.key
                        } ?: if (remembered == null) {
                            devices.firstOrNull { it.key == current.selectedDeviceKey }
                                ?: devices.firstOrNull()
                        } else {
                            null
                        }
                        selected?.let(::rememberDevice)
                        current.copy(
                            devices = devices,
                            selectedDeviceKey = selected?.key,
                            progress = TransferProgress(
                                message = when {
                                    devices.isEmpty() -> "找不到上次接收端，可重新搜尋"
                                    selected != null &&
                                        remembered?.fingerprint?.isNotBlank() == true &&
                                        normalizeFingerprint(selected.fingerprint) ==
                                        normalizeFingerprint(remembered.fingerprint) ->
                                        "已自動連接 ${selected.alias}"
                                    else -> "找到 ${devices.size} 個接收端"
                                }
                            )
                        )
                    }
                    refreshBackupStatus()
                }
                .onFailure(::showError)
        }
    }

    fun addManualDevice() {
        val address = _state.value.manualAddress
        if (address.isBlank()) return
        runCatching { discovery.manualDevice(address) }
            .onSuccess { device ->
                rememberDevice(device)
                _state.update {
                    it.copy(
                        devices = (it.devices.filterNot { old -> old.key == device.key } + device),
                        selectedDeviceKey = device.key,
                    )
                }
                refreshBackupStatus()
            }
            .onFailure(::showError)
    }

    fun send() {
        val snapshot = _state.value
        val device = destinationIdentity(snapshot)
        if (device == null) {
            showError(
                IllegalStateException(
                    if (snapshot.destinationType == DestinationType.USB) {
                        "請先選擇 USB 外接磁碟資料夾"
                    } else {
                        "請先選擇 LocalSend 接收端"
                    }
                )
            )
            return
        }
        viewModelScope.launch {
            runCatching {
                val targetScope = targetScope(snapshot)
                _state.update {
                    it.copy(progress = TransferProgress(stage = TransferStage.SCANNING, message = "重新確認檔案…"))
                }
                val scanStartedAt = System.currentTimeMillis()
                val result = scan(snapshot, device)
                val checkpointCandidate = scanStartedAt.takeIf {
                    snapshot.backupMode == BackupMode.TIMESTAMP
                }
                _state.update {
                    it.copy(
                        scanResult = result,
                        progress = TransferProgress(
                            stage = TransferStage.PREPARING,
                            totalFiles = result.files.size,
                            totalBytes = result.totalBytes,
                            message = "等待接收端確認…",
                        )
                    )
                }
                if (result.files.isEmpty()) {
                    withContext(Dispatchers.IO) {
                        history.replaceFailures(device, targetScope, emptyList())
                        if (checkpointCandidate != null) {
                            history.completeCheckpoint(
                                device,
                                targetScope,
                                checkpointCandidate,
                            )
                        } else {
                            history.clearPendingCheckpoint(device, targetScope)
                        }
                        history.saveSummary(
                            device,
                            targetScope,
                            BackupSummary(
                                finishedAt = System.currentTimeMillis(),
                                successfulFiles = 0,
                                failedFiles = 0,
                                successfulBytes = 0,
                                status = "no_changes",
                            ),
                        )
                    }
                    _state.update {
                        it.copy(progress = TransferProgress(
                            stage = TransferStage.COMPLETED,
                            totalFiles = 0,
                            totalBytes = 0,
                            message = if (result.skippedAlreadyBackedUp > 0) {
                                "沒有新檔案；已略過 ${result.skippedAlreadyBackedUp} 個已備份檔案"
                            } else {
                                "沒有符合條件的檔案"
                            },
                        ))
                    }
                    refreshBackupStatus()
                    return@runCatching
                }
                if (snapshot.destinationType == DestinationType.LOCALSEND) {
                    rememberDevice(device)
                }
                TransferService.start(
                    getApplication(),
                    TransferRequest(
                        device = device,
                        targetFolder = targetScope,
                        files = result.files,
                        pin = snapshot.pin,
                        retryOnly = false,
                        destinationType = snapshot.destinationType,
                        usbTreeUri = snapshot.usbDestination?.uri,
                        checkpointCandidate = checkpointCandidate,
                    ),
                )
            }.onFailure(::showError)
        }
    }

    fun retryFailed() {
        val snapshot = _state.value
        val device = destinationIdentity(snapshot)
        if (device == null) {
            showError(IllegalStateException("目前目的地尚未就緒"))
            return
        }
        viewModelScope.launch {
            runCatching {
                val targetScope = targetScope(snapshot)
                val failedFiles = withContext(Dispatchers.IO) {
                    history.loadFailures(device, targetScope)
                }
                if (failedFiles.isEmpty()) {
                    _state.update {
                        it.copy(progress = TransferProgress(message = "沒有需要重試的檔案"))
                    }
                    return@runCatching
                }
                val checkpointCandidate = withContext(Dispatchers.IO) {
                    history.loadPendingCheckpoint(device, targetScope)
                }
                TransferService.start(
                    getApplication(),
                    TransferRequest(
                        device = device,
                        targetFolder = targetScope,
                        files = failedFiles,
                        pin = snapshot.pin,
                        retryOnly = true,
                        destinationType = snapshot.destinationType,
                        usbTreeUri = snapshot.usbDestination?.uri,
                        checkpointCandidate = checkpointCandidate,
                    ),
                )
            }.onFailure(::showError)
        }
    }

    fun cancelTransfer() {
        TransferService.cancel(getApplication())
    }

    fun openBrowser(kind: RootKind) {
        val root = _state.value.roots[kind] ?: return
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            root.uri,
            DocumentsContract.getTreeDocumentId(root.uri),
        )
        loadBrowser(
            BrowserState(
                kind = kind,
                currentUri = documentUri,
                currentPath = "",
                title = kind.title,
            )
        )
    }

    fun enterDirectory(node: DocumentNode) {
        val browser = _state.value.browser ?: return
        loadBrowser(
            browser.copy(
                currentUri = node.uri,
                currentPath = node.relativePath,
                title = node.name,
                nodes = emptyList(),
                loading = true,
                backStack = browser.backStack + Triple(browser.currentUri, browser.currentPath, browser.title),
            )
        )
    }

    fun browserBack() {
        val browser = _state.value.browser ?: return
        val previous = browser.backStack.lastOrNull()
        if (previous == null) {
            closeBrowser()
            return
        }
        loadBrowser(
            browser.copy(
                currentUri = previous.first,
                currentPath = previous.second,
                title = previous.third,
                nodes = emptyList(),
                loading = true,
                backStack = browser.backStack.dropLast(1),
            )
        )
    }

    fun toggleSelection(node: DocumentNode) {
        val browser = _state.value.browser ?: return
        _state.update { state ->
            val exists = state.selections.any { it.kind == browser.kind && it.uri == node.uri }
            val next = if (exists) {
                state.selections.filterNot { it.kind == browser.kind && it.uri == node.uri }
            } else {
                state.selections + SourceSelection(
                    browser.kind, node.uri, node.name, node.relativePath, node.isDirectory
                )
            }
            persistSelections(next)
            state.copy(selections = next, scanResult = null)
        }
    }

    fun closeBrowser() = _state.update { it.copy(browser = null) }

    fun clearHistory() {
        val snapshot = _state.value
        val device = destinationIdentity(snapshot)
        if (device == null) {
            _state.update { it.copy(confirmClearHistory = false) }
            return
        }
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                history.clear(device, targetScope(snapshot))
            }
            _state.update {
                it.copy(
                    confirmClearHistory = false,
                    scanResult = null,
                    progress = TransferProgress(message = "已清除 $count 筆備份紀錄"),
                )
            }
            refreshBackupStatus()
        }
    }

    fun createBaseline() {
        val snapshot = _state.value
        val device = destinationIdentity(snapshot)
        if (device == null) {
            _state.update { it.copy(confirmCreateBaseline = false) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    confirmCreateBaseline = false,
                    progress = TransferProgress(
                        stage = TransferStage.SCANNING,
                        message = "正在建立現有備份基準…",
                    ),
                )
            }
            runCatching {
                val targetScope = targetScope(snapshot)
                if (snapshot.backupMode == BackupMode.TIMESTAMP) {
                    val checkpoint = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        history.replaceFailures(device, targetScope, emptyList())
                        history.completeCheckpoint(device, targetScope, checkpoint)
                    }
                    0
                } else {
                    val result = scan(snapshot, null)
                    withContext(Dispatchers.IO) {
                        history.recordSuccessfulBatch(device, targetScope, result.files)
                    }
                    result.files.size
                }
            }.onSuccess { count ->
                _state.update {
                    it.copy(
                        scanResult = null,
                        progress = TransferProgress(
                            stage = TransferStage.COMPLETED,
                            message = if (snapshot.backupMode == BackupMode.TIMESTAMP) {
                                "已將目前時間設為備份起點，未傳送檔案"
                            } else {
                                "已建立 $count 個檔案的備份基準，未傳送檔案"
                            },
                        ),
                    )
                }
                refreshBackupStatus()
            }.onFailure(::showError)
        }
    }

    private fun loadBrowser(browser: BrowserState) {
        _state.update { it.copy(browser = browser.copy(loading = true)) }
        viewModelScope.launch {
            runCatching {
                val root = _state.value.roots.getValue(browser.kind)
                withContext(Dispatchers.IO) {
                    documents.listChildren(root.uri, browser.currentUri, browser.currentPath)
                }
            }.onSuccess { nodes ->
                _state.update {
                    it.copy(browser = it.browser?.copy(nodes = nodes, loading = false))
                }
            }.onFailure(::showError)
        }
    }

    private suspend fun scan(
        snapshot: MainUiState,
        device: LocalSendDevice?,
    ): ScanResult = withContext(Dispatchers.IO) {
        val targetScope = targetScope(snapshot)
        val maxBytes = snapshot.maxMbText.toDoubleOrNull()
            ?.takeIf { it > 0 }
            ?.times(1024.0 * 1024.0)
            ?.toLong()
        val manualNewerThan = snapshot.days.takeIf { it > 0 }?.let {
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it.toLong())
        }
        val checkpoint = if (snapshot.backupMode == BackupMode.TIMESTAMP && device != null) {
            history.loadCheckpoint(device, targetScope)
        } else {
            null
        }
        val newerThan = listOfNotNull(manualNewerThan, checkpoint).maxOrNull()
        val rawResult = documents.scan(
            roots = snapshot.roots,
            includeAll = snapshot.includeAll,
            selections = snapshot.selections,
            targetFolder = targetScope,
            filter = BackupFilter(maxBytes, newerThan),
            onVisited = { count ->
                if (count % 25 == 0) {
                    _state.update { state ->
                        state.copy(progress = state.progress.copy(message = "已檢查 $count 個檔案…"))
                    }
                }
            }
        )
        if (device == null) {
            rawResult
        } else if (snapshot.backupMode == BackupMode.INCREMENTAL) {
            val (pending, skipped) = history.filterPending(
                device = device,
                targetFolder = targetScope,
                files = rawResult.files,
            )
            rawResult.copy(files = pending, skippedAlreadyBackedUp = skipped)
        } else if (checkpoint != null ||
            history.loadPendingCheckpoint(device, targetScope) != null ||
            history.hasBackupRecords(device, targetScope)
        ) {
            // Signature records are a safety net for files whose provider reports no
            // modification time, an unfinished timestamp window, and migration from
            // the previous incremental-only app version.
            val (pending, skipped) = history.filterPending(
                device = device,
                targetFolder = targetScope,
                files = rawResult.files,
            )
            rawResult.copy(files = pending, skippedAlreadyBackedUp = skipped)
        } else {
            rawResult
        }
    }

    private fun showError(error: Throwable) {
        _state.update {
            it.copy(progress = it.progress.copy(
                stage = TransferStage.ERROR,
                message = error.message ?: "發生未預期錯誤",
            ))
        }
    }

    private fun refreshBackupStatus() {
        val snapshot = _state.value
        val device = destinationIdentity(snapshot)
        val historyScope = targetScope(snapshot)
        if (device == null || (snapshot.useTargetFolder && historyScope.isBlank())) {
            _state.update {
                it.copy(
                    lastBackupSummary = null,
                    failedFilesCount = 0,
                    lastCheckpoint = null,
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val summary = history.loadSummary(device, historyScope)
            val failed = history.failedCount(device, historyScope)
            val checkpoint = history.loadCheckpoint(device, historyScope)
            _state.update { current ->
                if (current.destinationType == snapshot.destinationType &&
                    current.selectedDeviceKey == snapshot.selectedDeviceKey &&
                    current.usbDestination?.uri == snapshot.usbDestination?.uri &&
                    targetScope(current) == historyScope
                ) {
                    current.copy(
                        lastBackupSummary = summary,
                        failedFilesCount = failed,
                        lastCheckpoint = checkpoint,
                    )
                } else {
                    current
                }
            }
        }
    }

    private fun rememberDevice(device: LocalSendDevice) {
        preferences.edit()
            .putString("receiver_alias", device.alias)
            .putString("receiver_ip", device.ip)
            .putInt("receiver_port", device.port)
            .putString("receiver_protocol", device.protocol)
            .putString("receiver_fingerprint", device.fingerprint)
            .apply()
    }

    private fun loadRememberedDevice(): LocalSendDevice? {
        val ip = preferences.getString("receiver_ip", null)?.takeIf { it.isNotBlank() } ?: return null
        return LocalSendDevice(
            alias = preferences.getString("receiver_alias", null).orEmpty().ifBlank { "上次接收端" },
            ip = ip,
            port = preferences.getInt("receiver_port", 53317),
            protocol = preferences.getString("receiver_protocol", null).orEmpty().ifBlank { "https" },
            fingerprint = preferences.getString("receiver_fingerprint", null).orEmpty(),
        )
    }

    private fun normalizeFingerprint(value: String): String =
        value.lowercase().replace(":", "").trim()

    private fun targetScope(state: MainUiState): String =
        state.targetFolder.takeIf { state.useTargetFolder }.orEmpty()

    private fun restoreSelections(): List<SourceSelection> = runCatching {
        val array = JSONArray(preferences.getString("source_selections", "[]"))
        buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val kind = RootKind.valueOf(item.getString("kind"))
                add(
                    SourceSelection(
                        kind = kind,
                        uri = Uri.parse(item.getString("uri")),
                        name = item.getString("name"),
                        relativePath = item.getString("relativePath"),
                        isDirectory = item.getBoolean("isDirectory"),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun persistSelections(selections: List<SourceSelection>) {
        val array = JSONArray().apply {
            selections.forEach { selection ->
                put(
                    JSONObject()
                        .put("kind", selection.kind.name)
                        .put("uri", selection.uri.toString())
                        .put("name", selection.name)
                        .put("relativePath", selection.relativePath)
                        .put("isDirectory", selection.isDirectory)
                )
            }
        }
        preferences.edit().putString("source_selections", array.toString()).apply()
    }

    private fun destinationIdentity(state: MainUiState): LocalSendDevice? =
        when (state.destinationType) {
            DestinationType.LOCALSEND ->
                state.devices.firstOrNull { it.key == state.selectedDeviceKey }
            DestinationType.USB -> state.usbDestination?.historyDevice()
        }
}
