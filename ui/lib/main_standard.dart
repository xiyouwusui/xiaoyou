import 'package:ui/app_bootstrap.dart';

Future<void> main(List<String> args) async {
  await bootstrapMain(args);
}

@pragma('vm:entry-point')
void subEngineMain(List<String> args) async {
  await bootstrapSubEngine(args);
}
