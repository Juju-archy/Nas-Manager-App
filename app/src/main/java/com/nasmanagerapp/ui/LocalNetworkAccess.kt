package com.nasmanagerapp.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/*
 * Android 17 "Local Network Protections": an app targeting SDK 37 (this one does) has every
 * connection to a LAN address blocked — surfacing as a plain socket timeout — until the user grants
 * ACCESS_LOCAL_NETWORK. Addresses on the Internet (public domain, VPN) don't need it, so the
 * permission is asked for but never required to attempt a login. See `CONNECTIVITY_TODO.md`.
 */

/** API level where ACCESS_LOCAL_NETWORK exists. Earlier, LAN access is implicitly granted. */
private const val LOCAL_NETWORK_PERMISSION_MIN_SDK = 37

/**
 * Whether the app may currently reach LAN addresses. Always true before Android 17, where the
 * permission doesn't exist — checking it there would always report "denied".
 *
 * Read from Android each time rather than cached: the user can revoke it in the settings, and
 * Android auto-revokes permissions of apps left unused for months.
 */
fun hasLocalNetworkAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_MIN_SDK ||
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
        PackageManager.PERMISSION_GRANTED

@Stable
class LocalNetworkAccessState internal constructor(
    private val context: Context,
    private val launcher: ManagedActivityResultLauncher<String, Boolean>,
    private val granted: MutableState<Boolean>,
    private val canAskAgainState: MutableState<Boolean>,
    private val requestedState: MutableState<Boolean>,
) {
    /** Kept up to date after each request and whenever the screen resumes (e.g. back from the settings). */
    val isGranted: Boolean get() = granted.value

    /** Whether [request] was already called on this screen. */
    val hasRequested: Boolean get() = requestedState.value

    /**
     * Whether [request] would still show the system dialog. Once the user has denied twice,
     * Android denies further requests by itself without showing anything, and the permission can
     * only be granted from the app's settings.
     *
     * Android can't tell "never asked" apart from "denied for good" before a first request, so
     * until [hasRequested], a request is assumed possible.
     */
    val canAskAgain: Boolean get() = !requestedState.value || canAskAgainState.value

    /** Shows the system dialog; the answer goes to the `onResult` given to [rememberLocalNetworkAccess]. */
    fun request() {
        // Never reached before Android 17 in practice: isGranted is always true there.
        if (Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_MIN_SDK) return
        requestedState.value = true
        launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Some customized ROMs lack this standard settings page: fall back to the settings app.
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                // Nothing to open; the notice text still says where to allow it.
            }
        }
    }
}

@Composable
fun rememberLocalNetworkAccess(onResult: (granted: Boolean) -> Unit = {}): LocalNetworkAccessState {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val granted = remember { mutableStateOf(hasLocalNetworkAccess(context)) }
    val canAskAgain = remember { mutableStateOf(false) }
    val requested = rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        granted.value = hasLocalNetworkAccess(context)
        canAskAgain.value = Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_MIN_SDK &&
            context.findActivity()
                ?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK) == true
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
        currentOnResult(granted.value)
    }

    // Covers the return from the app's settings page, where the user may have granted it.
    LifecycleResumeEffect(context) {
        refresh()
        onPauseOrDispose { }
    }

    return remember(context, launcher) {
        LocalNetworkAccessState(context, launcher, granted, canAskAgain, requested)
    }
}

/**
 * Shown after a connection to a NAS on the local network failed because it was unreachable while
 * the local network permission is missing — the likely cause then.
 */
@Composable
fun LocalNetworkAccessNotice(state: LocalNetworkAccessState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .widthIn(max = 400.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Lan, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Local network access is not allowed for this app. It's required to " +
                        "reach a NAS on your local network (e.g. 192.168.x.x).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (state.canAskAgain) {
                OutlinedButton(onClick = state::request) { Text("Allow access") }
            } else {
                Text(
                    text = "Allow it under Permissions → Nearby devices.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = state::openAppSettings) { Text("Open settings") }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
