package com.example.nfc_smartlock_app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.UUID

class BleClient(private val context: Context) {

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    private val serviceUuid: UUID = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")

    private var onFoundDevice: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun scanAndConnect(
        deviceName: String,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onConnectionFailed: (String) -> Unit
    ) {
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

            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    if (device.name == deviceName) {
                        Log.d(TAG, "Device found: ${device.name}")
                        bluetoothLeScanner?.stopScan(this)
                        connectToDevice(device, onConnected, onDisconnected, onConnectionFailed)
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
            val service: BluetoothGattService? = gatt.getService(serviceUuid)
            if (service == null) {
                Log.e(TAG, "Service UUID $serviceUuid not found.")
                onFailure("Service UUID $serviceUuid not found.")
                return
            }
            val characteristic: BluetoothGattCharacteristic? = service.getCharacteristic(characteristicUuid)
            if (characteristic == null) {
                Log.e(TAG, "Characteristic UUID $characteristicUuid not found.")
                onFailure("Characteristic UUID $characteristicUuid not found.")
                return
            }

            characteristic.value = "unlock".toByteArray(Charsets.UTF_8)
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

    companion object {
        private const val TAG = "BleClient"
    }
}
