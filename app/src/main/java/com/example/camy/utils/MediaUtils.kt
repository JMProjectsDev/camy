package com.example.camy.utils
import android.content.ContentValues
import android.os.Build

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
