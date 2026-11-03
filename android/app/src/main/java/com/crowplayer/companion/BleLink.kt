package com.crowplayer.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.UUID

/** BLE GATT client for the CrowPlayer metadata write characteristic. */
class BleLink(private val context: Context, private val onStatus: (String) -> Unit) {
    companion object {
        val SERVICE: UUID = UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e")
        val META: UUID = UUID.fromString("7d2ea28b-f7bd-485a-bd9d-92ad6ecfe93e")
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var mtuReady = false
    private var writeInFlight = false
    private var latestPayload = ""
    private var sentPayload = ""

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus("CrowPanel найден — настраиваем BLE…")
                @SuppressLint("MissingPermission")
                run { g.requestMtu(247) }
                g.discoverServices()
            } else {
                characteristic = null
                mtuReady = false
                writeInFlight = false
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                onStatus("CrowPanel отключён. Повторный поиск…")
                startScan()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuReady = true // Default MTU also works for short metadata payloads.
            flushLatest()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            characteristic = service?.getCharacteristic(META)
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                onStatus("Связь с CrowPanel установлена")
                if (mtuReady) flushLatest()
            } else {
                onStatus("CrowPanel найден, но сервис CrowPlayer не найден")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeInFlight = false
            if (status == BluetoothGatt.GATT_SUCCESS) sentPayload = String(c.value ?: byteArrayOf(), Charsets.UTF_8)
            else onStatus("Ошибка передачи данных на CrowPanel ($status)")
            flushLatest()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Включи Bluetooth на планшете")
            return
        }
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return
        if (scanning || gatt != null) return
        try {
            // The tablet is already connected to the same ESP32 as a HID host.
            // Scan by advertised name instead of a 128-bit UUID filter: HID + custom
            // service UUIDs may not both fit into the 31-byte legacy advertisement.
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanning = true
            scanner.startScan(null, settings, scanCallback)
            onStatus("Ищем CrowPanel по Bluetooth…")
        } catch (_: SecurityException) {
            onStatus("Нет разрешения на поиск Bluetooth-устройств")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val advertisedName = device.name ?: result.scanRecord?.deviceName
            if (advertisedName != "CrowPlayer") return
            stopScan()
            onStatus("Подключаемся к CrowPanel…")
            gatt = if (Build.VERSION.SDK_INT >= 23)
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, false, callback)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onStatus("Ошибка Bluetooth-сканирования: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }

    fun send(title: String, artist: String, playback: String) {
        val state = if (playback.equals("playing", true) || playback.contains("Воспроизводится", true)) "playing" else "paused"
        latestPayload = JSONObject()
            .put("title", title.take(64))
            .put("artist", artist.take(64))
            .put("state", state)
            .toString()
        flushLatest()
    }

    @SuppressLint("MissingPermission")
    private fun flushLatest() {
        val g = gatt ?: return
        val c = characteristic ?: return
        if (!mtuReady || writeInFlight || latestPayload.isBlank() || latestPayload == sentPayload) return
        try {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = latestPayload.toByteArray(Charsets.UTF_8)
            writeInFlight = g.writeCharacteristic(c)
        } catch (_: Exception) {
            writeInFlight = false
            onStatus("Не удалось отправить данные на CrowPanel")
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        characteristic = null
        mtuReady = false
        writeInFlight = false
    }
}
