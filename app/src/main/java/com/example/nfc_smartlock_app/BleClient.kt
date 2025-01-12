package com.example.nfc_smartlock_app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.UUID

@Suppress("DEPRECATION")
class BleClient(private val context: Context) {

    companion object {
        private const val TAG = "BleClient"
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    private val serviceUuid: UUID = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")

    /**
     * BLE scan -> connect if the device with the specified name is found.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    fun scanAndConnect(
        deviceName: String,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onConnectionFailed: (String) -> Unit
    ) {
        // Check Bluetooth permissions
        checkBluetoothPermissions()

        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter? = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(context, "Bluetooth is disabled.", Toast.LENGTH_SHORT).show()
                onConnectionFailed("Bluetooth is disabled.")
                return
            }

            bluetoothLeScanner = adapter.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Toast.makeText(context, "Cannot get BluetoothLeScanner.", Toast.LENGTH_SHORT).show()
                onConnectionFailed("Cannot get BluetoothLeScanner.")
                return
            }

            // Define scan callback
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val foundName = device.name ?: "Unknown"
                    val foundAddress = device.address ?: "No Address"
                    Log.d(TAG, "Scan device: name=$foundName address=$foundAddress")

                    if (foundName == deviceName) {
                        Log.d(TAG, "Target device found: $foundName. Stopping scan.")
                        bluetoothLeScanner?.stopScan(this)
                        connectToDevice(device, onConnected, onDisconnected, onConnectionFailed)
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    for (result in results) {
                        val device = result.device
                        val foundName = device.name ?: "Unknown"
                        val foundAddress = device.address ?: "No Address"
                        Log.d(TAG, "BatchScan device: $foundName [$foundAddress]")
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Scan failed with error: $errorCode")
                    onConnectionFailed("Scan failed with error: $errorCode")
                }
            }

            val scanFilter = ScanFilter.Builder()
                .setDeviceName(deviceName)
                .build()
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            Log.d(TAG, "Starting BLE scan for device name: $deviceName")
            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

            // Stop scan after 10 seconds
            handler.postDelayed({
                bluetoothLeScanner?.stopScan(scanCallback)
                Toast.makeText(context, "Scan timed out.", Toast.LENGTH_SHORT).show()
                onConnectionFailed("Scan timed out.")
            }, 10000)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            Toast.makeText(context, "Required permissions are missing.", Toast.LENGTH_SHORT).show()
            onConnectionFailed("SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}")
            Toast.makeText(context, "An error occurred while scanning.", Toast.LENGTH_SHORT).show()
            onConnectionFailed("Exception: ${e.message}")
        }
    }

    /**
     * Connect to the given BluetoothDevice via GATT.
     */
    @SuppressLint("MissingPermission")
    private fun connectToDevice(
        device: BluetoothDevice,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onConnectionFailed: (String) -> Unit
    ) {
        try {
            bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    super.onConnectionStateChange(gatt, status, newState)
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "Connected to GATT server.")
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "Disconnected from GATT server.")
                        gatt.close()
                        bluetoothGatt = null
                        onDisconnected()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    super.onServicesDiscovered(gatt, status)
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Services discovered.")
                        onConnected()
                    } else {
                        Log.e(TAG, "Service discovery failed with status: $status")
                        onConnectionFailed("Service discovery failed with status: $status")
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            Toast.makeText(context, "Required permissions are missing.", Toast.LENGTH_SHORT).show()
            onConnectionFailed("SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}")
            Toast.makeText(context, "An error occurred while connecting.", Toast.LENGTH_SHORT).show()
            onConnectionFailed("Exception: ${e.message}")
        }
    }

    /**
     * Send an "unlock" command to the BLE device.
     */
    @SuppressLint("MissingPermission")
    fun sendUnlockCommand(
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        try {
            val gatt = bluetoothGatt ?: run {
                Log.e(TAG, "BluetoothGatt is null.")
                onFailure("BluetoothGatt is null.")
                return
            }
            val service = gatt.getService(serviceUuid)
            if (service == null) {
                Log.e(TAG, "Service UUID $serviceUuid not found.")
                onFailure("Service UUID $serviceUuid not found.")
                return
            }
            val characteristic = service.getCharacteristic(characteristicUuid)
            if (characteristic == null) {
                Log.e(TAG, "Characteristic UUID $characteristicUuid not found.")
                onFailure("Characteristic UUID $characteristicUuid not found.")
                return
            }

            // Use setValue() instead of the property setter
            characteristic.setValue("unlock".toByteArray(Charsets.UTF_8))
            val result = gatt.writeCharacteristic(characteristic)
            Log.d(TAG, "writeCharacteristic(unlock) result: $result")

            if (!result) {
                onFailure("Failed to write characteristic.")
            } else {
                onSuccess()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            Toast.makeText(context, "Required permissions are missing.", Toast.LENGTH_SHORT).show()
            onFailure("SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}")
            Toast.makeText(context, "An error occurred while sending command.", Toast.LENGTH_SHORT).show()
            onFailure("Exception: ${e.message}")
        }
    }

    /**
     * Disconnect from the BLE device.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.close()
            bluetoothGatt = null
            Log.d(TAG, "Disconnected from BLE device.")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during disconnect: ${e.message}")
        }
    }

    /**
     * Check Bluetooth permissions and log the results.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkBluetoothPermissions() {
        val scanPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
        val connectPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)

        if (scanPerm == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "BLUETOOTH_SCAN permission GRANTED")
        } else {
            Log.d(TAG, "BLUETOOTH_SCAN permission NOT granted")
        }

        if (connectPerm == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "BLUETOOTH_CONNECT permission GRANTED")
        } else {
            Log.d(TAG, "BLUETOOTH_CONNECT permission NOT granted")
        }
    }
}