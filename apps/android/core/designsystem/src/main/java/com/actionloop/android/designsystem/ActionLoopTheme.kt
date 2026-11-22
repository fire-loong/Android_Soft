package com.actionloop.android.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 三端统一主色 #1677FF（科技蓝），暗色保留同色系。 */
private val ActionLoopBlue = Color(0xFF1677FF)

private val LightColors = lightColorScheme(primary = ActionLoopBlue)
private val DarkColors = darkColorScheme(primary = ActionLoopBlue)

@Composable
fun ActionLoopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
