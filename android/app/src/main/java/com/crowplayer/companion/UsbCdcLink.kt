package com.crowplayer.companion

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.concurrent.Executors

/** Android USB-host side of the panel's CDC ACM JSON-lines protocol. */
class UsbCdcLink(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onCommand: (JSONObject) -> Unit
) {
    companion object {
        private const val ACTION_PERMISSION = "com.crowplayer.companion.USB_PERMISSION"
        private const val CDC_SET_LINE_CODING = 0x20
        private const val CDC_SET_CONTROL_LINE_STATE = 0x22
        private const val MAX_LINE_BYTES = 2047
        private const val ARTWORK_SIZE = 240
    }

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val writer = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var output: UsbEndpoint? = null
    @Volatile private var running = false
    private var reader: Thread? = null
    @Volatile private var connectedDeviceId = -1
    @Volatile private var permissionPendingDeviceId = -1
    @Volatile private var permissionDeniedDeviceId = -1
    private var registered = false
    @Volatile private var started = false
    private var lastStateAt = 0L
    @Volatile private var lastArtworkRevision = Long.MIN_VALUE
    @Volatile private var queuedArtworkRevision = Long.MIN_VALUE
    private var lastStatus = ""
    private val reconnectTask = object : Runnable {
        override fun run() {
            if (!started) return
            if (connection != null && !isConnectedDeviceListed()) disconnect("CrowPanel переподключается")
            if (connection == null) discover()
            mainHandler.postDelayed(this, 2000L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val device = intent.usbDeviceExtra() ?: return
                    if (permissionPendingDeviceId == device.deviceId) permissionPendingDeviceId = -1
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(device)
                    else {
                        permissionDeniedDeviceId = device.deviceId
                        report("Разреши CrowPlayer доступ к USB-панели")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    permissionDeniedDeviceId = -1
                    if (connection != null && !isConnectedDeviceListed()) disconnect("CrowPanel переподключается")
                    discover()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDeviceExtra()
                    val matchesConnected = device == null || device.deviceId == connectedDeviceId
                    val matchesPendingPermission = device == null || device.deviceId == permissionPendingDeviceId
                    val activeDeviceMissing = connection != null && !isConnectedDeviceListed()
                    if (connection != null && (matchesConnected || activeDeviceMissing)) {
                        disconnect("CrowPanel отключена")
                    }
                    if (matchesConnected || matchesPendingPermission || activeDeviceMissing) {
                        permissionPendingDeviceId = -1
                        permissionDeniedDeviceId = -1
                    }
                    scheduleReconnect()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    fun start() {
        started = true
        if (!context.packageManager.hasSystemFeature("android.hardware.usb.host")) {
            report("Планшет не поддерживает USB host/OTG")
            return
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            registered = true
        }
        permissionDeniedDeviceId = -1
        discover()
        scheduleReconnect(1500L)
    }

    private fun discover() {
        if (connection != null) {
            if (isConnectedDeviceListed()) return
            disconnect("CrowPanel переподключается")
        }
        val candidate = manager.deviceList.values.firstOrNull { cdcInterfaces(it) != null }
        if (candidate == null) {
            report("Подключи CrowPanel к USB OTG планшета")
            return
        }
        if (manager.hasPermission(candidate)) {
            permissionPendingDeviceId = -1
            permissionDeniedDeviceId = -1
            connect(candidate)
        } else if (permissionPendingDeviceId != candidate.deviceId && permissionDeniedDeviceId != candidate.deviceId) {
            report("Запрашиваем разрешение USB для CrowPanel…")
            permissionPendingDeviceId = candidate.deviceId
            val pending = PendingIntent.getBroadcast(
                context,
                candidate.deviceId,
                Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.requestPermission(candidate, pending)
        }
    }

    private fun isConnectedDeviceListed(): Boolean {
        val id = connectedDeviceId
        if (id < 0) return false
        return try { manager.deviceList.values.any { it.deviceId == id } } catch (_: Exception) { false }
    }

    private data class CdcInterfaces(val control: UsbInterface, val data: UsbInterface, val input: UsbEndpoint, val output: UsbEndpoint)

    private fun cdcInterfaces(device: UsbDevice): CdcInterfaces? {
        val controls = (0 until device.interfaceCount).map { device.getInterface(it) }
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_COMM && it.interfaceSubclass == 0x02 }
        val data = (0 until device.interfaceCount).map { device.getInterface(it) }
            .filter { it.interfaceClass == 0x0A }
        for (control in controls) for (iface in data) {
            val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: continue
            val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: continue
            return CdcInterfaces(control, iface, input, output)
        }
        return null
    }

    private fun connect(device: UsbDevice) {
        synchronized(stateLock) {
            if (connection != null || !manager.hasPermission(device)) return
            val interfaces = cdcInterfaces(device)
            if (interfaces == null) {
                report("USB-устройство не предоставляет CDC ACM")
                return
            }
            val conn = manager.openDevice(device)
            if (conn == null) {
                report("Не удалось открыть USB-порт CrowPanel")
                return
            }
            val claimedControl = conn.claimInterface(interfaces.control, true)
            val claimedData = claimedControl && conn.claimInterface(interfaces.data, true)
            if (!claimedData) {
                conn.close()
                report("Не удалось открыть CDC-интерфейс панели")
                return
            }
            val lineCoding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 0, 8) // 115200, 8N1
            val codingOk = conn.controlTransfer(0x21, CDC_SET_LINE_CODING, 0, interfaces.control.id,
                lineCoding, 0, lineCoding.size, 1000) >= 0
            val dtrOk = conn.controlTransfer(0x21, CDC_SET_CONTROL_LINE_STATE, 3, interfaces.control.id,
                null, 0, 0, 1000) >= 0
            if (!codingOk || !dtrOk) {
                conn.releaseInterface(interfaces.data)
                conn.releaseInterface(interfaces.control)
                conn.close()
                report("USB CDC не ответил на настройку порта")
                return
            }
            connection = conn
            output = interfaces.output
            connectedDeviceId = device.deviceId
            permissionPendingDeviceId = -1
            permissionDeniedDeviceId = -1
            lastStateAt = 0L
            // A newly connected panel has no cached cover, even if Android does.
            lastArtworkRevision = Long.MIN_VALUE
            running = true
            reader = Thread({ readLoop(conn, interfaces.input, device.deviceId) }, "crowplayer-usb-reader").apply {
                isDaemon = true
                start()
            }
            report("Связь с CrowPanel установлена по USB")
        }
    }

    private fun readLoop(conn: UsbDeviceConnection, input: UsbEndpoint, deviceId: Int) {
        val buffer = ByteArray(512)
        val line = ByteArray(MAX_LINE_BYTES)
        var size = 0
        var overflow = false
        while (running && connection === conn) {
            val count = try { conn.bulkTransfer(input, buffer, buffer.size, 300) } catch (_: Exception) { -1 }
            if (count < 0) {
                // A quiet CDC endpoint can report a timed-out read as -1 on some Android
                // USB host implementations. The panel only sends data for user actions,
                // so an idle read is normal; the detach broadcast or missing device ends it.
                val stillAttached = try {
                    manager.deviceList.values.any { it.deviceId == deviceId }
                } catch (_: Exception) {
                    false
                }
                if (!stillAttached) break
                try { Thread.sleep(25) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }
            for (i in 0 until count) {
                val value = buffer[i].toInt() and 0xFF
                if (value == 10) {
                    if (!overflow && size > 0) {
                        val text = String(line, 0, size, Charsets.UTF_8).trimEnd('\r')
                        try {
                            val json = JSONObject(text)
                            if (json.optInt("v") == 1 && json.optString("type") == "command") onCommand(json)
                        } catch (_: Exception) {}
                    }
                    size = 0
                    overflow = false
                } else if (value != 13) {
                    if (size < line.size) line[size++] = value.toByte() else overflow = true
                }
            }
        }
        if (connection === conn) {
            disconnect("Связь с CrowPanel по USB прервана")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        scheduleReconnect(1000L)
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (!started) return
        mainHandler.removeCallbacks(reconnectTask)
        mainHandler.postDelayed(reconnectTask, delayMs)
    }

    @Synchronized
    fun sendState() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStateAt < 900L) return
        lastStateAt = now
        val liked: Any = if (PlayerSnapshot.likeStateKnown) PlayerSnapshot.isLiked else JSONObject.NULL
        val payload = JSONObject()
            .put("v", 1)
            .put("type", "state")
            .put("title", PlayerSnapshot.title.take(160))
            .put("artist", PlayerSnapshot.artist.take(100))
            .put("state", if (PlayerSnapshot.playback.equals("playing", true)) "playing" else "paused")
            .put("positionMs", PlayerSnapshot.positionMs.coerceAtLeast(0L))
            .put("durationMs", PlayerSnapshot.durationMs.coerceAtLeast(0L))
            .put("liked", liked)
            .put("likeAvailable", PlayerSnapshot.likeAvailable)
            .put("epochMs", System.currentTimeMillis())
            .put("utcOffsetMinutes", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
        write(payload)
        syncArtwork()
    }

    private fun syncArtwork() {
        val revision = PlayerSnapshot.artworkRevision
        if (revision == lastArtworkRevision || revision == queuedArtworkRevision) return
        queuedArtworkRevision = revision
        val bitmap = PlayerSnapshot.artwork
        try {
            writer.execute {
                var transferred = false
                try {
                    val conn = connection ?: return@execute
                    val endpoint = output ?: return@execute
                    if (bitmap == null) {
                        transferred = writeBytes(conn, endpoint,
                            (JSONObject().put("v", 1).put("type", "artwork_clear").toString() + "\n").toByteArray())
                        return@execute
                    }
                    val readable = if (bitmap.config == Bitmap.Config.HARDWARE)
                        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@execute
                    else bitmap
                    val scaled = if (readable.width == ARTWORK_SIZE && readable.height == ARTWORK_SIZE) readable
                        else Bitmap.createScaledBitmap(readable, ARTWORK_SIZE, ARTWORK_SIZE, true)
                    val raw = try {
                        val pixels = IntArray(ARTWORK_SIZE * ARTWORK_SIZE)
                        scaled.getPixels(pixels, 0, ARTWORK_SIZE, 0, 0, ARTWORK_SIZE, ARTWORK_SIZE)
                        ByteArray(pixels.size * 2).also { bytes ->
                            for (i in pixels.indices) {
                                val color = pixels[i]
                                val rgb565 = (((color ushr 16) and 0xF8) shl 8) or
                                    (((color ushr 8) and 0xFC) shl 3) or ((color and 0xFF) ushr 3)
                                bytes[i * 2] = rgb565.toByte()
                                bytes[i * 2 + 1] = (rgb565 ushr 8).toByte()
                            }
                        }
                    } finally {
                        if (scaled !== readable) scaled.recycle()
                        if (readable !== bitmap) readable.recycle()
                    }
                    val chunks = (raw.size + 899) / 900
                    if (!writeBytes(conn, endpoint, line(JSONObject().put("v", 1).put("type", "artwork_start")
                            .put("width", ARTWORK_SIZE).put("height", ARTWORK_SIZE).put("bytes", raw.size).put("chunks", chunks)))) return@execute
                    var offset = 0
                    while (offset < raw.size && connection === conn) {
                        val length = minOf(900, raw.size - offset)
                        val data = Base64.encodeToString(raw.copyOfRange(offset, offset + length), Base64.NO_WRAP)
                        val packet = JSONObject().put("v", 1).put("type", "artwork_chunk").put("offset", offset).put("data", data)
                        if (!writeBytes(conn, endpoint, line(packet))) return@execute
                        offset += length
                    }
                    transferred = offset == raw.size && writeBytes(conn, endpoint,
                        line(JSONObject().put("v", 1).put("type", "artwork_end")))
                } catch (_: Exception) {
                    // Leave this revision unacknowledged so the next state tick retries it.
                } finally {
                    if (transferred && PlayerSnapshot.artworkRevision == revision) lastArtworkRevision = revision
                    if (queuedArtworkRevision == revision) queuedArtworkRevision = Long.MIN_VALUE
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (queuedArtworkRevision == revision) queuedArtworkRevision = Long.MIN_VALUE
        }
    }

    private fun line(json: JSONObject) = (json.toString() + "\n").toByteArray(Charsets.UTF_8)

    fun sendResult(id: Long, ok: Boolean, liked: Boolean?) {
        val payload = JSONObject().put("v", 1).put("type", "result").put("id", id).put("ok", ok)
            .put("liked", liked ?: JSONObject.NULL)
        write(payload)
    }

    fun sendResult(id: Long, ok: Boolean, liked: Boolean?, message: String?) {
        val payload = JSONObject().put("v", 1).put("type", "result").put("id", id).put("ok", ok)
            .put("liked", liked ?: JSONObject.NULL).put("message", message ?: JSONObject.NULL)
        write(payload)
    }

    private fun write(json: JSONObject) {
        val bytes = line(json)
        try {
            writer.execute {
                val conn = connection ?: return@execute
                val endpoint = output ?: return@execute
                writeBytes(conn, endpoint, bytes)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun writeBytes(conn: UsbDeviceConnection, endpoint: UsbEndpoint, bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size && connection === conn) {
            val sent = try { conn.bulkTransfer(endpoint, bytes, offset, bytes.size - offset, 1000) }
            catch (_: Exception) { -1 }
            if (sent <= 0) {
                report("Ошибка передачи данных CrowPanel по USB")
                return false
            }
            offset += sent
        }
        return offset == bytes.size
    }

    private fun report(message: String) {
        synchronized(stateLock) {
            if (lastStatus == message) return
            lastStatus = message
        }
        onStatus(message)
    }

    private fun disconnect(message: String) {
        synchronized(stateLock) {
            running = false
            val old = connection
            connection = null
            output = null
            connectedDeviceId = -1
            permissionPendingDeviceId = -1
            lastStateAt = 0L
            try { old?.close() } catch (_: Exception) {}
        }
        report(message)
    }

    fun close() {
        started = false
        mainHandler.removeCallbacksAndMessages(null)
        disconnect("Связь с CrowPanel отключена")
        if (registered) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            registered = false
        }
        writer.shutdownNow()
    }
}

