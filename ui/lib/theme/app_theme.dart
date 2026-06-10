import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/services/app_font_effect_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/omni_theme_palette.dart';

class AppTheme {
  AppTheme._();

  static ThemeData get lightTheme => _lightTheme;
  static ThemeData get darkTheme => _darkTheme;

  static ThemeData lightThemeFor({required bool enhancedFonts}) {
    if (!enhancedFonts) {
      return _lightTheme;
    }
    return _buildTheme(
      brightness: Brightness.light,
      palette: OmniThemePalette.light,
      enhancedFonts: true,
    );
  }

  static ThemeData darkThemeFor({required bool enhancedFonts}) {
    if (!enhancedFonts) {
      return _darkTheme;
    }
    return _buildTheme(
      brightness: Brightness.dark,
      palette: OmniThemePalette.dark,
      enhancedFonts: true,
    );
  }

  static final ThemeData _lightTheme = _buildTheme(
    brightness: Brightness.light,
    palette: OmniThemePalette.light,
  );

  static final ThemeData _darkTheme = _buildTheme(
    brightness: Brightness.dark,
    palette: OmniThemePalette.dark,
  );

  static ThemeData _buildTheme({
    required Brightness brightness,
    required OmniThemePalette palette,
    bool enhancedFonts = false,
  }) {
    final isDark = brightness == Brightness.dark;
    final onAccentColor = _foregroundForAccent(palette.accentPrimary);
    final fontFamily = AppFontEffectService.fontFamilyFor(
      enhancedFonts: enhancedFonts,
    );
    final fontFamilyFallback = AppFontEffectService.fontFallbackFor(
      enhancedFonts: enhancedFonts,
    );
    final colorScheme =
        ColorScheme.fromSeed(
          seedColor: palette.accentPrimary,
          brightness: brightness,
        ).copyWith(
          primary: palette.accentPrimary,
          onPrimary: onAccentColor,
          secondary: isDark
              ? _secondaryAccentForDark(palette)
              : AppColors.gradientAux,
          onSecondary: isDark ? onAccentColor : palette.textPrimary,
          error: AppColors.alertRed,
          onError: Colors.white,
          surface: palette.surfacePrimary,
          onSurface: palette.textPrimary,
          outline: palette.borderStrong,
          outlineVariant: palette.borderSubtle,
          surfaceTint: Colors.transparent,
        );

    final baseTheme = ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: palette.pageBackground,
      dividerColor: palette.borderSubtle,
      shadowColor: palette.shadowColor,
      splashColor: palette.accentPrimary.withValues(alpha: isDark ? 0.1 : 0.08),
      highlightColor: Colors.transparent,
      fontFamily: fontFamily,
      fontFamilyFallback: fontFamilyFallback,
      extensions: <ThemeExtension<dynamic>>[palette],
      appBarTheme: AppBarTheme(
        backgroundColor: palette.pageBackground,
        foregroundColor: palette.textPrimary,
        elevation: 0,
        scrolledUnderElevation: 0,
        shadowColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        centerTitle: true,
        systemOverlayStyle: overlayStyleForBrightness(brightness),
        titleTextStyle: TextStyle(
          fontSize: 17,
          fontWeight: FontWeight.w600,
          color: palette.textPrimary,
          fontFamily: fontFamily,
          fontFamilyFallback: fontFamilyFallback,
        ),
        iconTheme: IconThemeData(color: palette.textPrimary, size: 24),
      ),
      drawerTheme: DrawerThemeData(
        backgroundColor: palette.pageBackground,
        surfaceTintColor: Colors.transparent,
      ),
      dividerTheme: DividerThemeData(
        color: palette.borderSubtle,
        thickness: 1,
        space: 1,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: palette.surfacePrimary,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 14,
          vertical: 14,
        ),
        hintStyle: TextStyle(color: palette.textTertiary, fontSize: 14),
        labelStyle: TextStyle(color: palette.textSecondary, fontSize: 14),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: palette.borderSubtle),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: palette.borderSubtle),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: palette.accentPrimary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.alertRed),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.alertRed, width: 1.5),
        ),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: palette.surfaceElevated,
        selectedColor: palette.segmentThumb,
        disabledColor: palette.surfaceSecondary,
        labelStyle: TextStyle(
          color: palette.textSecondary,
          fontSize: 13,
          fontWeight: FontWeight.w600,
        ),
        secondaryLabelStyle: TextStyle(
          color: palette.textPrimary,
          fontSize: 13,
          fontWeight: FontWeight.w600,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        shape: StadiumBorder(side: BorderSide(color: palette.borderSubtle)),
        side: BorderSide(color: palette.borderSubtle),
      ),
      sliderTheme: SliderThemeData(
        activeTrackColor: palette.accentPrimary,
        inactiveTrackColor: palette.borderSubtle,
        thumbColor: palette.accentPrimary,
        overlayColor: palette.accentPrimary.withValues(alpha: 0.14),
        trackHeight: 3,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return Colors.white;
          }
          return palette.surfacePrimary;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return palette.accentPrimary;
          }
          return palette.borderStrong;
        }),
        trackOutlineColor: WidgetStateProperty.all(Colors.transparent),
      ),
      listTileTheme: ListTileThemeData(
        textColor: palette.textPrimary,
        iconColor: palette.textSecondary,
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: palette.surfacePrimary,
        surfaceTintColor: Colors.transparent,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: palette.surfacePrimary,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      ),
    );

    return baseTheme.copyWith(
      textTheme: baseTheme.textTheme.apply(
        bodyColor: palette.textPrimary,
        displayColor: palette.textPrimary,
        fontFamily: fontFamily,
        fontFamilyFallback: fontFamilyFallback,
      ),
    );
  }

  static SystemUiOverlayStyle overlayStyleForBrightness(Brightness brightness) {
    final lightIcons = brightness == Brightness.dark;
    return SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: lightIcons ? Brightness.light : Brightness.dark,
      statusBarBrightness: lightIcons ? Brightness.dark : Brightness.light,
      systemNavigationBarColor: Colors.transparent,
      systemNavigationBarDividerColor: Colors.transparent,
      systemNavigationBarContrastEnforced: false,
      systemNavigationBarIconBrightness: lightIcons
          ? Brightness.light
          : Brightness.dark,
    );
  }

  static Color _foregroundForAccent(Color accentColor) {
    return accentColor.computeLuminance() > 0.35
        ? const Color(0xFF141716)
        : Colors.white;
  }

  static Color _secondaryAccentForDark(OmniThemePalette palette) {
    final hsl = HSLColor.fromColor(palette.accentPrimary);
    return hsl
        .withSaturation((hsl.saturation * 0.72).clamp(0.0, 1.0))
        .withLightness((hsl.lightness + 0.08).clamp(0.0, 1.0))
        .toColor();
  }
}
