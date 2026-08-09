package tw.chehu.quicksend.model

import android.net.Uri

enum class RootKind(val title: String, val wireName: String) {
    DCIM("DCIM", "DCIM"),
    PICTURES("Pictures", "Pictures"),
}

data class GrantedRoot(
    val kind: RootKind,
    val uri: Uri,
    val displayName: String,
)

data class DocumentNode(
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
)

data class SourceSelection(
    val kind: RootKind,
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
)

data class BackupFilter(
    val maxBytes: Long? = null,
    val newerThanMillis: Long? = null,
)

data class TransferFile(
    val uri: Uri,
    val destinationPath: String,
    val size: Long,
    val mimeType: String,
    val lastModified: Long,
)

data class ScanResult(
    val files: List<TransferFile> = emptyList(),
    val skippedBySize: Int = 0,
    val skippedByDate: Int = 0,
    val skippedAlreadyBackedUp: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

data class LocalSendDevice(
    val alias: String,
    val ip: String,
    val port: Int,
    val protocol: String,
    val fingerprint: String,
) {
    val key: String get() = "$protocol://$ip:$port"
}

enum class DestinationType {
    LOCALSEND,
    USB,
}

enum class BackupMode {
    TIMESTAMP,
    INCREMENTAL,
}

data class UsbDestination(
    val uri: Uri,
    val displayName: String,
) {
    fun historyDevice(): LocalSendDevice = LocalSendDevice(
        alias = displayName,
        ip = "",
        port = 0,
        protocol = "usb",
        fingerprint = "usb:$uri",
    )
}

data class BackupSummary(
    val finishedAt: Long,
    val successfulFiles: Int,
    val failedFiles: Int,
    val successfulBytes: Long,
    val status: String,
)

enum class TransferStage {
    IDLE, SCANNING, DISCOVERING, PREPARING, SENDING, COMPLETED, ERROR
}

data class TransferProgress(
    val stage: TransferStage = TransferStage.IDLE,
    val currentFile: String = "",
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val sentBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String = "",
)
