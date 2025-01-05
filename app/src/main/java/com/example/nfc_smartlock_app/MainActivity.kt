package com.example.nfc_smartlock_app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.nfc_smartlock_app.ui.theme.Nfc_smartlock_appTheme

class MainActivity : ComponentActivity() {

    private val bleClient by lazy { BleClient(this) }

    // Permission request launcher
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.d("MainActivity", "All permissions granted.")
            // If launched by NFC, initiate BLE connection
            if (isLaunchedByNfc) {
                unlockByBle()
            }
        } else {
            Toast.makeText(this, "Required permissions were denied. Exiting app.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Flag to determine if app was launched by NFC
    private var isLaunchedByNfc = false

    // UID obtained from NFC tag
    private var uid: String? = null

    // UI state for displaying status messages
    private var statusMessage by mutableStateOf("Ready")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle intent to check if launched via NFC
        handleIntent(intent)

        setContent {
            Nfc_smartlock_appTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    content = { padding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                )
            }
        }

        // Check and request permissions
        if (!arePermissionsGranted()) {
            requestPermissions()
        } else {
            // If permissions are already granted and launched by NFC, initiate BLE connection
            if (isLaunchedByNfc && uid != null) {
                unlockByBle()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Handles the intent to determine if launched by NFC and retrieves UID.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED
        ) {
            Log.d("MainActivity", "App launched via NFC tag.")
            isLaunchedByNfc = true

            // Retrieve UID from the NFC tag
            val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            if (rawMsgs != null && rawMsgs.isNotEmpty()) {
                val ndefMsg = rawMsgs[0] as android.nfc.NdefMessage
                val records = ndefMsg.records
                for (record in records) {
                    if (record.tnf == android.nfc.NdefRecord.TNF_WELL_KNOWN &&
                        record.type.contentEquals(android.nfc.NdefRecord.RTD_TEXT)
                    ) {
                        val payload = String(record.payload, Charsets.UTF_8)
                        // Assuming payload is in format "unlock/UID"
                        val parts = payload.split("/")
                        if (parts.size == 2 && parts[0] == "unlock") {
                            uid = parts[1]
                            statusMessage = "NFC tag detected. Initiating unlock..."
                            Log.d("MainActivity", "UID obtained from NFC tag: $uid")
                            break
                        }
                    }
                }
            }

            // If UID is obtained and permissions are granted, initiate BLE connection
            if (uid != null && arePermissionsGranted()) {
                unlockByBle()
            } else if (uid == null) {
                statusMessage = "Failed to retrieve UID from NFC tag."
                Log.e("MainActivity", "UID not found in NFC tag.")
            }
        } else {
            isLaunchedByNfc = false
            statusMessage = "App launched normally."
        }
    }

    /**
     * Initiates BLE connection and sends the unlock command.
     */
    private fun unlockByBle() {
        statusMessage = "Attempting to connect to ESP32 via BLE..."
        bleClient.connectToDevice(
            deviceName = "ESP32_SMART_LOCK",
            onConnected = {
                runOnUiThread {
                    statusMessage = "Connected to ESP32. Sending unlock command..."
                }
                bleClient.sendUnlockCommand(
                    onSuccess = {
                        runOnUiThread {
                            statusMessage = "Unlock command sent successfully."
                        }
                    },
                    onFailure = { error ->
                        runOnUiThread {
                            statusMessage = "Failed to send unlock command: $error"
                        }
                    }
                )
            },
            onDisconnected = {
                runOnUiThread {
                    statusMessage = "Disconnected from ESP32."
                }
            },
            onConnectionFailed = { error ->
                runOnUiThread {
                    statusMessage = "Failed to connect to ESP32: $error"
                }
            }
        )
    }

    /**
     * Checks if all required permissions are granted.
     */
    private fun arePermissionsGranted(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        permissions.add(Manifest.permission.NFC)

        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Requests the necessary permissions.
     */
    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        permissionsToRequest.add(Manifest.permission.NFC)

        requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
    }
}
