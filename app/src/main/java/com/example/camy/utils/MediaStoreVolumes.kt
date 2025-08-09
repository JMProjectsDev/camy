package com.example.camy.utils

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.provider.MediaStore

fun getSdCardMediaStoreVolumeName(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    val sd = sm.storageVolumes.firstOrNull { it.isRemovable && !it.isPrimary }
    return sd?.uuid
}

fun getImagesVolumeUriForSd(context: Context) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val vol = getSdCardMediaStoreVolumeName(context) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        MediaStore.Images.Media.getContentUri(vol)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

fun getVideosVolumeUriForSd(context: Context) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val vol = getSdCardMediaStoreVolumeName(context) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        MediaStore.Video.Media.getContentUri(vol)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
