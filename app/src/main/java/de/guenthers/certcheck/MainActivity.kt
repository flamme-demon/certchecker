package de.guenthers.certcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import de.guenthers.certcheck.ui.screens.HomeScreen
import de.guenthers.certcheck.ui.screens.ResultScreen
import de.guenthers.certcheck.ui.theme.CertCheckTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CertCheckTheme {
                CertCheckApp()
            }
        }
    }
}

@Composable
fun CertCheckApp(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.result != null && !uiState.isLoading) {
        ResultScreen(
            result = uiState.result!!,
            onBack = { viewModel.clearResult() },
            onRecheck = { viewModel.checkCertificate() }
        )
    } else {
        HomeScreen(
            uiState = uiState,
            onHostnameChanged = viewModel::onHostnameChanged,
            onCheck = viewModel::checkCertificate,
            onHistoryItemClick = viewModel::loadFromHistory,
        )
    }
}
