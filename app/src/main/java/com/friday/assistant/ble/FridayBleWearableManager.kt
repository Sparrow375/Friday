package com.friday.assistant.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.friday.assistant.core.FridayLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Manages Bluetooth Low Energy (BLE) connection to the Friday Wearable detection device (Pi Zero 2 W).
 * Handles:
 * 1. Automatic background scanning and reconnection
 * 2. High MTU negotiation (512) for ultra-fast audio packet transfer
 * 3. Wake-word detection alerts from wearable (< 15ms)
 * 4. Chunked IMA-ADPCM audio packet reception and decoding to 16kHz PCM
 */
class FridayBleWearableManager private constructor(private val context: Context) {

    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED
    }

    companion object {
        private const val TAG = "FridayBleWearable"

        val SERVICE_UUID: UUID = UUID.fromString("1F81DA00-B5A3-F393-E0A9-E50E24DCCA9E")
        val STATE_CHAR_UUID: UUID = UUID.fromString("1F81DA01-B5A3-F393-E0A9-E50E24DCCA9E")
        val COMMAND_CHAR_UUID: UUID = UUID.fromString("1F81DA02-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val DEVICE_NAME = "Friday-Wearable"

        @Volatile
        private var instance: FridayBleWearableManager? = null

        fun getInstance(context: Context): FridayBleWearableManager {
            return instance ?: synchronized(this) {
                instance ?: FridayBleWearableManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private var activeGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isEnabled = false

    // Incoming audio packet reassembly
    private var expectedChunks = 0
    private var expectedBytes = 0
    private var sampleRate = 16000
    private val audioBufferAccumulator = ByteArrayOutputStream()

    // Callbacks for Friday Service
    var onWakeWordDetected: (() -> Unit)? = null
    var onCommandAudioReceived: ((FloatArray) -> Unit)? = null
    var onCommandTextReceived: ((String) -> Unit)? = null

    fun setEnabled(enabled: Boolean) {
        FridayLogger.i(TAG, "setEnabled called: $enabled (current: $isEnabled)")
        isEnabled = enabled
        if (enabled) {
            startScan()
        } else {
            disconnect()
        }
    }

    fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connect = ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            return scan && connect
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!isEnabled) return
        if (!hasPermissions()) {
            FridayLogger.w(TAG, "Cannot start scan: missing Bluetooth permissions")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            FridayLogger.w(TAG, "Bluetooth adapter is disabled or unavailable")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        if (isScanning || _connectionState.value == ConnectionState.CONNECTED) {
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            FridayLogger.e(TAG, "BluetoothLeScanner is null")
            return
        }

        FridayLogger.i(TAG, "Starting BLE scan for '$DEVICE_NAME' ($SERVICE_UUID)...")
        _connectionState.value = ConnectionState.SCANNING

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        try {
            scanner.startScan(filters, settings, scanCallback)
            // Timeout scan after 30 seconds to save power, retry later
            mainHandler.postDelayed(scanTimeoutRunnable, 30000L)
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Exception starting BLE scan", e)
            isScanning = false
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private val scanTimeoutRunnable = Runnable {
        if (isScanning && _connectionState.value == ConnectionState.SCANNING) {
            FridayLogger.d(TAG, "Scan window timed out. Pausing scan before retry...")
            stopScan()
            if (isEnabled) {
                mainHandler.postDelayed({ startScan() }, 10000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (isScanning) {
            isScanning = false
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                FridayLogger.w(TAG, "Error stopping scan: ${e.message}", e)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName
            FridayLogger.i(TAG, "Found target BLE device: $name (${device.address})")

            stopScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            FridayLogger.e(TAG, "BLE scan failed with error code: $errorCode")
            isScanning = false
            _connectionState.value = ConnectionState.DISCONNECTED
            if (isEnabled) {
                mainHandler.postDelayed({ startScan() }, 8000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        FridayLogger.i(TAG, "Connecting to GATT device: ${device.address}")
        _connectionState.value = ConnectionState.CONNECTING
        _deviceName.value = device.name ?: DEVICE_NAME

        activeGatt?.close()
        activeGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (e: Exception) {
            FridayLogger.w(TAG, "Error closing GATT: ${e.message}", e)
        }
        activeGatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceName.value = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            FridayLogger.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                FridayLogger.i(TAG, "Connected to Friday-Wearable GATT server. Requesting MTU 512...")
                _connectionState.value = ConnectionState.CONNECTED
                gatt?.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                FridayLogger.w(TAG, "Disconnected from Friday-Wearable GATT server.")
                _connectionState.value = ConnectionState.DISCONNECTED
                activeGatt?.close()
                activeGatt = null

                if (isEnabled) {
                    FridayLogger.i(TAG, "Scheduling reconnection scan in 4 seconds...")
                    mainHandler.postDelayed({ startScan() }, 4000L)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            FridayLogger.i(TAG, "BLE MTU changed to: $mtu (status: $status). Discovering services...")
            gatt?.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                FridayLogger.e(TAG, "Service discovery failed with status: $status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                FridayLogger.e(TAG, "Service $SERVICE_UUID not found on device!")
                return
            }

            FridayLogger.i(TAG, "Found Friday Wearable Service! Subscribing to characteristics...")

            // Subscribe to State characteristic (0x01 = Wake detected)
            val stateChar = service.getCharacteristic(STATE_CHAR_UUID)
            if (stateChar != null) {
                enableNotifications(gatt, stateChar)
            }

            // Subscribe to Command characteristic (Audio/text payload)
            mainHandler.postDelayed({
                val commandChar = service.getCharacteristic(COMMAND_CHAR_UUID)
                if (commandChar != null) {
                    enableNotifications(gatt, commandChar)
                }
            }, 300L)
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val registered = gatt.setCharacteristicNotification(characteristic, true)
            FridayLogger.d(TAG, "setCharacteristicNotification on ${characteristic.uuid}: $registered")

            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                FridayLogger.d(TAG, "Wrote CCCD notification descriptor for: ${characteristic.uuid}")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            handleCharacteristicUpdate(characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicBytes(characteristic.uuid, value)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleCharacteristicUpdate(characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        val value = characteristic.value ?: return
        handleCharacteristicBytes(characteristic.uuid, value)
    }

    private fun handleCharacteristicBytes(uuid: UUID, value: ByteArray) {
        if (value.isEmpty()) return

        when (uuid) {
            STATE_CHAR_UUID -> {
                val stateByte = value[0].toInt() and 0xFF
                FridayLogger.i(TAG, "Received Wearable STATE update: 0x%02X".format(stateByte))
                when (stateByte) {
                    0x01 -> {
                        // Wake-word detected on wearable!
                        FridayLogger.i(TAG, "★ Wearable reported wake-word trigger 'Friday'!")
                        mainHandler.post {
                            onWakeWordDetected?.invoke()
                        }
                    }
                    0x02 -> {
                        FridayLogger.d(TAG, "Wearable is recording command...")
                    }
                    0x00 -> {
                        FridayLogger.d(TAG, "Wearable is idle.")
                    }
                }
            }

            COMMAND_CHAR_UUID -> {
                val packetType = value[0].toInt() and 0xFF
                when (packetType) {
                    0x00 -> {
                        // Direct text command (e.g., if Pi did ASR locally)
                        val text = String(value, 1, value.size - 1, Charsets.UTF_8).trim()
                        FridayLogger.i(TAG, "Received direct text command from wearable: '$text'")
                        mainHandler.post {
                            onCommandTextReceived?.invoke(text)
                        }
                    }

                    0x01 -> {
                        // Audio Header: 0x01 | totalChunks (2B) | totalBytes (4B) | sampleRate (2B)
                        if (value.size >= 9) {
                            expectedChunks = ((value[1].toInt() and 0xFF) shl 8) or (value[2].toInt() and 0xFF)
                            expectedBytes = ((value[3].toInt() and 0xFF) shl 24) or
                                    ((value[4].toInt() and 0xFF) shl 16) or
                                    ((value[5].toInt() and 0xFF) shl 8) or
                                    (value[6].toInt() and 0xFF)
                            sampleRate = ((value[7].toInt() and 0xFF) shl 8) or (value[8].toInt() and 0xFF)

                            audioBufferAccumulator.reset()
                            FridayLogger.i(TAG, "Incoming Wearable Audio: $expectedChunks chunks, $expectedBytes bytes @ ${sampleRate}Hz")
                        }
                    }

                    0x02 -> {
                        // Audio Chunk: 0x02 | chunkIndex (2B) | chunkPayload
                        if (value.size > 3) {
                            val chunkIdx = ((value[1].toInt() and 0xFF) shl 8) or (value[2].toInt() and 0xFF)
                            audioBufferAccumulator.write(value, 3, value.size - 3)
                        }
                    }

                    0x03 -> {
                        // Audio End: 0x03 -> Decode and dispatch!
                        val compressedBytes = audioBufferAccumulator.toByteArray()
                        FridayLogger.i(TAG, "Wearable audio transmission complete: received ${compressedBytes.size} compressed bytes")

                        scope.launch(Dispatchers.Default) {
                            val decodedSamples = ImaAdpcmDecoder.decode(compressedBytes)
                            FridayLogger.i(TAG, "Decoded IMA ADPCM -> ${decodedSamples.size} float PCM samples (${decodedSamples.size / 16000.0f}s)")
                            mainHandler.post {
                                onCommandAudioReceived?.invoke(decodedSamples)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * High-performance, zero-dependency IMA-ADPCM 4-bit audio decoder in Kotlin.
 * Expands compressed 4-bit audio frames back into standard 16-bit PCM float samples [-1.0f, 1.0f].
 */
object ImaAdpcmDecoder {
    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    fun decode(adpcmData: ByteArray): FloatArray {
        val out = FloatArray(adpcmData.size * 2)
        var outIdx = 0
        var valprev = 0
        var index = 0

        for (b in adpcmData) {
            val byteVal = b.toInt() and 0xFF
            val nibble1 = byteVal and 0x0F
            val nibble2 = (byteVal shr 4) and 0x0F

            for (delta in intArrayOf(nibble1, nibble2)) {
                val step = STEP_TABLE[index]
                var vpdiff = step shr 3
                if ((delta and 4) != 0) vpdiff += step
                if ((delta and 2) != 0) vpdiff += (step shr 1)
                if ((delta and 1) != 0) vpdiff += (step shr 2)

                if ((delta and 8) != 0) {
                    valprev -= vpdiff
                } else {
                    valprev += vpdiff
                }

                if (valprev > 32767) valprev = 32767
                else if (valprev < -32768) valprev = -32768

                index += INDEX_TABLE[delta]
                if (index < 0) index = 0
                else if (index > 88) index = 88

                out[outIdx++] = valprev / 32768.0f
            }
        }
        return out
    }
}
