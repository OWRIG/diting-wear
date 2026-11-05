package io.github.owrig.diting

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 安静的靛蓝 + 青绿（人物）+ 暖橙（地点）
private val Light = lightColorScheme(
    primary = Color(0xFF3D5AA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF0E2560),
    secondary = Color(0xFF5B6275),
    secondaryContainer = Color(0xFFE2E5F1),
    onSecondaryContainer = Color(0xFF181C29),
    tertiary = Color(0xFF2D7A69),
    tertiaryContainer = Color(0xFFD3EFE6),
    onTertiaryContainer = Color(0xFF0B3A30),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFE3E5EE),
    onSurfaceVariant = Color(0xFF5E6170),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEFF1F7),
    surfaceContainerHigh = Color(0xFFE9EBF2),
    surfaceContainerHighest = Color(0xFFE3E5EE),
    outline = Color(0xFF8E91A0),
    outlineVariant = Color(0xFFDADCE5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4C4FF),
    onPrimary = Color(0xFF0E2560),
    primaryContainer = Color(0xFF26407F),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFFC2C6D9),
    secondaryContainer = Color(0xFF3A3F50),
    onSecondaryContainer = Color(0xFFE2E5F1),
    tertiary = Color(0xFF8FD5C2),
    tertiaryContainer = Color(0xFF14513F),
    onTertiaryContainer = Color(0xFFD3EFE6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E4EA),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E4EA),
    surfaceVariant = Color(0xFF2B2E37),
    onSurfaceVariant = Color(0xFFA9ACBA),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF1A1C22),
    surfaceContainer = Color(0xFF1E2027),
    surfaceContainerHigh = Color(0xFF282A31),
    surfaceContainerHighest = Color(0xFF33353C),
    outline = Color(0xFF8B8E9B),
    outlineVariant = Color(0xFF3A3D47),
)

val PlaceColor = Color(0xFFB8692A)

@Composable
fun DitingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
