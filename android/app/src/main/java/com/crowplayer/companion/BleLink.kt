package com.crowplayer.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
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
        val ART: UUID = UUID.fromString("7d2ea28c-f7bd-485a-bd9d-92ad6ecfe93e")
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var artCharacteristic: BluetoothGattCharacteristic? = null
    private val artQueue = ArrayDeque<ByteArray>()
    private var artWriteInFlight = false
    private var lastArtworkKey = 0
    private var scanning = false
    private var mtuReady = false
    private var negotiatedMtu = 23
    private var writeInFlight = false
    private var latestPayload = ""
    private var sentPayload = ""
    @Volatile private var nightMode = false
    @Volatile private var screenBrightness = 75
    @Volatile private var ledBrightness = 42

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
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            mtuReady = true
            flushLatest(); flushArtwork()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            characteristic = service?.getCharacteristic(META)
            artCharacteristic = service?.getCharacteristic(ART)
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                onStatus("Связь с CrowPanel установлена")
                if (mtuReady) { flushLatest(); flushArtwork() }
            } else {
                onStatus("CrowPanel найден, но сервис CrowPlayer не найден")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid == ART) { artWriteInFlight = false; if (status != BluetoothGatt.GATT_SUCCESS) onStatus("Ошибка передачи обложки ($status)"); flushArtwork(); return }
            writeInFlight = false
            if (status == BluetoothGatt.GATT_SUCCESS) sentPayload = String(c.value ?: byteArrayOf(), Charsets.UTF_8)
            else onStatus("Ошибка передачи данных на CrowPanel ($status)")
            flushLatest()
            flushArtwork()
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

    fun setNightMode(enabled: Boolean) { nightMode = enabled; sentPayload = ""; flushLatest() }
    fun setScreenBrightness(value: Int) { screenBrightness = value.coerceIn(5, 100); sentPayload = ""; flushLatest() }
    fun setLedBrightness(value: Int) { ledBrightness = value.coerceIn(0, 100); sentPayload = ""; flushLatest() }

    fun sendArtwork(bitmap: Bitmap?) {
        if (bitmap == null) return
        val key = 31 * bitmap.width + bitmap.height + bitmap.generationId
        if (key == lastArtworkKey) return
        lastArtworkKey = key
        val scaled = Bitmap.createScaledBitmap(bitmap, 80, 80, true)
        val pixels = IntArray(80 * 80)
        scaled.getPixels(pixels, 0, 80, 0, 0, 80, 80)
        if (scaled !== bitmap) scaled.recycle()
        val raw = ByteArray(pixels.size * 2)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 19) and 0x1f
            val g = (c shr 10) and 0x3f
            val b = (c shr 3) and 0x1f
            val rgb565 = (r shl 11) or (g shl 5) or b
            raw[i * 2] = (rgb565 shr 8).toByte()
            raw[i * 2 + 1] = rgb565.toByte()
        }
        synchronized(artQueue) {
            artQueue.clear()
            val mtuPayload = (negotiatedMtu - 7).coerceAtLeast(16)
            var offset = 0
            while (offset < raw.size) {
                val count = minOf(mtuPayload, raw.size - offset)
                val packet = ByteArray(count + 4)
                packet[0] = (offset shr 8).toByte(); packet[1] = offset.toByte()
                packet[2] = (raw.size shr 8).toByte(); packet[3] = raw.size.toByte()
                System.arraycopy(raw, offset, packet, 4, count)
                artQueue.add(packet)
                offset += count
            }
        }
        flushArtwork()
    }

    @SuppressLint("MissingPermission")
    private fun flushArtwork() {
        val g = gatt ?: return
        val c = artCharacteristic ?: return
        if (!mtuReady || artWriteInFlight) return
        val packet = synchronized(artQueue) { if (artQueue.isEmpty()) null else artQueue.removeFirst() } ?: return
        try {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = packet
            artWriteInFlight = g.writeCharacteristic(c)
            if (!artWriteInFlight) synchronized(artQueue) { artQueue.addFirst(packet) }
        } catch (_: Exception) {
            artWriteInFlight = false
            synchronized(artQueue) { artQueue.addFirst(packet) }
        }
    }

    fun send(title: String, artist: String, playback: String, positionMs: Long = 0L, durationMs: Long = 0L) {
        val state = if (playback.equals("playing", true) || playback.contains("Воспроизводится", true)) "playing" else "paused"
        latestPayload = JSONObject()
            .put("title", title.take(64))
            .put("artist", artist.take(64))
            .put("state", state)
            .put("positionMs", positionMs.coerceAtLeast(0L))
            .put("durationMs", durationMs.coerceAtLeast(0L))
            .put("nightMode", nightMode)
            .put("screenBrightness", screenBrightness)
            .put("ledBrightness", ledBrightness)
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
        artCharacteristic = null
        artWriteInFlight = false
        synchronized(artQueue) { artQueue.clear() }
        mtuReady = false
        negotiatedMtu = 23
        writeInFlight = false
    }
}
