import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/codex_request_card.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const codexChannel = MethodChannel('cn.com.omnimind.bot/CodexAppServer');
  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async => null);
  });

  tearDown(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(codexChannel, null);
    messenger.setMockMethodCallHandler(assistCoreChannel, null);
  });

  testWidgets('renders requestUserInput options and submits selection', (
    tester,
  ) async {
    MethodCall? submittedCall;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(codexChannel, (call) async {
      submittedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: CodexRequestCard(
            cardData: <String, dynamic>{
              'type': 'codex_request',
              'requestId': 'request-1',
              'requestKind': 'user_input',
              'title': 'Choose mode',
              'detail': 'Pick one',
              'questionId': 'mode',
              'status': 'pending',
              'rawParamsJson': jsonEncode({
                'questions': [
                  {
                    'id': 'mode',
                    'question': 'Choose mode',
                    'options': [
                      {'label': 'Plan', 'description': 'Plan first'},
                      {'label': 'Chat', 'description': 'Answer directly'},
                    ],
                  },
                ],
              }),
            },
          ),
        ),
      ),
    );

    expect(find.text('Plan'), findsOneWidget);
    expect(find.text('Chat'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);

    await tester.tap(find.text('Chat'));
    await tester.pump();
    await tester.tap(find.text('Submit'));
    await tester.pumpAndSettle();

    expect(submittedCall?.method, 'respondToServerRequest');
    expect(submittedCall?.arguments, containsPair('requestId', 'request-1'));
    final arguments = Map<String, dynamic>.from(
      submittedCall!.arguments as Map,
    );
    final response = Map<String, dynamic>.from(arguments['response'] as Map);
    final answers = Map<String, dynamic>.from(response['answers'] as Map);
    final mode = Map<String, dynamic>.from(answers['mode'] as Map);
    expect(mode['answers'], <String>['Chat']);
    expect(find.text('submitted: Chat'), findsOneWidget);
  });

  testWidgets('fills the available message width', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 360,
              child: CodexRequestCard(cardData: _requestCardData()),
            ),
          ),
        ),
      ),
    );

    final surface = find.byKey(const ValueKey('codex-request-card-surface'));
    expect(surface, findsOneWidget);
    expect(tester.getSize(surface).width, closeTo(360, 0.1));
  });
}

Map<String, dynamic> _requestCardData() {
  return <String, dynamic>{
    'type': 'codex_request',
    'requestId': 'request-1',
    'requestKind': 'user_input',
    'title': 'Choose mode',
    'detail': 'Pick one',
    'questionId': 'mode',
    'status': 'pending',
    'rawParamsJson': jsonEncode({
      'questions': [
        {
          'id': 'mode',
          'question': 'Choose mode',
          'options': [
            {'label': 'Plan', 'description': 'Plan first'},
            {'label': 'Chat', 'description': 'Answer directly'},
          ],
        },
      ],
    }),
  };
}
