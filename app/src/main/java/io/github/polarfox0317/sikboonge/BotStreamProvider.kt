package io.github.polarfox0317.sikboonge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Read-only sequential pipe whose bytes contain only the source audio track. */
class BotStreamProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = BotStreamSessions.find(uri).mime()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (selection != null || selectionArgs != null || sortOrder != null) throw SecurityException("Unsupported external audio query")
        val session = BotStreamSessions.find(uri)
        val supported = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val columns = projection?.filter { it in supported }?.distinct()?.toTypedArray() ?: supported
        val cursor = MatrixCursor(columns, 1)
        val row = Array<Any?>(columns.size) { index ->
            when (columns[index]) {
                OpenableColumns.DISPLAY_NAME -> session.displayName()
                OpenableColumns.SIZE -> session.size()?.takeIf { it > 0L }
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("External audio is read-only")
        val session = try { BotStreamSessions.find(uri) } catch (error: SecurityException) { throw FileNotFoundException(error.message) }
        try {
            return session.openPipe()
        } catch (_: Exception) {
            throw FileNotFoundException("외부 오디오를 열 수 없어요.")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")

    companion object {
        internal const val AUTHORITY_SUFFIX = ".botstream"
    }
}
