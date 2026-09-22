package com.flammedemon.certcheck.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.view.View
import android.widget.RemoteViews
import com.flammedemon.certcheck.MainActivity
import com.flammedemon.certcheck.R
import com.flammedemon.certcheck.UserPreferences
import com.flammedemon.certcheck.database.CertCheckDatabase
import com.flammedemon.certcheck.database.CheckHistoryEntity
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
        val preferences = UserPreferences(context)

        // Apply dynamic background color + opacity
        val baseColor = preferences.widgetColor.value
        val opacity = preferences.widgetOpacity.value
        // Color filter at full opacity for the color, imageAlpha for transparency
        val solidColor = 0xFF000000.toInt() or (baseColor and 0x00FFFFFF)
        views.setInt(R.id.widget_bg, "setColorFilter", solidColor)
        views.setInt(R.id.widget_bg, "setImageAlpha", (opacity * 255 / 100))

        // Adjust text colors for light backgrounds
        val isLight = baseColor == 0xFFFFFF
        val titleColor = if (isLight) 0xFF1565C0.toInt() else 0xFF90CAF9.toInt()
        val subtitleColor = if (isLight) 0xFF666666.toInt() else 0xB0FFFFFF.toInt()
        val updateColor = if (isLight) 0xFF999999.toInt() else 0x80FFFFFF.toInt()
        views.setTextColor(R.id.widget_title, titleColor)
        views.setTextColor(R.id.widget_last_update, updateColor)

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

            val hasIssues = warningChecks.isNotEmpty() || errorChecks.isNotEmpty()

            // Show divider only when there are issues or all OK
            if (latestChecks.isNotEmpty()) {
                views.setViewVisibility(R.id.widget_divider, View.VISIBLE)
            }

            // All OK message
            if (!hasIssues && latestChecks.isNotEmpty()) {
                views.setViewVisibility(R.id.widget_all_ok, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_all_ok, View.GONE)
            }

            // Warning domains with details
            if (warningChecks.isNotEmpty()) {
                val warningText = warningChecks.joinToString("\n") { check ->
                    val detail = buildDomainDetail(check)
                    "\u2022 ${check.hostname}$detail"
                }
                views.setTextViewText(R.id.widget_warning_list, warningText)
                views.setViewVisibility(R.id.widget_warning_section, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_warning_section, View.GONE)
            }

            // Error domains with details
            if (errorChecks.isNotEmpty()) {
                val errorText = errorChecks.joinToString("\n") { check ->
                    val detail = buildDomainDetail(check)
                    "\u2022 ${check.hostname}$detail"
                }
                views.setTextViewText(R.id.widget_error_list, errorText)
                views.setViewVisibility(R.id.widget_error_section, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_error_section, View.GONE)
            }

            val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            views.setTextViewText(R.id.widget_last_update, dateFormat.format(Date()))
        } catch (_: Exception) {
            views.setTextViewText(R.id.widget_last_update, "Erreur")
        }

        return views
    }

    private fun buildDomainDetail(check: CheckHistoryEntity): String {
        return when {
            check.error != null -> " \u2014 ${check.error}"
            check.daysUntilExpiry != null && check.daysUntilExpiry <= 0 ->
                " \u2014 Expiré"
            check.daysUntilExpiry != null ->
                " \u2014 ${check.daysUntilExpiry}j restants"
            check.issuesSummary.isNotBlank() -> {
                // Extract the first issue type for a concise display
                val firstIssue = check.issuesSummary.split(";").firstOrNull()?.trim()
                if (firstIssue != null) " \u2014 $firstIssue" else ""
            }
            else -> ""
        }
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
