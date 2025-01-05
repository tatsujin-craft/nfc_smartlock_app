package com.example.nfc_smartlock_app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var connectButton: Button
    private lateinit var unlockButton: Button
    private lateinit var statusTextView: TextView

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private var bleClient: BleClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        connectButton = findViewById(R.id.connectButton)
        unlockButton = findViewById(R.id.unlockButton)
        statusTextView = findViewById(R.id.statusTextView)

        // Initialize BLE client
        bleClient = BleClient(this)

        // Set click listeners
        connectButton.setOnClickListener {
            connectToBleDevice()
        }

        unlockButton.setOnClickListener {
            sendUnlockCommand()
        }

        // Disable Unlock button until connected
        unlockButton.isEnabled = false

        // Check and request permissions
        if (!arePermissionsGranted()) {
            requestPermissions()
        }
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

        ActivityCompat.requestPermissions(
            this,
            permissionsToRequest.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Handles the result of permission requests.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.d(TAG, "All permissions granted.")
                Toast.makeText(this, "All permissions granted.", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "Required permissions were denied.")
                Toast.makeText(this, "Required permissions were denied.", Toast.LENGTH_LONG).show()
                // Disable functionality that depends on these permissions.
                connectButton.isEnabled = false
                unlockButton.isEnabled = false
            }
        }
    }

    /**
     * Initiates connection to the BLE device.
     */
    private fun connectToBleDevice() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Bluetooth is disabled.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Bluetooth is disabled.")
            return
        }

        statusTextView.text = "Connecting to ESP_SMART_LOCK..."
        Log.d(TAG, "Attempting to connect to ESP_SMART_LOCK.")

        bleClient?.connectToDevice(
            deviceName = "ESP_SMART_LOCK",
            onConnected = {
                runOnUiThread {
                    statusTextView.text = "Connected to ESP_SMART_LOCK."
                    unlockButton.isEnabled = true
                }
                Log.d(TAG, "Successfully connected to ESP_SMART_LOCK.")
            },
            onDisconnected = {
                runOnUiThread {
                    statusTextView.text = "Disconnected from ESP_SMART_LOCK."
                    unlockButton.isEnabled = false
                }
                Log.d(TAG, "Disconnected from ESP_SMART_LOCK.")
            },
            onConnectionFailed = { error ->
                runOnUiThread {
                    statusTextView.text = "Failed to connect: $error"
                }
                Log.e(TAG, "Failed to connect: $error")
            }
        )
    }

    /**
     * Sends the "unlock" command to the BLE server.
     */
    private fun sendUnlockCommand() {
        statusTextView.text = "Sending unlock command..."
        Log.d(TAG, "Sending unlock command to ESP_SMART_LOCK.")

        bleClient?.sendUnlockCommand(
            onSuccess = {
                runOnUiThread {
                    statusTextView.text = "Unlock command sent successfully."
                }
                Log.d(TAG, "Unlock command sent successfully.")
            },
            onFailure = { error ->
                runOnUiThread {
                    statusTextView.text = "Failed to send unlock command: $error"
                }
                Log.e(TAG, "Failed to send unlock command: $error")
            }
        )
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "MainActivity"
    }
}
