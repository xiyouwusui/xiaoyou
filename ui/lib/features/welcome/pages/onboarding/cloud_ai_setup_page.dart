import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/welcome/state/onboarding_state.dart';
import 'package:ui/features/welcome/widgets/cloud_config_form.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';

class CloudAiSetupPage extends ConsumerWidget {
  const CloudAiSetupPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final palette = context.omniPalette;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: context.trLegacy('云 AI 服务配置'),
        primary: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Brief description
            Text(
              context.trLegacy('配置云端 AI 服务商，使用更强大的模型能力'),
              style: TextStyle(
                fontSize: 14,
                color: palette.textSecondary,
                height: 1.5,
              ),
            ),
            const SizedBox(height: 24),
            // Config form
            CloudConfigForm(
              onSaved: (result) {
                ref
                    .read(onboardingStateProvider)
                    .markCloudConfigured(result.profileId);
                // Go to permissions page, then home
                GoRouterManager.go('/welcome/permissions');
              },
            ),
          ],
        ),
      ),
    );
  }
}
