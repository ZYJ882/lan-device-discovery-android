package com.zyj.lanobserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: LanDiscoveryViewModel = viewModel()
            val state = viewModel.uiState
            val colorScheme = if (isSystemInDarkTheme()) {
                darkColorScheme(
                    primary = Color(0xFF90CAF9),
                    onPrimary = Color(0xFF102235),
                    primaryContainer = Color(0xFF19324F),
                    onPrimaryContainer = Color(0xFFE1F0FF),
                    secondary = Color(0xFF75DAB2),
                    background = Color(0xFF101720),
                    surface = Color(0xFF18232F),
                    onSurface = Color(0xFFE1E8F0)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF185ABC),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFEAF2FF),
                    onPrimaryContainer = Color(0xFF0B356F),
                    secondary = Color(0xFF0B7654),
                    background = Color(0xFFF7F9FC),
                    surface = Color.White,
                    onSurface = Color(0xFF172033)
                )
            }
            MaterialTheme(colorScheme = colorScheme) {
                LanDiscoveryScreen(
                    state = state,
                    onStartScan = viewModel::startScan,
                    onCancelScan = viewModel::cancelScan,
                    onRefreshNetwork = viewModel::refreshNetwork,
                    onFilter = viewModel::filter,
                    onSelectDevice = viewModel::selectDevice,
                    onScanDevicePorts = viewModel::scanDevicePorts,
                    onCancelPortScan = viewModel::cancelPortScan,
                    onStartMonitoring = viewModel::startMonitoring,
                    onStopMonitoring = viewModel::stopMonitoring,
                    onScanLocalHostPorts = viewModel::scanLocalHostPorts,
                    onCancelLocalHostPortScan = viewModel::cancelLocalHostPortScan,
                    onIdentifyDeviceModel = viewModel::identifyDeviceModel,
                    onIdentifyDeviceWithOnvif = viewModel::identifyDeviceWithOnvif,
                    onSyncOuiDatabase = viewModel::syncOuiDatabase
                )
            }
        }
    }
}
