package cn.com.omnimind.bot.quicklog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.QuickLogEntryActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QuickLogWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        QuickLogWidgetUpdater.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            QuickLogWidgetUpdater.updateAll(context)
        }
    }

    companion object {
        private val widgetTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context),
            appWidgetIds: IntArray = appWidgetManager.getAppWidgetIds(
                ComponentName(context, QuickLogWidgetProvider::class.java)
            )
        ) {
            val service = QuickLogService(context)
            appWidgetIds.forEach { appWidgetId ->
                appWidgetManager.updateAppWidget(
                    appWidgetId,
                    buildRemoteViews(context, service)
                )
            }
        }

        private fun buildRemoteViews(
            context: Context,
            service: QuickLogService
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_log)
            val records = service.latestLogsForWidget(limit = 3)
            val totalCount = service.countLogs()

            views.setOnClickPendingIntent(
                R.id.quick_log_widget_add_button,
                buildQuickAddPendingIntent(context)
            )
            views.setOnClickPendingIntent(
                R.id.quick_log_widget_open_button,
                buildOpenLogsPendingIntent(context)
            )
            views.setOnClickPendingIntent(
                R.id.quick_log_widget_root,
                buildOpenLogsPendingIntent(context)
            )

            val summary = if (records.isEmpty()) {
                context.getString(R.string.quick_log_widget_empty_summary)
            } else {
                context.getString(
                    R.string.quick_log_widget_summary,
                    totalCount
                )
            }
            views.setTextViewText(R.id.quick_log_widget_summary, summary)

            val itemViewIds = listOf(
                R.id.quick_log_widget_item_1,
                R.id.quick_log_widget_item_2,
                R.id.quick_log_widget_item_3
            )

            itemViewIds.forEachIndexed { index, viewId ->
                val record = records.getOrNull(index)
                if (record == null) {
                    views.setViewVisibility(viewId, if (index == 0) View.VISIBLE else View.GONE)
                    if (index == 0) {
                        views.setTextViewText(
                            viewId,
                            context.getString(R.string.quick_log_widget_empty_hint)
                        )
                    }
                } else {
                    views.setViewVisibility(viewId, View.VISIBLE)
                    val timestamp = widgetTimeFormat.format(Date(record.updatedAtMillis))
                    val preview = record.content.replace(Regex("\\s+"), " ").trim()
                    views.setTextViewText(
                        viewId,
                        "$timestamp  $preview"
                    )
                }
                views.setOnClickPendingIntent(viewId, buildOpenLogsPendingIntent(context))
            }

            return views
        }

        private fun buildOpenLogsPendingIntent(context: Context): PendingIntent {
            val intent = Intent()
                .setClassName(context, "cn.com.omnimind.bot.activity.LauncherActivity")
                .putExtra("route", "/home/quick_logs")
                .putExtra("needClear", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                3001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun buildQuickAddPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, QuickLogEntryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                3002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
