package tw.chehu.quicksend.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import tw.chehu.quicksend.model.BackupSummary
import tw.chehu.quicksend.model.LocalSendDevice
import tw.chehu.quicksend.model.TransferFile

class BackupHistoryRepository(context: Context) :
    SQLiteOpenHelper(context, "backup_history.db", null, 3) {

    override fun onCreate(database: SQLiteDatabase) {
        createBackupRecords(database)
        createFailureRecords(database)
        createSummaries(database)
        createCheckpoints(database)
    }

    private fun createBackupRecords(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS backup_records (
                device_key TEXT NOT NULL,
                target_folder TEXT NOT NULL,
                destination_path TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                PRIMARY KEY (device_key, target_folder, destination_path)
            )
            """.trimIndent()
        )
    }

    private fun createFailureRecords(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS failed_records (
                device_key TEXT NOT NULL,
                target_folder TEXT NOT NULL,
                destination_path TEXT NOT NULL,
                content_uri TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                mime_type TEXT NOT NULL,
                PRIMARY KEY (device_key, target_folder, destination_path)
            )
            """.trimIndent()
        )
    }

    private fun createSummaries(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS backup_summaries (
                device_key TEXT NOT NULL,
                target_folder TEXT NOT NULL,
                finished_at INTEGER NOT NULL,
                successful_files INTEGER NOT NULL,
                failed_files INTEGER NOT NULL,
                successful_bytes INTEGER NOT NULL,
                status TEXT NOT NULL,
                PRIMARY KEY (device_key, target_folder)
            )
            """.trimIndent()
        )
    }

    private fun createCheckpoints(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS backup_checkpoints (
                device_key TEXT NOT NULL,
                target_folder TEXT NOT NULL,
                completed_at INTEGER NOT NULL DEFAULT 0,
                pending_at INTEGER,
                PRIMARY KEY (device_key, target_folder)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createFailureRecords(database)
            createSummaries(database)
        }
        if (oldVersion < 3) {
            createCheckpoints(database)
        }
    }

    fun filterPending(
        device: LocalSendDevice,
        targetFolder: String,
        files: List<TransferFile>,
    ): Pair<List<TransferFile>, Int> {
        if (files.isEmpty()) return files to 0
        val known = mutableMapOf<String, Signature>()
        readableDatabase.query(
            "backup_records",
            arrayOf("destination_path", "file_size", "modified_at"),
            "device_key = ? AND target_folder = ?",
            arrayOf(device.historyKey(), targetFolder),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                known[cursor.getString(0)] = Signature(cursor.getLong(1), cursor.getLong(2))
            }
        }
        val pending = files.filter { file ->
            val previous = known[file.destinationPath]
            previous == null ||
                previous.size != file.size ||
                previous.modifiedAt != file.lastModified
        }
        return pending to (files.size - pending.size)
    }

    fun recordSuccessful(
        device: LocalSendDevice,
        targetFolder: String,
        file: TransferFile,
    ) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            insertRecord(database, device, targetFolder, file)
            deleteFailure(database, device, targetFolder, file.destinationPath)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun recordSuccessfulBatch(
        device: LocalSendDevice,
        targetFolder: String,
        files: List<TransferFile>,
    ) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            files.forEach { insertRecord(database, device, targetFolder, it) }
            database.delete(
                "failed_records",
                "device_key = ? AND target_folder = ?",
                arrayOf(device.historyKey(), targetFolder),
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun insertRecord(
        database: SQLiteDatabase,
        device: LocalSendDevice,
        targetFolder: String,
        file: TransferFile,
    ) {
        val values = ContentValues().apply {
            put("device_key", device.historyKey())
            put("target_folder", targetFolder)
            put("destination_path", file.destinationPath)
            put("file_size", file.size)
            put("modified_at", file.lastModified)
        }
        database.insertWithOnConflict(
            "backup_records",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clear(device: LocalSendDevice, targetFolder: String): Int {
        val database = writableDatabase
        val args = arrayOf(device.historyKey(), targetFolder)
        database.beginTransaction()
        return try {
            val count = database.delete(
                "backup_records",
                "device_key = ? AND target_folder = ?",
                args,
            )
            database.delete("failed_records", "device_key = ? AND target_folder = ?", args)
            database.delete("backup_summaries", "device_key = ? AND target_folder = ?", args)
            database.delete("backup_checkpoints", "device_key = ? AND target_folder = ?", args)
            database.setTransactionSuccessful()
            count
        } finally {
            database.endTransaction()
        }
    }

    fun replaceFailures(
        device: LocalSendDevice,
        targetFolder: String,
        files: List<TransferFile>,
    ) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete(
                "failed_records",
                "device_key = ? AND target_folder = ?",
                arrayOf(device.historyKey(), targetFolder),
            )
            files.forEach { insertFailure(database, device, targetFolder, it) }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun loadFailures(device: LocalSendDevice, targetFolder: String): List<TransferFile> {
        val result = mutableListOf<TransferFile>()
        readableDatabase.query(
            "failed_records",
            arrayOf("content_uri", "destination_path", "file_size", "mime_type", "modified_at"),
            "device_key = ? AND target_folder = ?",
            arrayOf(device.historyKey(), targetFolder),
            null,
            null,
            "destination_path",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TransferFile(
                    uri = Uri.parse(cursor.getString(0)),
                    destinationPath = cursor.getString(1),
                    size = cursor.getLong(2),
                    mimeType = cursor.getString(3),
                    lastModified = cursor.getLong(4),
                )
            }
        }
        return result
    }

    fun failedCount(device: LocalSendDevice, targetFolder: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM failed_records WHERE device_key = ? AND target_folder = ?",
            arrayOf(device.historyKey(), targetFolder),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun hasBackupRecords(device: LocalSendDevice, targetFolder: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM backup_records WHERE device_key = ? AND target_folder = ? LIMIT 1",
            arrayOf(device.historyKey(), targetFolder),
        ).use { cursor -> cursor.moveToFirst() }

    fun loadCheckpoint(device: LocalSendDevice, targetFolder: String): Long? =
        queryCheckpointColumn(device, targetFolder, "completed_at")

    fun loadPendingCheckpoint(device: LocalSendDevice, targetFolder: String): Long? =
        queryCheckpointColumn(device, targetFolder, "pending_at")

    fun beginCheckpoint(
        device: LocalSendDevice,
        targetFolder: String,
        candidate: Long,
    ) {
        val database = writableDatabase
        val args = arrayOf(device.historyKey(), targetFolder)
        val updated = database.update(
            "backup_checkpoints",
            ContentValues().apply { put("pending_at", candidate) },
            "device_key = ? AND target_folder = ?",
            args,
        )
        if (updated == 0) {
            database.insertOrThrow(
                "backup_checkpoints",
                null,
                ContentValues().apply {
                    put("device_key", device.historyKey())
                    put("target_folder", targetFolder)
                    put("completed_at", 0L)
                    put("pending_at", candidate)
                },
            )
        }
    }

    fun completeCheckpoint(
        device: LocalSendDevice,
        targetFolder: String,
        candidate: Long,
    ) {
        val database = writableDatabase
        val args = arrayOf(device.historyKey(), targetFolder)
        val values = ContentValues().apply {
            put("completed_at", candidate)
            putNull("pending_at")
        }
        val updated = database.update(
            "backup_checkpoints",
            values,
            "device_key = ? AND target_folder = ?",
            args,
        )
        if (updated == 0) {
            values.put("device_key", device.historyKey())
            values.put("target_folder", targetFolder)
            database.insertOrThrow("backup_checkpoints", null, values)
        }
    }

    fun clearPendingCheckpoint(device: LocalSendDevice, targetFolder: String) {
        writableDatabase.update(
            "backup_checkpoints",
            ContentValues().apply { putNull("pending_at") },
            "device_key = ? AND target_folder = ?",
            arrayOf(device.historyKey(), targetFolder),
        )
    }

    fun saveSummary(
        device: LocalSendDevice,
        targetFolder: String,
        summary: BackupSummary,
    ) {
        val values = ContentValues().apply {
            put("device_key", device.historyKey())
            put("target_folder", targetFolder)
            put("finished_at", summary.finishedAt)
            put("successful_files", summary.successfulFiles)
            put("failed_files", summary.failedFiles)
            put("successful_bytes", summary.successfulBytes)
            put("status", summary.status)
        }
        writableDatabase.insertWithOnConflict(
            "backup_summaries",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun loadSummary(device: LocalSendDevice, targetFolder: String): BackupSummary? =
        readableDatabase.query(
            "backup_summaries",
            arrayOf(
                "finished_at",
                "successful_files",
                "failed_files",
                "successful_bytes",
                "status",
            ),
            "device_key = ? AND target_folder = ?",
            arrayOf(device.historyKey(), targetFolder),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            BackupSummary(
                finishedAt = cursor.getLong(0),
                successfulFiles = cursor.getInt(1),
                failedFiles = cursor.getInt(2),
                successfulBytes = cursor.getLong(3),
                status = cursor.getString(4),
            )
        }

    private fun insertFailure(
        database: SQLiteDatabase,
        device: LocalSendDevice,
        targetFolder: String,
        file: TransferFile,
    ) {
        val values = ContentValues().apply {
            put("device_key", device.historyKey())
            put("target_folder", targetFolder)
            put("destination_path", file.destinationPath)
            put("content_uri", file.uri.toString())
            put("file_size", file.size)
            put("modified_at", file.lastModified)
            put("mime_type", file.mimeType)
        }
        database.insertWithOnConflict(
            "failed_records",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun deleteFailure(
        database: SQLiteDatabase,
        device: LocalSendDevice,
        targetFolder: String,
        destinationPath: String,
    ) {
        database.delete(
            "failed_records",
            "device_key = ? AND target_folder = ? AND destination_path = ?",
            arrayOf(device.historyKey(), targetFolder, destinationPath),
        )
    }

    private data class Signature(val size: Long, val modifiedAt: Long)

    private fun queryCheckpointColumn(
        device: LocalSendDevice,
        targetFolder: String,
        column: String,
    ): Long? = readableDatabase.query(
        "backup_checkpoints",
        arrayOf(column),
        "device_key = ? AND target_folder = ?",
        arrayOf(device.historyKey(), targetFolder),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
        cursor.getLong(0).takeIf { it > 0 }
    }

    private fun LocalSendDevice.historyKey(): String =
        fingerprint.takeIf { it.isNotBlank() } ?: key
}
