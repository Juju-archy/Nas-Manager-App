package com.nasmanagerapp.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nasmanagerapp.data.dashboard.BootInfo
import com.nasmanagerapp.data.dashboard.NetworkInfo
import com.nasmanagerapp.data.dashboard.NetworkInterfaceInfo
import com.nasmanagerapp.data.dashboard.PoolScan
import com.nasmanagerapp.data.dashboard.SystemGeneralSettings

/**
 * "System" screen, reachable from the drawer's "System" entry — three independent sections
 * (General settings/Network/Boot), each its own `Card`, loaded together on entry and on manual
 * refresh (see [DashboardViewModel.loadSystemSettings]). Not polled, like Alerts/Apps. Curl-verified
 * API formats and the reason each section is read-only-display are in `SYSTEM_TODO.md`.
 */
@Composable
fun SystemRoute(
    uiState: DashboardUiState,
    onLoadSystemSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLoadSystemSettings() }
    SystemScreen(
        systemGeneral = uiState.systemGeneral,
        isLoadingSystemGeneral = uiState.isLoadingSystemGeneral,
        systemGeneralError = uiState.systemGeneralError,
        networkInfo = uiState.networkInfo,
        isLoadingNetworkInfo = uiState.isLoadingNetworkInfo,
        networkInfoError = uiState.networkInfoError,
        bootInfo = uiState.bootInfo,
        isLoadingBootInfo = uiState.isLoadingBootInfo,
        bootInfoError = uiState.bootInfoError,
        onRefresh = onLoadSystemSettings,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    systemGeneral: SystemGeneralSettings?,
    isLoadingSystemGeneral: Boolean,
    systemGeneralError: String?,
    networkInfo: NetworkInfo?,
    isLoadingNetworkInfo: Boolean,
    networkInfoError: String?,
    bootInfo: BootInfo?,
    isLoadingBootInfo: Boolean,
    bootInfoError: String?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("System") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "General settings") {
                when {
                    systemGeneral != null -> {
                        InfoRow("Timezone", systemGeneral.timezone)
                        InfoRow("Keyboard", systemGeneral.keyboardLayout)
                        InfoRow("HTTP port", "${systemGeneral.httpPort}")
                        InfoRow("HTTPS port", "${systemGeneral.httpsPort}")
                        InfoRow("HTTPS redirect", if (systemGeneral.httpsRedirect) "Yes" else "No")
                        InfoRow("HTTPS protocols", systemGeneral.httpsProtocols.joinToString(", "))
                    }
                    isLoadingSystemGeneral -> LoadingRow()
                    else -> ErrorRow(message = systemGeneralError ?: "Information unavailable.", onRetry = null)
                }
            }

            SectionCard(title = "Network") {
                when {
                    networkInfo != null -> NetworkSection(networkInfo)
                    isLoadingNetworkInfo -> LoadingRow()
                    else -> ErrorRow(message = networkInfoError ?: "Information unavailable.", onRetry = null)
                }
            }

            SectionCard(title = "Boot") {
                when {
                    bootInfo != null -> BootSection(bootInfo)
                    isLoadingBootInfo -> LoadingRow()
                    else -> ErrorRow(message = bootInfoError ?: "Information unavailable.", onRetry = null)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun NetworkSection(network: NetworkInfo) {
    InfoRow("Hostname", network.hostname)
    InfoRow("Domain", network.domain)
    network.ipv4Gateway?.let { InfoRow("IPv4 gateway", it) }
    network.ipv6Gateway?.let { InfoRow("IPv6 gateway", it) }
    if (network.nameservers.isNotEmpty()) {
        InfoRow("DNS", network.nameservers.joinToString(", "))
    }
    network.interfaces.forEach { iface ->
        Spacer(Modifier.height(12.dp))
        InterfaceRow(iface)
    }
}

@Composable
private fun InterfaceRow(iface: NetworkInterfaceInfo) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(healthy = iface.linkUp)
            Spacer(Modifier.width(8.dp))
            Text(iface.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(
                iface.type,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfoRow("DHCP", if (iface.dhcp) "Yes" else "No")
        if (iface.addresses.isNotEmpty()) {
            InfoRow("Addresses", iface.addresses.joinToString(", "))
        }
    }
}

@Composable
private fun BootSection(boot: BootInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(healthy = boot.poolHealthy)
        Spacer(Modifier.width(8.dp))
        Text(
            boot.poolName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            boot.poolStatus,
            style = MaterialTheme.typography.labelLarge,
            color = if (boot.poolHealthy) HealthyColor else MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(8.dp))
    InfoRow("Size", formatBytes(boot.sizeBytes))
    InfoRow("Allocated", formatBytes(boot.allocatedBytes))

    Spacer(Modifier.height(12.dp))
    Text("Last scrub", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    BootScanInfo(boot.lastScan)

    if (boot.environments.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Boot environments", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        boot.environments.forEach { env ->
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    env.id,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (env.active) FontWeight.Bold else FontWeight.Normal,
                    color = if (env.active) HealthyColor else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    formatBytes(env.usedBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BootScanInfo(scan: PoolScan?) {
    if (scan == null || scan.endEpochSeconds == null) {
        Text(
            "No scrub performed yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    InfoRow("Date", formatEpochSeconds(scan.endEpochSeconds))
    scan.durationSeconds?.let {
        Spacer(Modifier.height(4.dp))
        InfoRow("Duration", formatDurationLong(it))
    }
    Spacer(Modifier.height(4.dp))
    InfoRow("Errors", "${scan.errors}")
}
