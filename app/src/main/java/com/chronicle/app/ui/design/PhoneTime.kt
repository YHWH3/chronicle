package com.chronicle.app.ui.design

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Re-read the phone's preference when the screen recomposes after Settings. */
@Composable
internal fun phoneTime(at: LocalDateTime): String =
    at.format(DateTimeFormatter.ofPattern(
        if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a",
        Locale.getDefault(),
    ))
