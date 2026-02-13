package de.guenthers.certcheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.guenthers.certcheck.model.CertCheckResult
import de.guenthers.certcheck.network.SSLChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val hostname: String = "",
    val isLoading: Boolean = false,
    val result: CertCheckResult? = null,
    val history: List<CertCheckResult> = emptyList(),
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun onHostnameChanged(hostname: String) {
        _uiState.value = _uiState.value.copy(hostname = hostname)
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
    }
}
