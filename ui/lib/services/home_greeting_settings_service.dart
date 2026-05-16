import 'dart:convert';

import 'package:flutter/widgets.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/storage_service.dart';

const String kHomeGreetingSettingsStorageKey = 'home_greeting_settings';

@immutable
class HomeQuickPrompt {
  final String id;
  final String title;
  final String prompt;
  final String? titleEn;
  final String? promptEn;
  final String iconKey;
  final bool builtIn;

  const HomeQuickPrompt({
    required this.id,
    required this.title,
    required this.prompt,
    this.titleEn,
    this.promptEn,
    required this.iconKey,
    required this.builtIn,
  });

  factory HomeQuickPrompt.fromJson(Map<String, dynamic> json) {
    return HomeQuickPrompt(
      id: (json['id'] ?? '').toString(),
      title: (json['title'] ?? '').toString(),
      prompt: (json['prompt'] ?? '').toString(),
      titleEn: json['titleEn']?.toString(),
      promptEn: json['promptEn']?.toString(),
      iconKey: (json['iconKey'] ?? 'spark').toString(),
      builtIn: json['builtIn'] == true,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'prompt': prompt,
      if (titleEn != null) 'titleEn': titleEn,
      if (promptEn != null) 'promptEn': promptEn,
      'iconKey': iconKey,
      'builtIn': builtIn,
    };
  }

  HomeQuickPrompt copyWith({
    String? id,
    String? title,
    String? prompt,
    String? titleEn,
    String? promptEn,
    String? iconKey,
    bool? builtIn,
  }) {
    return HomeQuickPrompt(
      id: id ?? this.id,
      title: title ?? this.title,
      prompt: prompt ?? this.prompt,
      titleEn: titleEn ?? this.titleEn,
      promptEn: promptEn ?? this.promptEn,
      iconKey: iconKey ?? this.iconKey,
      builtIn: builtIn ?? this.builtIn,
    );
  }

  String resolveTitle(BuildContext context) {
    final languageCode = Localizations.localeOf(context).languageCode;
    if (languageCode == 'en' && titleEn?.trim().isNotEmpty == true) {
      return titleEn!.trim();
    }
    return LegacyTextLocalizer.localize(
      title.trim(),
      locale: Locale(languageCode),
    );
  }

  String resolvePrompt(BuildContext context) {
    final languageCode = Localizations.localeOf(context).languageCode;
    if (languageCode == 'en' && promptEn?.trim().isNotEmpty == true) {
      return promptEn!.trim();
    }
    return LegacyTextLocalizer.localize(
      prompt.trim(),
      locale: Locale(languageCode),
    );
  }
}

@immutable
class HomeGreetingSettings {
  final bool greetingEnabled;
  final List<HomeQuickPrompt> quickPrompts;

  const HomeGreetingSettings({
    required this.greetingEnabled,
    required this.quickPrompts,
  });

  static HomeGreetingSettings get defaults => const HomeGreetingSettings(
    greetingEnabled: true,
    quickPrompts: HomeGreetingSettingsService.defaultQuickPrompts,
  );

  List<HomeQuickPrompt> get visibleQuickPrompts =>
      quickPrompts.take(2).toList(growable: false);

  factory HomeGreetingSettings.fromJson(Map<String, dynamic> json) {
    final rawPrompts = json['quickPrompts'];
    final prompts = rawPrompts is List
        ? rawPrompts
              .whereType<Map>()
              .map(
                (item) =>
                    HomeQuickPrompt.fromJson(Map<String, dynamic>.from(item)),
              )
              .where((item) => item.id.trim().isNotEmpty)
              .toList(growable: false)
        : HomeGreetingSettingsService.defaultQuickPrompts;
    return HomeGreetingSettings(
      greetingEnabled: json['greetingEnabled'] != false,
      quickPrompts: prompts,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'greetingEnabled': greetingEnabled,
      'quickPrompts': quickPrompts.map((item) => item.toJson()).toList(),
    };
  }

  HomeGreetingSettings copyWith({
    bool? greetingEnabled,
    List<HomeQuickPrompt>? quickPrompts,
  }) {
    return HomeGreetingSettings(
      greetingEnabled: greetingEnabled ?? this.greetingEnabled,
      quickPrompts: quickPrompts ?? this.quickPrompts,
    );
  }
}

class HomeGreetingSettingsService {
  static const List<HomeQuickPrompt> defaultQuickPrompts = <HomeQuickPrompt>[
    HomeQuickPrompt(
      id: 'builtin_summarize',
      title: '总结一下',
      titleEn: 'Summarize',
      prompt: '请帮我总结一下当前内容，并列出关键要点。',
      promptEn: 'Please summarize this and list the key points.',
      iconKey: 'summarize',
      builtIn: true,
    ),
    HomeQuickPrompt(
      id: 'builtin_plan',
      title: '帮我规划',
      titleEn: 'Plan',
      prompt: '请帮我把这个目标拆成清晰、可执行的步骤。',
      promptEn: 'Please break this goal into clear, actionable steps.',
      iconKey: 'plan',
      builtIn: true,
    ),
    HomeQuickPrompt(
      id: 'builtin_execute',
      title: '执行任务',
      titleEn: 'Execute',
      prompt: '请帮我执行这个任务：',
      promptEn: 'Please help me execute this task:',
      iconKey: 'execute',
      builtIn: true,
    ),
    HomeQuickPrompt(
      id: 'builtin_explore',
      title: '探索想法',
      titleEn: 'Explore',
      prompt: '我想探索一个想法，请先帮我梳理可能方向：',
      promptEn:
          'I want to explore an idea. Please help map possible directions first:',
      iconKey: 'explore',
      builtIn: true,
    ),
  ];

  static final ValueNotifier<HomeGreetingSettings> notifier =
      ValueNotifier<HomeGreetingSettings>(HomeGreetingSettings.defaults);

  static bool _loaded = false;

  static Future<void> load() async {
    if (_loaded) {
      return;
    }
    _loaded = true;
    final raw = StorageService.getString(kHomeGreetingSettingsStorageKey);
    if (raw == null || raw.trim().isEmpty) {
      notifier.value = HomeGreetingSettings.defaults;
      return;
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) {
        notifier.value = HomeGreetingSettings.fromJson(decoded);
      } else if (decoded is Map) {
        notifier.value = HomeGreetingSettings.fromJson(
          Map<String, dynamic>.from(decoded),
        );
      }
    } catch (error) {
      debugPrint('Load home greeting settings failed: $error');
      notifier.value = HomeGreetingSettings.defaults;
    }
  }

  static Future<bool> setGreetingEnabled(bool enabled) {
    return _save(notifier.value.copyWith(greetingEnabled: enabled));
  }

  static Future<bool> addQuickPrompt({
    required String title,
    required String prompt,
  }) {
    final normalizedTitle = title.trim();
    final normalizedPrompt = prompt.trim();
    if (normalizedTitle.isEmpty || normalizedPrompt.isEmpty) {
      return Future.value(false);
    }
    final nextPrompt = HomeQuickPrompt(
      id: 'custom_${DateTime.now().microsecondsSinceEpoch}',
      title: normalizedTitle,
      prompt: normalizedPrompt,
      iconKey: 'spark',
      builtIn: false,
    );
    return _save(
      notifier.value.copyWith(
        quickPrompts: [nextPrompt, ...notifier.value.quickPrompts],
      ),
    );
  }

  static Future<bool> updateQuickPrompt(HomeQuickPrompt prompt) {
    final nextPrompt = prompt.copyWith(
      title: prompt.title.trim(),
      prompt: prompt.prompt.trim(),
    );
    if (nextPrompt.title.isEmpty || nextPrompt.prompt.isEmpty) {
      return Future.value(false);
    }
    final nextPrompts = notifier.value.quickPrompts
        .map((item) => item.id == nextPrompt.id ? nextPrompt : item)
        .toList(growable: false);
    return _save(notifier.value.copyWith(quickPrompts: nextPrompts));
  }

  static Future<bool> deleteQuickPrompt(String id) {
    final nextPrompts = notifier.value.quickPrompts
        .where((item) => item.id != id)
        .toList(growable: false);
    return _save(notifier.value.copyWith(quickPrompts: nextPrompts));
  }

  static Future<bool> resetQuickPrompts() {
    return _save(notifier.value.copyWith(quickPrompts: defaultQuickPrompts));
  }

  static Future<bool> _save(HomeGreetingSettings settings) async {
    final saved = await StorageService.setString(
      kHomeGreetingSettingsStorageKey,
      jsonEncode(settings.toJson()),
    );
    if (saved) {
      notifier.value = settings;
    }
    return saved;
  }
}
