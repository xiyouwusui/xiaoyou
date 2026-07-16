import 'package:ui/main_standard.dart' as standard;

Future<void> main(List<String> args) => standard.main(args);

@pragma('vm:entry-point')
void subEngineMain(List<String> args) => standard.subEngineMain(args);
