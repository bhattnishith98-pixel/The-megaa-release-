package com.example.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

data class LocalIpInfo(
    val ipv4Addresses: List<String>,
    val ipv6Addresses: List<String>
)

data class NetworkTransferEntry(
    val id: String,
    val direction: String, // SEND or RECV
    val protocol: String,  // IPv4 or IPv6
    val peer: String,
    val fileName: String,
    val bytesCount: Int,
    val ok: Boolean,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Dual-stack IPv4 & IPv6 peer-to-peer data sharing manager.
 * Transfers scripts (.stl), polyglot libraries (.stl.lib), compiled binaries (.stlb/.elf),
 * or project bundles directly over IPv4 or IPv6 TCP sockets with length-prefixed framing.
 */
class NetworkShareManager(
    private val workspaceRoot: File
) {
    private val inboxDir = File(workspaceRoot, "shared_inbox").apply { mkdirs() }
    private val libsDir = File(workspaceRoot, "libs").apply { mkdirs() }

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val _listeningPort = MutableStateFlow(DEFAULT_PORT)
    val listeningPort: StateFlow<Int> = _listeningPort.asStateFlow()

    private val _localIps = MutableStateFlow(inspectLocalIps())
    val localIps: StateFlow<LocalIpInfo> = _localIps.asStateFlow()

    private val _transferLogs = MutableStateFlow<List<NetworkTransferEntry>>(emptyList())
    val transferLogs: StateFlow<List<NetworkTransferEntry>> = _transferLogs.asStateFlow()

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    fun refreshLocalIps(): LocalIpInfo {
        val info = inspectLocalIps()
        _localIps.value = info
        return info
    }

    fun startReceiverServer(scope: CoroutineScope, port: Int = DEFAULT_PORT) {
        stopReceiverServer()
        val safePort = port.coerceIn(1024, 65535)
        _listeningPort.value = safePort
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                // Dual-stack wildcard "::" accepts both IPv6 and IPv4-mapped peers on Android/Linux.
                val bindAddr = InetAddress.getByName("::")
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(bindAddr, safePort))
                serverSocket = ss
                _serverRunning.value = true
                appendLog(
                    direction = "RECV",
                    protocol = "IPv4/IPv6",
                    peer = "0.0.0.0 / [::]:$safePort",
                    fileName = "listener",
                    bytesCount = 0,
                    ok = true,
                    message = "Dual-stack receiver listening on port $safePort."
                )
                while (isActive && !ss.isClosed) {
                    val client = ss.accept()
                    handleIncomingClient(client)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_serverRunning.value) {
                    appendLog(
                        direction = "RECV",
                        protocol = "IPv4/IPv6",
                        peer = ":$safePort",
                        fileName = "listener",
                        bytesCount = 0,
                        ok = false,
                        message = "Server stopped: ${e.message}"
                    )
                }
            } finally {
                _serverRunning.value = false
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }
    }

    fun stopReceiverServer() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        _serverRunning.value = false
    }

    private fun handleIncomingClient(socket: Socket) {
        val peerAddr = socket.inetAddress?.hostAddress ?: "unknown"
        val proto = if (socket.inetAddress is Inet6Address) "IPv6" else "IPv4"
        try {
            socket.use { s ->
                s.soTimeout = 10_000
                val dis = DataInputStream(s.getInputStream())
                val magic = dis.readInt()
                if (magic != WIRE_MAGIC) {
                    appendLog("RECV", proto, peerAddr, "unknown", 0, false, "Invalid packet magic header.")
                    return
                }
                val rawName = dis.readUTF()
                val safeName = File(rawName).name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "shared_${System.currentTimeMillis()}.bin" }
                val len = dis.readInt()
                if (len < 0 || len > MAX_TRANSFER_BYTES) {
                    appendLog("RECV", proto, peerAddr, safeName, len, false, "Payload size out of safe bounds ($len bytes).")
                    return
                }
                val payload = ByteArray(len)
                dis.readFully(payload)

                val targetFile = if (safeName.endsWith(".stl.lib", ignoreCase = true)) {
                    File(libsDir, safeName)
                } else {
                    File(inboxDir, safeName)
                }
                targetFile.writeBytes(payload)

                val dos = DataOutputStream(s.getOutputStream())
                dos.writeUTF("OK:${targetFile.name}:$len")
                dos.flush()

                appendLog(
                    direction = "RECV",
                    protocol = proto,
                    peer = peerAddr,
                    fileName = targetFile.name,
                    bytesCount = len,
                    ok = true,
                    message = "Saved to ${targetFile.parentFile?.name}/${targetFile.name}"
                )
            }
        } catch (e: Exception) {
            appendLog("RECV", proto, peerAddr, "stream", 0, false, e.message ?: "Receive failed")
        }
    }

    suspend fun sendPayload(
        host: String,
        port: Int = DEFAULT_PORT,
        fileName: String,
        payload: ByteArray
    ): NetworkTransferEntry = withContext(Dispatchers.IO) {
        val normalizedHost = host.trim().removePrefix("[").substringBefore("]")
        val safePort = port.coerceIn(1, 65535)
        val cleanName = File(fileName).name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "data.bin" }
        val proto = if (normalizedHost.contains(":")) "IPv6" else "IPv4"

        if (normalizedHost.isEmpty()) {
            return@withContext appendLog("SEND", proto, host, cleanName, payload.size, false, "Target IP host cannot be blank.")
        }
        if (payload.size > MAX_TRANSFER_BYTES) {
            return@withContext appendLog("SEND", proto, host, cleanName, payload.size, false, "Payload exceeds 16 MB limit.")
        }

        return@withContext try {
            val addr = InetAddress.getByName(normalizedHost)
            val resolvedProto = if (addr is Inet6Address) "IPv6" else "IPv4"
            Socket().use { s ->
                s.connect(InetSocketAddress(addr, safePort), 6_000)
                s.soTimeout = 8_000
                val dos = DataOutputStream(s.getOutputStream())
                dos.writeInt(WIRE_MAGIC)
                dos.writeUTF(cleanName)
                dos.writeInt(payload.size)
                dos.write(payload)
                dos.flush()

                val dis = DataInputStream(s.getInputStream())
                val ack = dis.readUTF()
                appendLog(
                    direction = "SEND",
                    protocol = resolvedProto,
                    peer = "$normalizedHost:$safePort",
                    fileName = cleanName,
                    bytesCount = payload.size,
                    ok = true,
                    message = "Sent ${payload.size} bytes ($ack)"
                )
            }
        } catch (e: Exception) {
            appendLog("SEND", proto, "$normalizedHost:$safePort", cleanName, payload.size, false, e.message ?: "Transfer failed")
        }
    }

    private fun appendLog(
        direction: String,
        protocol: String,
        peer: String,
        fileName: String,
        bytesCount: Int,
        ok: Boolean,
        message: String
    ): NetworkTransferEntry {
        val entry = NetworkTransferEntry(
            id = "net_${System.currentTimeMillis()}_${_transferLogs.value.size}",
            direction = direction,
            protocol = protocol,
            peer = peer,
            fileName = fileName,
            bytesCount = bytesCount,
            ok = ok,
            message = message
        )
        _transferLogs.value = (listOf(entry) + _transferLogs.value).take(40)
        return entry
    }

    companion object {
        const val DEFAULT_PORT = 47890
        private const val WIRE_MAGIC = 0x53544C4E // "STLN"
        private const val MAX_TRANSFER_BYTES = 16 * 1024 * 1024

        fun inspectLocalIps(): LocalIpInfo {
            val v4 = linkedSetOf<String>()
            val v6 = linkedSetOf<String>()
            runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces != null && ifaces.hasMoreElements()) {
                    val nif = ifaces.nextElement()
                    if (!nif.isUp) continue
                    val addrs = nif.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        val raw = addr.hostAddress?.substringBefore('%') ?: continue
                        when (addr) {
                            is Inet4Address -> if (!addr.isLoopbackAddress) v4 += raw
                            is Inet6Address -> if (!addr.isLoopbackAddress) v6 += raw
                        }
                    }
                }
            }
            if (v4.isEmpty()) v4 += "127.0.0.1"
            if (v6.isEmpty()) v6 += "::1"
            return LocalIpInfo(v4.toList(), v6.toList())
        }
    }
}
