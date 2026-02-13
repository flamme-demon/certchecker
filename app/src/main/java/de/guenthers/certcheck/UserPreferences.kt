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

    companion object {
        private const val PREFS_NAME = "certcheck_settings"
        private const val KEY_CHECK_HOUR = "check_hour"
        private const val KEY_ALERT_THRESHOLD = "alert_threshold_days"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"

        const val DEFAULT_CHECK_HOUR = 3
        const val DEFAULT_ALERT_THRESHOLD = 30
    }
}
