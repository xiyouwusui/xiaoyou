import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/codex_app_server_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/CodexAppServer');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('startTurn forwards codex permission payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.startTurn(
      conversationId: 42,
      threadId: 'thread-1',
      text: 'hello',
      attachments: const <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'image-1',
          'name': 'screen.png',
          'path': '/tmp/screen.png',
          'mimeType': 'image/png',
          'isImage': true,
        },
      ],
      approvalPolicy: 'never',
      approvalsReviewer: 'user',
      sandboxPolicy: const <String, dynamic>{'type': 'dangerFullAccess'},
      model: 'gpt-5-codex',
      effort: 'high',
      collaborationMode: 'plan',
    );

    expect(capturedCall?.method, 'turn/start');
    final args = Map<String, dynamic>.from(
      (capturedCall?.arguments as Map).cast<String, dynamic>(),
    );
    expect(args['conversationId'], 42);
    expect(args['threadId'], 'thread-1');
    expect(args['text'], 'hello');
    expect(args['attachments'], const <Map<String, dynamic>>[
      <String, dynamic>{
        'id': 'image-1',
        'name': 'screen.png',
        'path': '/tmp/screen.png',
        'mimeType': 'image/png',
        'isImage': true,
      },
    ]);
    expect(args['approvalPolicy'], 'never');
    expect(args['approvalsReviewer'], 'user');
    expect(args['sandboxPolicy'], const <String, dynamic>{
      'type': 'dangerFullAccess',
    });
    expect(args['model'], 'gpt-5-codex');
    expect(args['effort'], 'high');
    expect(args['collaborationMode'], 'plan');
  });

  test('startReview forwards codex review payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.startReview(
      conversationId: 42,
      threadId: 'thread-1',
      approvalPolicy: 'on-request',
      approvalsReviewer: 'guardian_subagent',
      model: 'gpt-5-codex',
      effort: 'xhigh',
      collaborationMode: 'plan',
    );

    expect(capturedCall?.method, 'review/start');
    final args = Map<String, dynamic>.from(
      (capturedCall?.arguments as Map).cast<String, dynamic>(),
    );
    expect(args['conversationId'], 42);
    expect(args['threadId'], 'thread-1');
    expect(args['approvalPolicy'], 'on-request');
    expect(args['approvalsReviewer'], 'guardian_subagent');
    expect(args['target'], const <String, dynamic>{
      'type': 'uncommittedChanges',
    });
    expect(args['model'], 'gpt-5-codex');
    expect(args['effort'], 'xhigh');
    expect(args['collaborationMode'], 'plan');
  });

  test('lists codex models, collaboration modes, and config', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.listModels();
    await CodexAppServerService.listCollaborationModes();
    await CodexAppServerService.readConfig();
    await CodexAppServerService.listLoadedThreads();

    expect(calls.map((call) => call.method), [
      'model/list',
      'collaborationMode/list',
      'config/read',
      'thread/loaded/list',
    ]);
    expect(calls.first.arguments, {'limit': 100});
  });

  test('ignoreUserInput responds with empty answers payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.ignoreUserInput(requestId: 'request-1');

    expect(capturedCall?.method, 'respondToServerRequest');
    expect(capturedCall?.arguments, {
      'requestId': 'request-1',
      'response': {'answers': <String, dynamic>{}},
    });
  });

  test('readThread requests turns by default', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.readThread(threadId: 'thread-1');

    expect(capturedCall?.method, 'thread/read');
    expect(capturedCall?.arguments, {
      'threadId': 'thread-1',
      'includeTurns': true,
    });
  });

  test('reads and writes local codex config files', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{
        'baseUrl': 'https://example.com/v1',
        'model': 'gpt-5.5',
        'officialModel': 'gpt-5.5-codex',
        'localAuthMode': 'chatgpt',
        'apiKey': 'key',
        'codexHome': '/root/.codex',
      };
    });

    final read = await CodexAppServerService.readLocalConfig();
    final written = await CodexAppServerService.writeLocalConfig(
      baseUrl: ' https://example.com/v1 ',
      model: ' gpt-5.5 ',
      apiKey: ' key ',
      officialModel: ' gpt-5.5-codex ',
      localAuthMode: CodexLocalAuthMode.chatgpt,
    );

    expect(read.baseUrl, 'https://example.com/v1');
    expect(read.model, 'gpt-5.5');
    expect(read.officialModel, 'gpt-5.5-codex');
    expect(read.localAuthMode, CodexLocalAuthMode.chatgpt);
    expect(read.apiKey, 'key');
    expect(written.codexHome, '/root/.codex');
    expect(calls.map((call) => call.method), [
      'config/local/read',
      'config/local/write',
    ]);
    expect(calls.last.arguments, <String, dynamic>{
      'baseUrl': 'https://example.com/v1',
      'model': 'gpt-5.5',
      'apiKey': 'key',
      'officialModel': 'gpt-5.5-codex',
      'localAuthMode': 'chatgpt',
      'remoteEnabled': false,
      'remoteBridgeUrl': '',
      'remoteBridgeToken': '',
      'remoteCwd': '',
    });
  });

  test('forwards ChatGPT device-code login lifecycle', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.startLogin(
      type: CodexLoginType.chatgptDeviceCode,
    );
    await CodexAppServerService.cancelLogin(loginId: 'login-1');

    expect(calls.map((call) => call.method), [
      'account/login/start',
      'account/login/cancel',
    ]);
    expect(calls[0].arguments, {'type': 'chatgptDeviceCode'});
    expect(calls[1].arguments, {'loginId': 'login-1'});
  });

  test('forwards custom Codex model list credentials', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'models': <dynamic>[]};
    });

    await CodexAppServerService.listLocalApiModels(
      baseUrl: ' https://example.com/v1 ',
      apiKey: ' secret ',
    );

    expect(capturedCall?.method, 'config/local/models');
    expect(capturedCall?.arguments, {
      'baseUrl': 'https://example.com/v1',
      'apiKey': 'secret',
    });
  });

  test('keeps Codex model sources separate', () {
    expect(
      codexModelSourceKey(
        const CodexStatus(
          connected: true,
          ready: true,
          runtime: 'remote',
          localAuthMode: CodexLocalAuthMode.api,
        ),
      ),
      'remote',
    );
    expect(
      codexModelSourceKey(
        const CodexStatus(
          connected: true,
          ready: true,
          runtime: 'local',
          localAuthMode: CodexLocalAuthMode.chatgpt,
        ),
      ),
      'local-chatgpt',
    );
    expect(
      codexModelSourceKey(
        const CodexStatus(
          connected: true,
          ready: true,
          runtime: 'local',
          localAuthMode: CodexLocalAuthMode.api,
        ),
      ),
      'local-api',
    );
  });

  test('local API requests always use the configured model', () {
    final model = selectCodexRequestModel(
      status: const CodexStatus(
        connected: true,
        ready: true,
        runtime: 'local',
        localAuthMode: CodexLocalAuthMode.api,
      ),
      overrideModel: 'remote-override',
      activeModel: 'chatgpt-active',
      scopedModel: 'api-scoped',
      configuredApiModel: 'api-current',
      activeModelSourceMatches: true,
    );

    expect(model, 'api-current');
  });

  test('model load results are rejected after source changes', () {
    expect(
      isCurrentCodexModelLoad(
        requestId: 4,
        activeRequestId: 4,
        requestSource: 'remote',
        currentSource: 'local-api',
      ),
      isFalse,
    );
    expect(
      isCurrentCodexModelLoad(
        requestId: 5,
        activeRequestId: 5,
        requestSource: 'local-api',
        currentSource: 'local-api',
      ),
      isTrue,
    );
  });

  test(
    'forwards remote filesystem operations without trimming content',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'config/remote/fs/read') {
          return <String, dynamic>{
            'ok': true,
            'path': '/repo/lib/main.dart',
            'name': 'main.dart',
            'previewKind': 'code',
            'mimeType': 'text/plain',
            'content': 'void main() {}',
          };
        }
        return <String, dynamic>{'ok': true};
      });

      final read = await CodexAppServerService.readRemoteFile(
        remoteBridgeUrl: ' ws://pc:17321/codex ',
        remoteBridgeToken: ' token ',
        remoteCwd: ' /repo ',
        path: ' /repo/lib/main.dart ',
      );
      await CodexAppServerService.writeRemoteFile(
        path: '/repo/lib/main.dart',
        content: '  keep whitespace\n',
      );
      await CodexAppServerService.deleteRemotePath(
        path: '/repo/tmp',
        recursive: true,
      );
      await CodexAppServerService.moveRemotePath(
        path: '/repo/a.dart',
        destinationPath: '/repo/b.dart',
      );

      expect(read.content, 'void main() {}');
      expect(calls.map((call) => call.method), [
        'config/remote/fs/read',
        'config/remote/fs/write',
        'config/remote/fs/delete',
        'config/remote/fs/move',
      ]);
      expect(calls[0].arguments, <String, dynamic>{
        'remoteBridgeUrl': 'ws://pc:17321/codex',
        'remoteBridgeToken': 'token',
        'remoteCwd': '/repo',
        'path': '/repo/lib/main.dart',
      });
      expect((calls[1].arguments as Map)['content'], '  keep whitespace\n');
      expect((calls[2].arguments as Map)['recursive'], true);
      expect((calls[3].arguments as Map)['destinationPath'], '/repo/b.dart');
    },
  );
}
