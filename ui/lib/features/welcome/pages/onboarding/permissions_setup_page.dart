import 'package:flutter/material.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/welcome/pages/welcome_page/widgets/fourth_welcome_page.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/gradient_button.dart';

class PermissionsSetupPage extends StatelessWidget {
  const PermissionsSetupPage({super.key});

  Future<void> _handleStartExperience() async {
    try {
      await spePermission.invokeMethod('isInstalledAppsPermissionGranted');
    } catch (e) {
      debugPrint('Request installed apps permission failed: $e');
    }
    await StorageService.setBool(StorageKeys.welcomeCompleted, true);
    GoRouterManager.clearAndNavigateTo('/home/chat');
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final screenWidth = MediaQuery.of(context).size.width;
    final screenHeight = MediaQuery.of(context).size.height;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: FourthWelcomePage(
                screenWidth: screenWidth,
                screenHeight: screenHeight,
              ),
            ),
            // Bottom button
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
              child: GradientButton(
                width: screenWidth - 48,
                height: 48,
                text: context.trLegacy('开始体验'),
                onTap: _handleStartExperience,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
