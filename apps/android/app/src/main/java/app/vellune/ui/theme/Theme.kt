package app.vellune.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

// Moonlit indigo on vellum: seed #5B53A8, rose tertiary for warmth.
private val Light = lightColorScheme(
    primary = Color(0xFF5B53A8), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4DFFF), onPrimaryContainer = Color(0xFF170A5E),
    secondary = Color(0xFF5E5C71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E0F9), onSecondaryContainer = Color(0xFF1B192C),
    tertiary = Color(0xFF7D5263), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E5), onTertiaryContainer = Color(0xFF31101F),
    background = Color(0xFFFCF8FF), onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFCF8FF), onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE5E0EC), onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF787680), outlineVariant = Color(0xFFC9C5D0),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F2FA),
    surfaceContainer = Color(0xFFF0ECF4), surfaceContainerHigh = Color(0xFFEAE7EF),
    surfaceContainerHighest = Color(0xFFE5E1E9), surfaceDim = Color(0xFFDCD8E0), surfaceBright = Color(0xFFFCF8FF),
    inverseSurface = Color(0xFF313036), inverseOnSurface = Color(0xFFF3EFF7), inversePrimary = Color(0xFFC6BFFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC6BFFF), onPrimary = Color(0xFF2D2177),
    primaryContainer = Color(0xFF443A8F), onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = Color(0xFFC8C3DC), onSecondary = Color(0xFF302E41),
    secondaryContainer = Color(0xFF474558), onSecondaryContainer = Color(0xFFE4E0F9),
    tertiary = Color(0xFFEEB8CB), onTertiary = Color(0xFF492534),
    tertiaryContainer = Color(0xFF633B4B), onTertiaryContainer = Color(0xFFFFD8E5),
    background = Color(0xFF131318), onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF131318), onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF47464F), onSurfaceVariant = Color(0xFFC9C5D0),
    outline = Color(0xFF928F9A), outlineVariant = Color(0xFF47464F),
    surfaceContainerLowest = Color(0xFF0E0E13), surfaceContainerLow = Color(0xFF1C1B20),
    surfaceContainer = Color(0xFF201F25), surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A), surfaceDim = Color(0xFF131318), surfaceBright = Color(0xFF3A383E),
    inverseSurface = Color(0xFFE5E1E9), inverseOnSurface = Color(0xFF313036), inversePrimary = Color(0xFF5B53A8),
)

// Expressive "increased" corner scale.
private val VelluneShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val base = Typography()

// Emphasized display/headline weights with tighter tracking; Roboto Flex is the system default.
private val VelluneTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Material 3 Expressive: spring motion scheme, increased corners, emphasized type. Wallpaper colors when asked (Android 12+). */
@Composable
fun VelluneTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = VelluneShapes,
        typography = VelluneTypography,
        content = content,
    )
}

/** True when the user turned animations off (Accessibility "Remove animations"). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
