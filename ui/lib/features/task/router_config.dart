import 'package:go_router/go_router.dart';
import 'pages/scheduled_tasks/scheduled_task_list_page.dart';

/// Task模块路由配置
List<GoRoute> taskRoutes = [
  // 定时任务列表页
  GoRoute(
    path: '/task/scheduled_tasks',
    name: 'task/scheduled_tasks',
    builder: (context, state) =>
        ScheduledTaskListPage(initialTab: state.uri.queryParameters['tab']),
  ),
];
