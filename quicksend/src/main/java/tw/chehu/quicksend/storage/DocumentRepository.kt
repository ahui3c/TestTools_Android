package tw.chehu.quicksend.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import tw.chehu.quicksend.model.BackupFilter
import tw.chehu.quicksend.model.DocumentNode
import tw.chehu.quicksend.model.GrantedRoot
import tw.chehu.quicksend.model.RootKind
import tw.chehu.quicksend.model.ScanResult
import tw.chehu.quicksend.model.SourceSelection
import tw.chehu.quicksend.model.TransferFile
import tw.chehu.quicksend.model.UsbDestination
import java.util.ArrayDeque

class DocumentRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val preferences = context.getSharedPreferences("folder_access", Context.MODE_PRIVATE)

    fun restoreRoot(kind: RootKind): GrantedRoot? {
        val raw = preferences.getString(kind.name, null) ?: return null
        val uri = Uri.parse(raw)
        val hasPermission = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!hasPermission) return null
        return GrantedRoot(
            kind = kind,
            uri = uri,
            displayName = runCatching { queryDisplayName(uri) }.getOrNull() ?: kind.title,
        )
    }

    fun saveRoot(kind: RootKind, uri: Uri): GrantedRoot {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        preferences.edit().putString(kind.name, uri.toString()).apply()
        return GrantedRoot(
            kind = kind,
            uri = uri,
            displayName = runCatching { queryDisplayName(uri) }.getOrNull() ?: kind.title,
        )
    }

    fun restoreUsbDestination(): UsbDestination? {
        val raw = preferences.getString(USB_DESTINATION_KEY, null) ?: return null
        val uri = Uri.parse(raw)
        val hasPermission = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!hasPermission) return null
        return UsbDestination(
            uri = uri,
            displayName = runCatching { queryDisplayName(uri) }.getOrNull()
                ?: "USB 外接磁碟",
        )
    }

    fun saveUsbDestination(uri: Uri): UsbDestination {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        preferences.edit().putString(USB_DESTINATION_KEY, uri.toString()).apply()
        return UsbDestination(
            uri = uri,
            displayName = runCatching { queryDisplayName(uri) }.getOrNull()
                ?: "USB 外接磁碟",
        )
    }

    fun listChildren(rootUri: Uri, parentUri: Uri, parentRelativePath: String): List<DocumentNode> {
        val parentDocumentId = if (parentUri == rootUri) {
            DocumentsContract.getTreeDocumentId(rootUri)
        } else {
            DocumentsContract.getDocumentId(parentUri)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = mutableListOf<DocumentNode>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex) ?: "(未命名)"
                val mime = cursor.getString(mimeIndex)
                val relativePath = listOf(parentRelativePath, name)
                    .filter { it.isNotBlank() }
                    .joinToString("/")
                result += DocumentNode(
                    uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, id),
                    name = name,
                    relativePath = relativePath,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex),
                    lastModified = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
                    mimeType = mime,
                )
            }
        }
        return result.sortedWith(compareByDescending<DocumentNode> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun scan(
        roots: Map<RootKind, GrantedRoot>,
        includeAll: Map<RootKind, Boolean>,
        selections: List<SourceSelection>,
        targetFolder: String,
        filter: BackupFilter,
        onVisited: (Int) -> Unit,
    ): ScanResult {
        val output = mutableListOf<TransferFile>()
        val errors = mutableListOf<String>()
        var skippedBySize = 0
        var skippedByDate = 0
        var visited = 0

        roots.forEach { (kind, root) ->
            val startingNodes = if (includeAll[kind] != false) {
                listOf(
                    DocumentNode(
                        uri = root.uri,
                        name = root.displayName,
                        relativePath = "",
                        isDirectory = true,
                        size = 0,
                        lastModified = 0,
                        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                )
            } else {
                selections.filter { it.kind == kind }.map {
                    DocumentNode(it.uri, it.name, it.relativePath, it.isDirectory, 0, 0, null)
                }
            }

            val queue = ArrayDeque<DocumentNode>()
            val seenUris = mutableSetOf<String>()
            startingNodes.forEach(queue::addLast)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (!seenUris.add(node.uri.toString())) continue
                try {
                    if (node.isDirectory) {
                        listChildren(root.uri, node.uri, node.relativePath).forEach(queue::addLast)
                    } else {
                        visited++
                        onVisited(visited)
                        val metadata = if (node.size == 0L || node.lastModified == 0L || node.mimeType == null) {
                            queryNode(root.uri, node.uri, node.relativePath) ?: node
                        } else node
                        if (filter.maxBytes != null && metadata.size > filter.maxBytes) {
                            skippedBySize++
                            continue
                        }
                        if (filter.newerThanMillis != null &&
                            metadata.lastModified > 0 &&
                            metadata.lastModified < filter.newerThanMillis
                        ) {
                            skippedByDate++
                            continue
                        }
                        val relative = metadata.relativePath.trimStart('/')
                        val destination = targetFolder.takeIf { it.isNotBlank() }
                            ?.let { "${sanitizePathSegment(it)}/${kind.wireName}/$relative" }
                            ?: "${kind.wireName}/$relative"
                        output += TransferFile(
                            uri = metadata.uri,
                            destinationPath = destination,
                            size = metadata.size,
                            mimeType = metadata.mimeType?.takeUnless {
                                it == DocumentsContract.Document.MIME_TYPE_DIR
                            } ?: resolver.getType(metadata.uri) ?: "application/octet-stream",
                            lastModified = metadata.lastModified,
                        )
                    }
                } catch (error: Exception) {
                    errors += "${kind.title}/${node.relativePath}: ${error.message ?: "無法讀取"}"
                }
            }
        }
        return ScanResult(
            files = output,
            skippedBySize = skippedBySize,
            skippedByDate = skippedByDate,
            errors = errors,
        )
    }

    private fun queryNode(rootUri: Uri, uri: Uri, relativePath: String): DocumentNode? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0) ?: "(未命名)"
            val mime = cursor.getString(1)
            DocumentNode(
                uri = uri,
                name = name,
                relativePath = relativePath.ifBlank { name },
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                size = resolveSize(uri, if (cursor.isNull(2)) 0L else cursor.getLong(2)),
                lastModified = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                mimeType = mime,
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        // ACTION_OPEN_DOCUMENT_TREE returns .../tree/<id>. Some OEM document
        // providers reject queries against that URI and only accept the equivalent
        // .../tree/<id>/document/<id> URI.
        val queryUri = if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri),
            )
        } else {
            uri
        }
        return resolver.query(queryUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun resolveSize(uri: Uri, reportedSize: Long): Long {
        if (reportedSize > 0) return reportedSize
        val assetSize = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (assetSize != null && assetSize >= 0) return assetSize
        val descriptorSize = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()
        if (descriptorSize != null && descriptorSize >= 0) return descriptorSize
        return resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(128 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        } ?: 0L
    }

    private fun sanitizePathSegment(value: String): String {
        return value.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "Android" }
    }

    companion object {
        private const val USB_DESTINATION_KEY = "USB_DESTINATION"
    }
}
