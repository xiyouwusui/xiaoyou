import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/theme/theme_context.dart';

/// A tappable card used on the onboarding choice page.
///
/// Displays an icon, title, subtitle, and an optional completion badge.
class OnboardingChoiceCard extends StatelessWidget {
  final String svgIcon;
  final String title;
  final String subtitle;
  final bool completed;
  final VoidCallback onTap;

  const OnboardingChoiceCard({
    super.key,
    required this.svgIcon,
    required this.title,
    required this.subtitle,
    required this.onTap,
    this.completed = false,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: palette.surfacePrimary,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: completed ? palette.accentPrimary : palette.borderSubtle,
            width: completed ? 1.5 : 1,
          ),
          boxShadow: [
            BoxShadow(
              color: palette.shadowColor,
              blurRadius: 8,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        child: Row(
          children: [
            // Icon
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: palette.surfaceSecondary,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Center(
                child: SvgPicture.string(
                  svgIcon,
                  width: 24,
                  height: 24,
                  colorFilter: ColorFilter.mode(
                    palette.accentPrimary,
                    BlendMode.srcIn,
                  ),
                ),
              ),
            ),
            const SizedBox(width: 16),
            // Text
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: palette.textPrimary,
                      height: 1.4,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    subtitle,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w400,
                      color: palette.textSecondary,
                      height: 1.4,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            // Completion badge or chevron
            if (completed)
              Container(
                width: 24,
                height: 24,
                decoration: BoxDecoration(
                  color: palette.accentPrimary,
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.check, size: 16, color: Colors.white),
              )
            else
              Icon(
                Icons.chevron_right,
                size: 20,
                color: palette.textTertiary,
              ),
          ],
        ),
      ),
    );
  }
}
