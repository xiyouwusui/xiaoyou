package cn.com.omnimind.bot.quicklog

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

object QuickLogWidgetUpdater {
    fun updateAll(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, QuickLogWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(provider)
        if (widgetIds.isEmpty()) {
            return
        }
        QuickLogWidgetProvider.updateWidgets(context, appWidgetManager, widgetIds)
    }
}
