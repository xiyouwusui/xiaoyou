import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/codex_app_server_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';

class CodexRequestCard extends StatefulWidget {
  const CodexRequestCard({super.key, required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  State<CodexRequestCard> createState() => _CodexRequestCardState();
}

class _CodexRequestCardState extends State<CodexRequestCard> {
  final TextEditingController _answerController = TextEditingController();
  bool _isSubmitting = false;
  String? _localStatus;
  List<String> _localAnswers = const <String>[];
  String? _selectedOptionValue;

  @override
  void initState() {
    super.initState();
    _hydratePersistedResponse();
  }

  @override
  void didUpdateWidget(covariant CodexRequestCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_requestStorageIdentity(oldWidget.cardData) !=
        _requestStorageIdentity(widget.cardData)) {
      _localStatus = null;
      _localAnswers = const <String>[];
      _selectedOptionValue = null;
      _answerController.clear();
      _hydratePersistedResponse();
    }
  }

  @override
  void dispose() {
    _answerController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final kind = (widget.cardData['requestKind'] ?? '').toString();
    final title = (widget.cardData['title'] ?? 'Codex request').toString();
    final detail = (widget.cardData['detail'] ?? '').toString();
    final status =
        _localStatus ?? (widget.cardData['status'] ?? 'pending').toString();
    final isPending = status == 'pending' && !_isSubmitting;
    final options = _resolveRequestOptions(widget.cardData);
    final hasOptions = options.isNotEmpty;
    final answers = _localAnswers.isNotEmpty
        ? _localAnswers
        : _stringList(widget.cardData['submittedAnswers']);
    final canSubmit =
        isPending &&
        (!hasOptions ||
            _selectedOptionValue != null ||
            _answerController.text.trim().isNotEmpty);

    return Container(
      key: const ValueKey('codex-request-card-surface'),
      width: double.infinity,
      margin: const EdgeInsets.only(top: 8, bottom: 4),
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : const Color(0xFFF3F5F6),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: context.isDarkTheme
              ? palette.borderSubtle
              : const Color(0xFFE1E5E8),
        ),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: palette.textPrimary,
              height: 1.2,
            ),
          ),
          if (detail.trim().isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              detail,
              maxLines: 5,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 12,
                color: palette.textSecondary,
                height: 1.35,
              ),
            ),
          ],
          const SizedBox(height: 10),
          if (kind == 'user_input' && status == 'pending') ...[
            if (hasOptions) ...[
              for (final option in options) ...[
                _RequestOptionTile(
                  option: option,
                  selected: option.value == _selectedOptionValue,
                  enabled: isPending,
                  onTap: () {
                    setState(() {
                      _selectedOptionValue = option.value;
                      _answerController.text = option.value;
                    });
                  },
                ),
                const SizedBox(height: 6),
              ],
            ] else ...[
              TextField(
                controller: _answerController,
                minLines: 1,
                maxLines: 3,
                style: TextStyle(fontSize: 12, color: palette.textPrimary),
                decoration: InputDecoration(
                  isDense: true,
                  hintText: 'Answer',
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 8,
                  ),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(6),
                  ),
                ),
              ),
              const SizedBox(height: 8),
            ],
          ],
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (_isSubmitting)
                SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: palette.accentPrimary,
                  ),
                )
              else if (status != 'pending')
                Text(
                  answers.isEmpty ? status : '$status: ${answers.join(', ')}',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: palette.textSecondary,
                  ),
                )
              else if (kind == 'approval') ...[
                TextButton(
                  onPressed: isPending ? () => _respondApproval(true) : null,
                  child: const Text('Accept'),
                ),
                const SizedBox(width: 6),
                TextButton(
                  onPressed: isPending ? () => _respondApproval(false) : null,
                  child: const Text('Decline'),
                ),
              ] else ...[
                TextButton(
                  onPressed: canSubmit ? _respondUserInput : null,
                  child: const Text('Submit'),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _respondApproval(bool accepted) async {
    final requestId = widget.cardData['requestId'];
    if (requestId == null) return;
    await _submit(() {
      return CodexAppServerService.respondToApproval(
        requestId: requestId,
        accepted: accepted,
      );
    }, accepted ? 'accepted' : 'declined');
  }

  Future<void> _respondUserInput() async {
    final requestId = widget.cardData['requestId'];
    final questionId = (widget.cardData['questionId'] ?? 'answer').toString();
    if (requestId == null) return;
    final answer = (_selectedOptionValue ?? _answerController.text).trim();
    await _submit(() {
      return CodexAppServerService.respondToUserInput(
        requestId: requestId,
        questionId: questionId,
        answers: <String>[answer],
      );
    }, 'submitted');
  }

  Future<void> _submit(
    Future<Map<String, dynamic>> Function() action,
    String successStatus,
  ) async {
    if (_isSubmitting) return;
    setState(() {
      _isSubmitting = true;
    });
    try {
      await action();
      final answers = <String>[
        if (_answerController.text.trim().isNotEmpty)
          _answerController.text.trim(),
      ];
      await _persistResponseStatus(successStatus, answers);
      if (!mounted) return;
      setState(() {
        _localStatus = successStatus;
        _localAnswers = answers;
        _isSubmitting = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _localStatus = 'failed';
        _isSubmitting = false;
      });
    }
  }

  void _hydratePersistedResponse() {
    try {
      final raw = StorageService.getString(_requestStorageKey(widget.cardData));
      if (raw == null || raw.trim().isEmpty) {
        return;
      }
      final decoded = jsonDecode(raw);
      if (decoded is! Map) {
        return;
      }
      final status = decoded['status']?.toString().trim();
      if (status == null || status.isEmpty) {
        return;
      }
      _localStatus = status;
      _localAnswers = _stringList(decoded['answers']);
    } catch (_) {
      return;
    }
  }

  Future<void> _persistResponseStatus(
    String status,
    List<String> answers,
  ) async {
    final nextCardData = Map<String, dynamic>.from(widget.cardData)
      ..['status'] = status
      ..['submittedAnswers'] = answers;
    widget.cardData['status'] = status;
    widget.cardData['submittedAnswers'] = answers;

    final conversationId = _asInt(widget.cardData['conversationId']);
    final cardId = (widget.cardData['cardId'] ?? widget.cardData['id'] ?? '')
        .toString()
        .trim();
    if (conversationId != null && cardId.isNotEmpty) {
      await ConversationHistoryService.upsertConversationUiCard(
        conversationId,
        entryId: cardId,
        cardData: nextCardData,
        createdAtMillis: _asInt(widget.cardData['startTime']),
        mode: ConversationMode.codex,
      );
    }
    try {
      await StorageService.setString(
        _requestStorageKey(widget.cardData),
        jsonEncode(<String, dynamic>{'status': status, 'answers': answers}),
      );
    } catch (_) {
      return;
    }
  }
}

class _RequestOptionTile extends StatelessWidget {
  const _RequestOptionTile({
    required this.option,
    required this.selected,
    required this.enabled,
    required this.onTap,
  });

  final _RequestOption option;
  final bool selected;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final borderColor = selected ? palette.accentPrimary : palette.borderSubtle;
    return Material(
      color: selected
          ? palette.accentPrimary.withValues(alpha: 0.10)
          : Colors.transparent,
      borderRadius: BorderRadius.circular(6),
      child: InkWell(
        onTap: enabled ? onTap : null,
        borderRadius: BorderRadius.circular(6),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(6),
            border: Border.all(color: borderColor),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                option.label,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: enabled ? palette.textPrimary : palette.textTertiary,
                  height: 1.25,
                ),
              ),
              if (option.description.isNotEmpty) ...[
                const SizedBox(height: 2),
                Text(
                  option.description,
                  style: TextStyle(
                    fontSize: 11,
                    color: palette.textSecondary,
                    height: 1.25,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _RequestOption {
  const _RequestOption({
    required this.label,
    required this.value,
    this.description = '',
  });

  final String label;
  final String value;
  final String description;
}

List<_RequestOption> _resolveRequestOptions(Map<String, dynamic> cardData) {
  final raw = _decodeRawParams(cardData['rawParamsJson']);
  final questionId = (cardData['questionId'] ?? '').toString().trim();
  final question = _resolveQuestion(raw, questionId);
  final optionSource =
      question?['options'] ??
      question?['choices'] ??
      question?['items'] ??
      raw['options'] ??
      raw['choices'];
  if (optionSource is! List) {
    return const <_RequestOption>[];
  }
  final seen = <String>{};
  final options = <_RequestOption>[];
  for (final item in optionSource) {
    final option = _requestOptionFromValue(item);
    if (option == null || !seen.add(option.value)) {
      continue;
    }
    options.add(option);
  }
  return options;
}

Map<String, dynamic> _decodeRawParams(dynamic rawParamsJson) {
  final raw = rawParamsJson?.toString().trim() ?? '';
  if (raw.isEmpty) {
    return const <String, dynamic>{};
  }
  try {
    final decoded = jsonDecode(raw);
    if (decoded is Map) {
      return decoded.map((key, value) => MapEntry(key.toString(), value));
    }
  } catch (_) {
    return const <String, dynamic>{};
  }
  return const <String, dynamic>{};
}

Map<String, dynamic>? _resolveQuestion(
  Map<String, dynamic> raw,
  String questionId,
) {
  final questions = raw['questions'];
  if (questions is! List || questions.isEmpty) {
    return null;
  }
  for (final item in questions) {
    final map = _asStringMap(item);
    if (map == null) {
      continue;
    }
    final id = (map['id'] ?? map['questionId'] ?? '').toString();
    if (questionId.isNotEmpty && id == questionId) {
      return map;
    }
  }
  return _asStringMap(questions.first);
}

_RequestOption? _requestOptionFromValue(dynamic value) {
  if (value is String || value is num || value is bool) {
    final label = value.toString().trim();
    return label.isEmpty ? null : _RequestOption(label: label, value: label);
  }
  final map = _asStringMap(value);
  if (map == null) {
    return null;
  }
  final label =
      _firstText([
        map['label'],
        map['title'],
        map['name'],
        map['value'],
        map['id'],
      ]) ??
      '';
  if (label.isEmpty) {
    return null;
  }
  final optionValue =
      _firstText([map['value'], map['id'], map['label']]) ?? label;
  final description =
      _firstText([map['description'], map['detail'], map['subtitle']]) ?? '';
  return _RequestOption(
    label: label,
    value: optionValue,
    description: description,
  );
}

String _requestStorageKey(Map<String, dynamic> cardData) {
  return 'codex_request_response.${_requestStorageIdentity(cardData)}';
}

String _requestStorageIdentity(Map<String, dynamic> cardData) {
  final requestId = (cardData['requestId'] ?? '').toString().trim();
  if (requestId.isNotEmpty) {
    return requestId;
  }
  return (cardData['cardId'] ?? cardData['id'] ?? '').toString().trim();
}

Map<String, dynamic>? _asStringMap(dynamic value) {
  if (value is! Map) {
    return null;
  }
  return value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue));
}

List<String> _stringList(dynamic value) {
  if (value is! List) {
    return const <String>[];
  }
  return value
      .map((item) => item?.toString().trim() ?? '')
      .where((item) => item.isNotEmpty)
      .toList(growable: false);
}

String? _firstText(Iterable<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return null;
}

int? _asInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  return int.tryParse(value?.toString() ?? '');
}
