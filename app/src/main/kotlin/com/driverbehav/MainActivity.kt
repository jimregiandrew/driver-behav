package com.driverbehav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.driverbehav.sensors.TripService
import com.driverbehav.sensors.TripState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TripScreen()
                }
            }
        }
    }
}

/** Permissions the trip service needs before it can start. */
private val requiredPermissions: Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

@Composable
private fun TripScreen() {
    val context = LocalContext.current
    val state by TripService.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // Fine location is the gate for a location-typed foreground service.
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            TripService.start(context)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (val s = state) {
            is TripState.Idle -> {
                Text("Idle", style = MaterialTheme.typography.headlineSmall)
                Button(
                    modifier = Modifier.padding(top = 24.dp),
                    onClick = {
                        if (hasAllPermissions(context)) {
                            TripService.start(context)
                        } else {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    },
                ) { Text("Start trip") }
            }

            is TripState.Recording -> {
                Text("Recording", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${s.samples} samples",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = s.filePath,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    modifier = Modifier.padding(top = 24.dp),
                    onClick = { TripService.stop(context) },
                ) { Text("Stop trip") }
            }
        }
    }
}

private fun hasAllPermissions(context: android.content.Context): Boolean =
    requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
