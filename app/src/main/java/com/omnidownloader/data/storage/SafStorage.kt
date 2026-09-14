package com.omnidownloader.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.omnidownloader.download.http.FileNameParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.io.File
import javax.inject.Inject

class SafStorage @Inject constructor(@ApplicationContext private val context: Context) {
    data class PendingDocument(val document: DocumentFile, val output: OutputStream, val finalName: String)

    fun createPending(treeUri: String, requestedName: String, mimeType: String, taskId: String): PendingDocument {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: throw SecurityException("Destination folder is unavailable")
        require(root.canWrite()) { "Destination folder is not writable" }
        val safeName = FileNameParser.sanitize(requestedName) ?: "download"
        val pendingName = ".${safeName}.${taskId.take(8)}.part"
        root.findFile(pendingName)?.delete()
        val document = root.createFile(mimeType, pendingName) ?: throw java.io.IOException("Could not create destination file")
        val output = context.contentResolver.openOutputStream(document.uri, "wt") ?: throw java.io.IOException("Could not open destination file")
        return PendingDocument(document, output, uniqueName(root, safeName))
    }

    fun commit(pending: PendingDocument): Uri {
        pending.output.close()
        if (!pending.document.renameTo(pending.finalName)) throw java.io.IOException("Could not finalize destination file")
        return pending.document.uri
    }

    fun abort(pending: PendingDocument) {
        runCatching { pending.output.close() }
        runCatching { pending.document.delete() }
    }

    /** Copies a completed torrent payload into the user-selected SAF tree. */
    fun exportPayload(treeUri: String, payload: File, requestedName: String): Uri {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: throw SecurityException("Destination folder is unavailable")
        require(root.canWrite()) { "Destination folder is not writable" }
        return if (payload.isDirectory) {
            val directory = root.createDirectory(uniqueName(root, requestedName)) ?: throw java.io.IOException("Could not create destination folder")
            payload.listFiles()?.forEach { copyInto(it, directory) }
            directory.uri
        } else {
            val name = uniqueName(root, requestedName)
            val target = root.createFile("application/octet-stream", name) ?: throw java.io.IOException("Could not create destination file")
            val output = context.contentResolver.openOutputStream(target.uri, "wt")
                ?: throw java.io.IOException("Could not open destination file")
            output.use { stream -> payload.inputStream().buffered().use { it.copyTo(stream) } }
            target.uri
        }
    }

    private fun copyInto(source: File, parent: DocumentFile) {
        if (source.isDirectory) {
            val child = parent.createDirectory(source.name) ?: throw java.io.IOException("Could not create ${source.name}")
            source.listFiles()?.forEach { copyInto(it, child) }
        } else {
            val child = parent.createFile("application/octet-stream", source.name) ?: throw java.io.IOException("Could not create ${source.name}")
            val output = context.contentResolver.openOutputStream(child.uri, "wt")
                ?: throw java.io.IOException("Could not open ${source.name}")
            output.use { stream -> source.inputStream().buffered().use { it.copyTo(stream) } }
        }
    }

    private fun uniqueName(root: DocumentFile, name: String): String {
        if (root.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (root.findFile("$base ($index)$ext") != null) index++
        return "$base ($index)$ext"
    }
}
