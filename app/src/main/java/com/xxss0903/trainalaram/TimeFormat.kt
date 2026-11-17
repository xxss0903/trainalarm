package com.xxss0903.trainalaram

import java.util.Locale

/** 已耗时格式化：向下取整，如 00:35 */
fun formatDuration(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000L
    return String.format(Locale.US, "%02d:%02d", totalSec / 60L, totalSec % 60L)
}

/** 倒计时格式化：向上取整，剩余 0.4 秒时显示 00:01，避免提前显示 00:00 */
fun formatCountdown(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) + 999L) / 1000L
    return String.format(Locale.US, "%02d:%02d", totalSec / 60L, totalSec % 60L)
}
