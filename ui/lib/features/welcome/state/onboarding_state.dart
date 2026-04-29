import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/mnn_local_models_service.dart';

/// Recommended model ID for onboarding local model download.
const String kOnboardingRecommendedModelId = 'Qwen3.5-0.8B-Q4_K_M';

/// Shared state across the onboarding flow.
///
/// Tracks whether the user has completed cloud AI configuration
/// and/or local model download during this onboarding session.
class OnboardingState extends ChangeNotifier {
  bool _cloudConfigured = false;
  bool _localModelReady = false;
  String? _configuredProfileId;
  String? _downloadedModelId;
  bool _initialized = false;

  bool get cloudConfigured => _cloudConfigured;
  bool get localModelReady => _localModelReady;
  String? get configuredProfileId => _configuredProfileId;
  String? get downloadedModelId => _downloadedModelId;
  bool get initialized => _initialized;

  void markCloudConfigured(String profileId) {
    _cloudConfigured = true;
    _configuredProfileId = profileId;
    notifyListeners();
  }

  void markLocalModelReady(String modelId) {
    _localModelReady = true;
    _downloadedModelId = modelId;
    notifyListeners();
  }

  /// Check persisted state to recover from app restart mid-onboarding.
  Future<void> checkExistingState() async {
    try {
      // Check if user already has a cloud provider profile
      final profiles = await ModelProviderConfigService.listProfiles();
      final hasUserProfile = profiles.profiles.any(
        (p) => !p.readOnly && p.apiKey.isNotEmpty,
      );
      if (hasUserProfile) {
        _cloudConfigured = true;
        _configuredProfileId = profiles.editingProfileId;
      }

      // Check if the recommended local model is already installed
      final installed = await MnnLocalModelsService.listInstalledModels();
      final hasRecommended = installed.any(
        (m) => m.id.contains('Qwen3') || m.id.contains('qwen3'),
      );
      if (hasRecommended) {
        _localModelReady = true;
        _downloadedModelId = installed
            .firstWhere(
              (m) => m.id.contains('Qwen3') || m.id.contains('qwen3'),
            )
            .id;
      }
    } catch (e) {
      debugPrint('OnboardingState.checkExistingState error: $e');
    }
    _initialized = true;
    notifyListeners();
  }
}

/// Auto-disposed provider scoped to the onboarding flow lifetime.
final onboardingStateProvider =
    ChangeNotifierProvider.autoDispose<OnboardingState>(
  (ref) => OnboardingState(),
);
