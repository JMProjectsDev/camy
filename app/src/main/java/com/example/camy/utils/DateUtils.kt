package com.example.camy.utils

import java.text.SimpleDateFormat
import java.util.*

private const val DATE_FORMAT = "dd_MM_yyyy"

fun getCurrentDate(): String {
    val currentDate = Date()
    val formatter = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
    return formatter.format(currentDate)
}