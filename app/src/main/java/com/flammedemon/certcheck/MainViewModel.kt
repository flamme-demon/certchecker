package com.flammedemon.certcheck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flammedemon.certcheck.database.CertCheckDatabase
import com.flammedemon.certcheck.database.CertCheckRepository
import com.flammedemon.certcheck.database.CheckHistoryEntity
import com.flammedemon.certcheck.database.FavoriteEntity
import com.flammedemon.certcheck.model.CertCheckResult
import com.flammedemon.certcheck.network.SSLChecker
import com.flammedemon.certcheck.widget.CertCheckWidgetProvider
import com.flammedemon.certcheck.worker.DailyCertificateCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val hostname: String = "",
    val isLoading: Boolean = false,
    val result: CertCheckResult? = null,
    val isFavorite: Boolean = false,
    val isRefreshingFavorites: Boolean = false,
    val refreshProgress: Int = 0,
    val refreshTotal: Int = 0,
    val refreshingFavoriteIds: Set<Long> = emptySet(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = CertCheckDatabase.getDatabase(application)
    private val repository = CertCheckRepository(database)
    val preferences = UserPreferences(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val favorites: StateFlow<List<FavoriteEntity>> = repository.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val checkHistory: StateFlow<List<CheckHistoryEntity>> = repository.getAllRecentHistory(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val latestChecks: StateFlow<List<CheckHistoryEntity>> = repository.getLatestCheckPerFavorite()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        DailyCertificateCheckWorker.schedule(application, preferences.checkHour.value, preferences.checkMinute.value)
        refreshAllFavorites()
    }

    fun refreshAllFavorites() {
        viewModelScope.launch(Dispatchers.IO) {
            val favs = repository.getAllFavorites().first()
            if (favs.isEmpty()) return@launch

            _uiState.value = _uiState.value.copy(
                isRefreshingFavorites = true,
                refreshProgress = 0,
                refreshTotal = favs.size,
            )

            favs.forEachIndexed { index, favorite ->
                try {
                    repository.checkAndSaveResult(favorite.id)
                } catch (_: Exception) {
                    // Silently ignore errors during background refresh
                }
                _uiState.value = _uiState.value.copy(refreshProgress = index + 1)
            }

            _uiState.value = _uiState.value.copy(isRefreshingFavorites = false)
            CertCheckWidgetProvider.updateAll(getApplication())
        }
    }

    fun onHostnameChanged(hostname: String) {
        _uiState.value = _uiState.value.copy(hostname = hostname)
        checkIfFavorite()
    }

    private fun checkIfFavorite() {
        viewModelScope.launch(Dispatchers.IO) {
            val (hostname, port) = parseHostnameAndPort(_uiState.value.hostname)
            if (hostname.isNotBlank()) {
                val isFav = repository.isFavorite(hostname, port)
                _uiState.value = _uiState.value.copy(isFavorite = isFav)
            }
        }
    }

    fun checkCertificate() {
        val hostname = _uiState.value.hostname.trim()
        if (hostname.isBlank()) return

        _uiState.value = _uiState.value.copy(isLoading = true, result = null)

        viewModelScope.launch(Dispatchers.IO) {
            val result = SSLChecker.check(hostname)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                result = result,
            )
            repository.saveCheck(result)
            checkIfFavorite()
        }
    }

    fun toggleFavorite() {
        val hostname = _uiState.value.hostname.trim()
        if (hostname.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val (host, port) = parseHostnameAndPort(hostname)
            val isFav = repository.isFavorite(host, port)
            if (isFav) {
                val fav = repository.getAllFavorites().stateIn(viewModelScope).value
                    .find { it.hostname == host && it.port == port }
                fav?.let { repository.removeFavorite(it.id) }
            } else {
                repository.addFavorite(host, port)
            }
            checkIfFavorite()
        }
    }

    fun addToFavorites(hostname: String, port: Int = 443) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addFavorite(hostname, port)
        }
    }

    fun removeFavorite(favoriteId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeFavorite(favoriteId)
        }
    }

    fun toggleFavoriteNotifications(favoriteId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleFavoriteNotifications(favoriteId)
        }
    }

    fun refreshFavorite(favoriteId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                refreshingFavoriteIds = _uiState.value.refreshingFavoriteIds + favoriteId
            )
            try {
                repository.checkAndSaveResult(favoriteId)
            } catch (_: Exception) {
                // Silently ignore errors during refresh
            } finally {
                _uiState.value = _uiState.value.copy(
                    refreshingFavoriteIds = _uiState.value.refreshingFavoriteIds - favoriteId
                )
            }
        }
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(result = null)
    }

    fun checkDomain(hostname: String, port: Int = 443) {
        val hostWithPort = if (port == 443) hostname else "$hostname:$port"
        _uiState.value = _uiState.value.copy(hostname = hostWithPort)
        checkCertificate()
    }

    fun selectFavoriteHostname(favorite: FavoriteEntity) {
        val hostWithPort = if (favorite.port == 443) {
            favorite.hostname
        } else {
            "${favorite.hostname}:${favorite.port}"
        }
        _uiState.value = _uiState.value.copy(hostname = hostWithPort)
        checkIfFavorite()
    }

    fun updateCheckTime(hour: Int, minute: Int) {
        preferences.setCheckHour(hour)
        preferences.setCheckMinute(minute)
        DailyCertificateCheckWorker.reschedule(getApplication(), hour, minute)
    }

    fun updateAlertThreshold(days: Int) {
        preferences.setAlertThresholdDays(days)
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        preferences.setNotificationsEnabled(enabled)
    }

    fun updateWidgetColor(color: Int) {
        preferences.setWidgetColor(color)
        CertCheckWidgetProvider.updateAll(getApplication())
    }

    fun updateWidgetOpacity(opacity: Int) {
        preferences.setWidgetOpacity(opacity)
        CertCheckWidgetProvider.updateAll(getApplication())
    }

    private fun parseHostnameAndPort(input: String): Pair<String, Int> {
        val cleanInput = input
            .removePrefix("https://")
            .removePrefix("http://")
            .trim()

        val defaultPort = 443
        val pathIndex = cleanInput.indexOf('/')
        val hostPart = if (pathIndex > 0) cleanInput.substring(0, pathIndex) else cleanInput

        val portIndex = hostPart.lastIndexOf(':')
        return if (portIndex > 0 && portIndex < hostPart.length - 1) {
            val host = hostPart.substring(0, portIndex)
            val port = hostPart.substring(portIndex + 1).toIntOrNull() ?: defaultPort
            Pair(host, port)
        } else {
            Pair(hostPart, defaultPort)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                MainViewModel(application)
            }
        }
    }
}
