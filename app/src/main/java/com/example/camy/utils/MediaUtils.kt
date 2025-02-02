package com.example.camy.utils
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

fun createMediaContentValues(type: String, storageChoice: String): ContentValues {
    val dateSys = System.currentTimeMillis()
    val actualDate = getCurrentDate()
    val displayName = if (type == "image") {
        "photo_${dateSys}_${actualDate}.jpg"
    } else {
        "video_${dateSys}_${actualDate}.mp4"
    }
    val relativePath = if (type == "image") {
        if (storageChoice == "external") "Pictures/Camy-Pictures-Ext" else "Pictures/Camy-Pictures"
    } else {
        if (storageChoice == "external") "Movies/Camy-Videos-Ext" else "Movies/Camy-Videos"
    }

    return ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(
            android.provider.MediaStore.MediaColumns.MIME_TYPE,
            if (type == "image") "image/jpeg" else "video/mp4"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
    }
}

fun copyStream(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(16 * 1024) // 16 KB
    var totalBytes = 0
    var bytesRead: Int
    while (input.read(buffer).also { bytesRead = it } != -1) {
        output.write(buffer, 0, bytesRead)
        totalBytes += bytesRead
    }
    output.flush()
    Log.d("copyStream", "Total bytes copiados: $totalBytes")
}


fun getRemovableSDTreeUri(context: Context): Uri? {
    val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    val volumes = sm.storageVolumes
    for (volume in volumes) {
        if (volume.isRemovable && !volume.isPrimary) {
            val uuid = volume.uuid
            if (!uuid.isNullOrEmpty()) {
                return Uri.parse("content://com.android.externalstorage.documents/tree/${uuid}%3A")
            }
        }
    }
    return null
}

fun getOrCreateDefaultFolder(context: Context, treeUri: Uri, folderName: String): DocumentFile? {
    val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    var folder = rootDoc.findFile(folderName)
    if (folder == null || !folder.isDirectory) {
        folder = rootDoc.createDirectory(folderName)
    }
    return folder
}




