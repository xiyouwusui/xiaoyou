import 'dart:async';
import 'package:flutter/services.dart';

/// Claude Code CLI Service — Flutter <-> Kotlin Method/Event Channel 桥接。
///
/// 对应 Kotlin 端:
/// - MethodChannel: cn.com.omnimind.bot/ClaudeCode
/// - EventChannel: cn.com.omnimind.bot/ClaudeCodeEvents
class ClaudeCodeService {
  static const _methodChannel = MethodChannel('cn.com.omnimind.bot/ClaudeCode');
  static const _eventChannel = EventChannel('cn.com.omnimind.bot/ClaudeCodeEvents');

  static StreamSubscription? _eventSub;
  static final _eventController = StreamController<Map<String, dynamic>>.broadcast();

  /// 事件流 — 接收 Claude Code 的输出/状态变化
  static Stream<Map<String, dynamic>> get events => _eventController.stream;

  /// 开始监听事件
  static void startListening() {
    _eventSub?.cancel();
    _eventSub = _eventChannel.receiveBroadcastStream().listen(
      (event) {
        if (event is Map) {
          _eventController.add(Map<String, dynamic>.from(event));
        }
      },
      onError: (e) {},
    );
  }

  /// 停止监听
  static void stopListening() {
    _eventSub?.cancel();
    _eventSub = null;
  }

  /// 获取 Claude Code 状态
  static Future<Map<String, dynamic>> status() async {
    final result = await _methodChannel.invokeMethod('status');
    return _normalize(result);
  }

  /// 安装 Claude Code CLI — 进度通过 events 流推送
  static Future<Map<String, dynamic>> install() async {
    final result = await _methodChannel.invokeMethod('install');
    return _normalize(result);
  }

  /// 发送消息给 Claude Code
  static Future<Map<String, dynamic>> send(String message) async {
    final result = await _methodChannel.invokeMethod('send', {'message': message});
    return _normalize(result);
  }

  // === Profile 管理 ===

  static Future<List<Map<String, dynamic>>> listProfiles() async {
    final result = await _methodChannel.invokeMethod('profiles/list');
    final map = _normalize(result);
    final profiles = map['profiles'] as List?;
    return profiles?.map((p) => Map<String, dynamic>.from(p as Map)).toList() ?? [];
  }

  static Future<Map<String, dynamic>?> activeProfile() async {
    final result = await _methodChannel.invokeMethod('profiles/active');
    final map = _normalize(result);
    return map['profile'] as Map<String, dynamic>?;
  }

  static Future<bool> activateProfile(String id) async {
    final result = await _methodChannel.invokeMethod('profiles/activate', {'id': id});
    return _normalize(result)['ok'] == true;
  }

  static Future<String?> addProfile({
    required String name,
    required String apiKey,
    String baseUrl = '',
    String model = '',
    String extraArgs = '',
  }) async {
    final result = await _methodChannel.invokeMethod('profiles/add', {
      'name': name,
      'apiKey': apiKey,
      'baseUrl': baseUrl,
      'model': model,
      'extraArgs': extraArgs,
    });
    return _normalize(result)['id'] as String?;
  }

  static Future<bool> updateProfile({
    required String id,
    String? name,
    String? apiKey,
    String? baseUrl,
    String? model,
    String? extraArgs,
  }) async {
    final args = {'id': id};
    if (name != null) args['name'] = name;
    if (apiKey != null) args['apiKey'] = apiKey;
    if (baseUrl != null) args['baseUrl'] = baseUrl;
    if (model != null) args['model'] = model;
    if (extraArgs != null) args['extraArgs'] = extraArgs;
    final result = await _methodChannel.invokeMethod('profiles/update', args);
    return _normalize(result)['ok'] == true;
  }

  static Future<bool> deleteProfile(String id) async {
    final result = await _methodChannel.invokeMethod('profiles/delete', {'id': id});
    return _normalize(result)['ok'] == true;
  }

  static Map<String, dynamic> _normalize(dynamic result) {
    if (result is Map) {
      return Map<String, dynamic>.from(result);
    }
    return {};
  }
}
