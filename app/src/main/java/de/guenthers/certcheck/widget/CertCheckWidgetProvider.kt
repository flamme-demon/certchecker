package de.guenthers.certcheck.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import de.guenthers.certcheck.MainActivity
import de.guenthers.certcheck.R
import de.guenthers.certcheck.database.CertCheckDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CertCheckWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        scope.launch {
            val views = buildRemoteViews(context)
            for (appWidgetId in appWidgetIds) {
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private suspend fun buildRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_certcheck)

        // Click opens the app
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        try {
            val database = CertCheckDatabase.getDatabase(context)
            val historyDao = database.checkHistoryDao()
            val latestChecks = historyDao.getLatestCheckPerFavorite().first()

            val okCount = latestChecks.count { it.overallStatus == "OK" }
            val warningCount = latestChecks.count { it.overallStatus == "WARNING" }
            val errorCount = latestChecks.count {
                it.overallStatus in listOf("CRITICAL", "ERROR")
            }

            views.setTextViewText(R.id.widget_ok_count, okCount.toString())
            views.setTextViewText(R.id.widget_warning_count, warningCount.toString())
            views.setTextViewText(R.id.widget_error_count, errorCount.toString())

            val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            views.setTextViewText(
                R.id.widget_last_update,
                "Mis à jour : ${dateFormat.format(Date())}"
            )
        } catch (_: Exception) {
            views.setTextViewText(R.id.widget_last_update, "Erreur de chargement")
        }

        return views
    }

    companion object {
        fun updateAll(context: Context) {
            val intent = Intent(context, CertCheckWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, CertCheckWidgetProvider::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}
