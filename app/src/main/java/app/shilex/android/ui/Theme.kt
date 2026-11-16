package app.shilex.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Dark = darkColorScheme(
    primary = Color(0xFF568E9D),
    onPrimary = Color(0xFF001519),
    primaryContainer = Color(0xFF12242B),
    onPrimaryContainer = Color(0xFFC4DFE6),
    secondary = Color(0xFF98AEB8),
    tertiary = Color(0xFFBDA181),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE3E9EC),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE3E9EC),
    surfaceVariant = Color(0xFF0A1115),
    onSurfaceVariant = Color(0xFF93A3AB),
    surfaceContainer = Color(0xFF05090B),
    outline = Color(0xFF293A42),
    outlineVariant = Color(0xFF17262D),
    error = Color(0xFFDF8D86),
)

private val Light = lightColorScheme(
    primary = Color(0xFF00708A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F3FA),
    onPrimaryContainer = Color(0xFF00313D),
    secondary = Color(0xFF52627A),
    tertiary = Color(0xFFA35F00),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF111722),
    surface = Color(0xFFF5F7FA),
    onSurface = Color(0xFF111722),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5B677A),
    surfaceContainer = Color(0xFFFFFFFF),
    outline = Color(0xFFD5DCE6),
    outlineVariant = Color(0xFFE4E9F0),
    error = Color(0xFFB3261E),
)

private val Type = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

private val Corners = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

/** KAIRO's always-black, low-glare colour palette. */
@Composable
fun ShilexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Dark,
        typography = Type,
        shapes = Corners,
        content = content,
    )
}

// Short names the screens use; they follow light and dark mode.
val Accent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val Background: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val Surface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Warning: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary
