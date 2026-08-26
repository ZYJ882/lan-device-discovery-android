package com.zyj.lanobserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: LanDiscoveryViewModel = viewModel()
            val state = viewModel.uiState
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF185ABC),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFEAF2FF),
                    onPrimaryContainer = Color(0xFF0B356F),
                    secondary = Color(0xFF0B7654),
                    background = Color(0xFFF7F9FC),
                    surface = Color.White,
                    onSurface = Color(0xFF172033)
                )
            ) {
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
