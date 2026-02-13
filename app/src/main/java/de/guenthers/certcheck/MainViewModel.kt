package de.guenthers.certcheck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.ViewModelFactory
import de.guenthers.certcheck.database.CertCheckDatabase
import de.guenthers.certcheck.database.CertCheckRepository
import de.guenthers.certcheck.database.CheckHistoryEntity
import de.guenthers.certcheck.database.FavoriteEntity
import de.guenthers.certcheck.model.CertCheckResult
import de.guenthers.certcheck.network.SSLChecker
import de.guenthers.certcheck.worker.DailyCertificateCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val hostname: String = "",
    val isLoading: Boolean = false,
    val result: CertCheckResult? = null,
    val history: List<CertCheckResult> = emptyList(),
    val isFavorite: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = CertCheckDatabase.getDatabase(application)
    private val repository = CertCheckRepository(database)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val favorites: StateFlow<List<FavoriteEntity>> = repository.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val checkHistory: StateFlow<List<CheckHistoryEntity>> = repository.getAllRecentHistory(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        DailyCertificateCheckWorker.schedule(application)
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
                history = listOf(result) + _uiState.value.history.take(19),
            )
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

    fun refreshFavorite(favoriteId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.checkAndSaveResult(favoriteId)
        }
    }

    fun clearResult() {
        _uiState.value = _uiState.value.copy(result = null)
    }

    fun loadFromHistory(result: CertCheckResult) {
        _uiState.value = _uiState.value.copy(
            hostname = result.hostname,
            result = result,
        )
        checkIfFavorite()
    }

    fun loadFromFavorite(favorite: FavoriteEntity) {
        val hostWithPort = if (favorite.port == 443) {
            favorite.hostname
        } else {
            "${favorite.hostname}:${favorite.port}"
        }
        _uiState.value = _uiState.value.copy(hostname = hostWithPort)
        checkCertificate()
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
        val Factory: ViewModelFactory = object : ViewModelFactory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                return MainViewModel(extras[APPLICATION_KEY] as Application) as T
            }
        }
    }
}
