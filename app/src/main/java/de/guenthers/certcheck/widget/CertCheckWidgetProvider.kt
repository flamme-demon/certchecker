package de.guenthers.certcheck.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
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
            val favoriteDao = database.favoriteDao()
            val historyDao = database.checkHistoryDao()

            // Get favorite IDs to filter
            val favorites = favoriteDao.getAllFavorites().first()
            val favoriteIds = favorites.map { it.id }.toSet()

            val latestChecks = historyDao.getLatestCheckPerFavorite().first()
                .filter { it.favoriteId in favoriteIds }

            val okCount = latestChecks.count { it.overallStatus == "OK" }
            val warningChecks = latestChecks.filter { it.overallStatus == "WARNING" }
            val errorChecks = latestChecks.filter {
                it.overallStatus in listOf("CRITICAL", "ERROR")
            }

            views.setTextViewText(R.id.widget_ok_count, okCount.toString())
            views.setTextViewText(R.id.widget_warning_count, warningChecks.size.toString())
            views.setTextViewText(R.id.widget_error_count, errorChecks.size.toString())

            // Warning domains list
            if (warningChecks.isNotEmpty()) {
                val warningText = warningChecks.joinToString(", ") { it.hostname }
                views.setTextViewText(R.id.widget_warning_list, "\u26A0 $warningText")
                views.setViewVisibility(R.id.widget_warning_list, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_warning_list, View.GONE)
            }

            // Error domains list
            if (errorChecks.isNotEmpty()) {
                val errorText = errorChecks.joinToString(", ") { it.hostname }
                views.setTextViewText(R.id.widget_error_list, "\u2716 $errorText")
                views.setViewVisibility(R.id.widget_error_list, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_error_list, View.GONE)
            }

            val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            views.setTextViewText(R.id.widget_last_update, dateFormat.format(Date()))
        } catch (_: Exception) {
            views.setTextViewText(R.id.widget_last_update, "Erreur")
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
