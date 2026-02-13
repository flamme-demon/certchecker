package de.guenthers.certcheck

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _checkHour = MutableStateFlow(prefs.getInt(KEY_CHECK_HOUR, DEFAULT_CHECK_HOUR))
    val checkHour: StateFlow<Int> = _checkHour.asStateFlow()

    private val _alertThresholdDays = MutableStateFlow(prefs.getInt(KEY_ALERT_THRESHOLD, DEFAULT_ALERT_THRESHOLD))
    val alertThresholdDays: StateFlow<Int> = _alertThresholdDays.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _widgetColor = MutableStateFlow(prefs.getInt(KEY_WIDGET_COLOR, DEFAULT_WIDGET_COLOR))
    val widgetColor: StateFlow<Int> = _widgetColor.asStateFlow()

    private val _widgetOpacity = MutableStateFlow(prefs.getInt(KEY_WIDGET_OPACITY, DEFAULT_WIDGET_OPACITY))
    val widgetOpacity: StateFlow<Int> = _widgetOpacity.asStateFlow()

    fun setCheckHour(hour: Int) {
        prefs.edit().putInt(KEY_CHECK_HOUR, hour).apply()
        _checkHour.value = hour
    }

    fun setAlertThresholdDays(days: Int) {
        prefs.edit().putInt(KEY_ALERT_THRESHOLD, days).apply()
        _alertThresholdDays.value = days
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
        _notificationsEnabled.value = enabled
    }

    fun setWidgetColor(color: Int) {
        prefs.edit().putInt(KEY_WIDGET_COLOR, color).apply()
        _widgetColor.value = color
    }

    fun setWidgetOpacity(opacity: Int) {
        prefs.edit().putInt(KEY_WIDGET_OPACITY, opacity.coerceIn(0, 100)).apply()
        _widgetOpacity.value = opacity.coerceIn(0, 100)
    }

    companion object {
        private const val PREFS_NAME = "certcheck_settings"
        private const val KEY_CHECK_HOUR = "check_hour"
        private const val KEY_ALERT_THRESHOLD = "alert_threshold_days"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_WIDGET_COLOR = "widget_color"
        private const val KEY_WIDGET_OPACITY = "widget_opacity"

        const val DEFAULT_CHECK_HOUR = 3
        const val DEFAULT_ALERT_THRESHOLD = 30
        const val DEFAULT_WIDGET_COLOR = 0x000000 // Black (without alpha)
        const val DEFAULT_WIDGET_OPACITY = 85 // 85%
    }
}
