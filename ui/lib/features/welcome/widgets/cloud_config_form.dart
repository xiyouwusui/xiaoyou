import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/theme/theme_context.dart';

/// Protocol type options for the cloud config form.
class ProtocolOption {
  const ProtocolOption({required this.value, required this.label});
  final String value;
  final String label;
}

const List<ProtocolOption> kProtocolOptions = [
  ProtocolOption(value: 'openai_compatible', label: 'OpenAI'),
  ProtocolOption(value: 'anthropic', label: 'Anthropic'),
];

/// Result returned when the cloud config form is saved successfully.
class CloudConfigResult {
  final String profileId;
  final String name;

  const CloudConfigResult({required this.profileId, required this.name});
}

/// Simplified cloud AI configuration form widget.
///
/// Provides protocol selector, name, base URL, API key fields,
/// a test connection button, and a save button.
class CloudConfigForm extends StatefulWidget {
  final void Function(CloudConfigResult result)? onSaved;

  const CloudConfigForm({super.key, this.onSaved});

  @override
  State<CloudConfigForm> createState() => _CloudConfigFormState();
}

class _CloudConfigFormState extends State<CloudConfigForm> {
  final _nameController = TextEditingController();
  final _baseUrlController = TextEditingController();
  final _apiKeyController = TextEditingController();

  String _protocolType = 'openai_compatible';
  bool _obscureApiKey = true;
  bool _isTesting = false;
  bool _isSaving = false;
  String? _testResult;
  bool? _testSuccess;

  @override
  void dispose() {
    _nameController.dispose();
    _baseUrlController.dispose();
    _apiKeyController.dispose();
    super.dispose();
  }

  bool get _canSave =>
      _nameController.text.trim().isNotEmpty &&
      _baseUrlController.text.trim().isNotEmpty;

  bool get _canTest => _baseUrlController.text.trim().isNotEmpty;

  Future<void> _testConnection() async {
    setState(() {
      _isTesting = true;
      _testResult = null;
      _testSuccess = null;
    });
    try {
      final models = await ModelProviderConfigService.fetchModels(
        apiBase: _baseUrlController.text.trim(),
        apiKey: _apiKeyController.text.trim(),
      );
      if (!mounted) return;
      setState(() {
        _isTesting = false;
        _testSuccess = true;
        _testResult = '${context.trLegacy('连接成功')}，${context.trLegacy('发现')} ${models.length} ${context.trLegacy('个模型')}';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isTesting = false;
        _testSuccess = false;
        _testResult = '${context.trLegacy('连接失败')}: $e';
      });
    }
  }

  Future<void> _save() async {
    if (!_canSave || _isSaving) return;
    setState(() => _isSaving = true);
    try {
      final profile = await ModelProviderConfigService.saveProfile(
        name: _nameController.text.trim(),
        baseUrl: _baseUrlController.text.trim(),
        apiKey: _apiKeyController.text.trim(),
        protocolType: _protocolType,
      );
      await ModelProviderConfigService.setEditingProfile(profile.id);
      if (!mounted) return;
      widget.onSaved?.call(
        CloudConfigResult(profileId: profile.id, name: profile.name),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('${context.trLegacy('保存失败')}: $e')),
      );
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final cardColor = palette.surfacePrimary;
    final secondaryText = palette.textSecondary;
    final tertiaryText = palette.textTertiary;
    final subtleBorder = BorderSide(color: palette.borderSubtle);
    final accentColor = palette.accentPrimary;

    InputDecoration inputDecoration({
      required String label,
      String? hint,
      Widget? suffixIcon,
    }) {
      return InputDecoration(
        labelText: label,
        hintText: hint,
        labelStyle: TextStyle(color: secondaryText, fontSize: 13),
        hintStyle: TextStyle(color: tertiaryText, fontSize: 13),
        filled: true,
        fillColor: cardColor,
        suffixIcon: suffixIcon,
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: subtleBorder,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: subtleBorder,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: accentColor),
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Protocol type selector
        Text(
          context.trLegacy('协议类型'),
          style: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w500,
            color: palette.textPrimary,
          ),
        ),
        const SizedBox(height: 8),
        Row(
          children: kProtocolOptions.map((option) {
            final selected = _protocolType == option.value;
            return Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(option.label),
                selected: selected,
                onSelected: (_) =>
                    setState(() => _protocolType = option.value),
                selectedColor: accentColor.withOpacity(0.15),
                backgroundColor: palette.surfaceSecondary,
                labelStyle: TextStyle(
                  color: selected ? accentColor : palette.textSecondary,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                  fontSize: 13,
                ),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                  side: BorderSide(
                    color: selected ? accentColor : palette.borderSubtle,
                  ),
                ),
                showCheckmark: false,
              ),
            );
          }).toList(),
        ),
        const SizedBox(height: 20),

        // Name field
        TextField(
          controller: _nameController,
          decoration: inputDecoration(
            label: context.trLegacy('名称'),
            hint: context.trLegacy('例如：我的 OpenAI'),
          ),
          onChanged: (_) => setState(() {}),
        ),
        const SizedBox(height: 12),

        // Base URL field
        TextField(
          controller: _baseUrlController,
          decoration: inputDecoration(
            label: 'Base URL',
            hint: 'https://api.openai.com/v1',
          ),
          keyboardType: TextInputType.url,
          onChanged: (_) => setState(() {}),
        ),
        const SizedBox(height: 12),

        // API Key field
        TextField(
          controller: _apiKeyController,
          obscureText: _obscureApiKey,
          decoration: inputDecoration(
            label: context.trLegacy('API Key（可选）'),
            hint: 'sk-xxxx',
            suffixIcon: IconButton(
              splashRadius: 18,
              onPressed: () =>
                  setState(() => _obscureApiKey = !_obscureApiKey),
              icon: Icon(
                _obscureApiKey ? Icons.visibility_off : Icons.visibility,
                size: 20,
                color: tertiaryText,
              ),
            ),
          ),
          onChanged: (_) => setState(() {}),
        ),
        const SizedBox(height: 20),

        // Test connection button
        SizedBox(
          width: double.infinity,
          height: 44,
          child: OutlinedButton(
            onPressed: _canTest && !_isTesting ? _testConnection : null,
            style: OutlinedButton.styleFrom(
              side: BorderSide(color: accentColor),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            child: _isTesting
                ? SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: accentColor,
                    ),
                  )
                : Text(
                    context.trLegacy('测试连接'),
                    style: TextStyle(
                      color: accentColor,
                      fontSize: 15,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
          ),
        ),

        // Test result
        if (_testResult != null) ...[
          const SizedBox(height: 8),
          Text(
            _testResult!,
            style: TextStyle(
              fontSize: 13,
              color: _testSuccess == true
                  ? (isDark ? const Color(0xFF7BC67E) : Colors.green)
                  : (isDark ? const Color(0xFFE57373) : Colors.red),
            ),
          ),
        ],
        const SizedBox(height: 24),

        // Save button
        SizedBox(
          width: double.infinity,
          height: 48,
          child: ElevatedButton(
            onPressed: _canSave && !_isSaving ? _save : null,
            style: ElevatedButton.styleFrom(
              backgroundColor: accentColor,
              foregroundColor: Colors.white,
              disabledBackgroundColor: isDark
                  ? palette.surfaceElevated
                  : Colors.grey.shade300,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            child: _isSaving
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : Text(
                    context.trLegacy('保存'),
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
          ),
        ),
      ],
    );
  }
}
