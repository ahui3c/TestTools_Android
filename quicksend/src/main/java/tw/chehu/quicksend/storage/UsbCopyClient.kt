package tw.chehu.quicksend.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tw.chehu.quicksend.model.TransferFile
import java.io.BufferedOutputStream
import kotlin.coroutines.coroutineContext

class UsbCopyClient(private val resolver: ContentResolver) {
    suspend fun copy(
        treeUri: Uri,
        files: List<TransferFile>,
        onFileStarted: (Int, TransferFile) -> Unit,
        onBytesCopied: (Int, Long) -> Unit,
        onFileCompleted: (TransferFile) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(files.isNotEmpty()) { "沒有符合條件的檔案" }
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val directoryCache = mutableMapOf("" to rootUri)

        files.forEachIndexed { index, file ->
            coroutineContext.ensureActive()
            onFileStarted(index, file)
            val segments = file.destinationPath
                .split('/')
                .filter(String::isNotBlank)
                .map(::sanitizeName)
            require(segments.isNotEmpty()) { "目的地檔名無效" }
            val parent = getOrCreateDirectories(
                treeUri = treeUri,
                rootUri = rootUri,
                segments = segments.dropLast(1),
                cache = directoryCache,
            )
            val targetUri = prepareTargetFile(
                treeUri = treeUri,
                parentUri = parent,
                displayName = segments.last(),
                mimeType = file.mimeType,
            )
            resolver.openInputStream(file.uri)?.use { input ->
                openTruncatedOutput(
                    parentUri = parent,
                    targetUri = targetUri,
                    displayName = segments.last(),
                    mimeType = file.mimeType,
                ).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onBytesCopied(index, copied)
                    }
                    output.flush()
                }
            } ?: error("無法開啟 ${file.destinationPath}")
            onFileCompleted(file)
        }
    }

    private fun getOrCreateDirectories(
        treeUri: Uri,
        rootUri: Uri,
        segments: List<String>,
        cache: MutableMap<String, Uri>,
    ): Uri {
        var parent = rootUri
        var path = ""
        segments.forEach { name ->
            path = if (path.isBlank()) name else "$path/$name"
            val cached = cache[path]
            if (cached != null) {
                parent = cached
            } else {
                val existing = findChild(treeUri, parent, name)
                val directory = when {
                    existing == null -> DocumentsContract.createDocument(
                        resolver,
                        parent,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        name,
                    ) ?: error("無法建立資料夾 $path")
                    existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> existing.uri
                    else -> error("$path 已存在同名檔案")
                }
                cache[path] = directory
                parent = directory
            }
        }
        return parent
    }

    private fun prepareTargetFile(
        treeUri: Uri,
        parentUri: Uri,
        displayName: String,
        mimeType: String,
    ): Uri {
        val existing = findChild(treeUri, parentUri, displayName)
        if (existing?.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            error("$displayName 已存在同名資料夾")
        }
        return existing?.uri ?: DocumentsContract.createDocument(
            resolver,
            parentUri,
            mimeType,
            displayName,
        ) ?: error("無法建立 $displayName")
    }

    private fun openTruncatedOutput(
        parentUri: Uri,
        targetUri: Uri,
        displayName: String,
        mimeType: String,
    ): BufferedOutputStream {
        val direct = runCatching {
            resolver.openOutputStream(targetUri, "rwt")
        }.getOrNull()
        if (direct != null) return BufferedOutputStream(direct, 128 * 1024)

        check(DocumentsContract.deleteDocument(resolver, targetUri)) {
            "無法覆寫 $displayName"
        }
        val recreated = DocumentsContract.createDocument(
            resolver,
            parentUri,
            mimeType,
            displayName,
        ) ?: error("無法重新建立 $displayName")
        val output = resolver.openOutputStream(recreated, "w")
            ?: error("無法寫入 $displayName")
        return BufferedOutputStream(output, 128 * 1024)
    }

    private fun findChild(treeUri: Uri, parentUri: Uri, displayName: String): Child? {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (name.equals(displayName, ignoreCase = true)) {
                    return@use Child(
                        uri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            cursor.getString(0),
                        ),
                        mimeType = cursor.getString(2),
                    )
                }
            }
            null
        }
    }

    private fun sanitizeName(value: String): String =
        value.replace(Regex("""[\u0000-\u001F\\/:*?"<>|]"""), "_")
            .trim()
            .trimEnd('.', ' ')
            .ifBlank { "_" }

    private data class Child(val uri: Uri, val mimeType: String?)
}
