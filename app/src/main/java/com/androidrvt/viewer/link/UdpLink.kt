package com.androidrvt.viewer.link

import com.androidrvt.viewer.AppLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UdpLink(private val codec: ProtocolCodec, private val scope: CoroutineScope) {
    data class Config(
        val host: String,
        val targetPort: Int,
        val localPort: Int,
        val heartbeatMs: Int,
    )

    data class Status(
        val enabled: Boolean = false,
        val target: String = "",
        val txPackets: Long = 0,
        val rxPackets: Long = 0,
        val lastRxAgeMs: Long? = null,
        val error: String? = null,
    ) {
        val peerAlive: Boolean get() = lastRxAgeMs != null && lastRxAgeMs < PEER_TIMEOUT_MS
    }

    private val commands = Channel<ControlCommand>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutableEvents = MutableSharedFlow<LinkEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableStatus = MutableStateFlow(Status())
    private val txPackets = AtomicLong()
    private val rxPackets = AtomicLong()
    private val lastRxAt = AtomicLong(0)
    private var job: Job? = null

    val events: SharedFlow<LinkEvent> = mutableEvents.asSharedFlow()
    val status: StateFlow<Status> = mutableStatus.asStateFlow()
    val codecName: String get() = codec.name

    fun start(config: Config) {
        val previous = job
        job = scope.launch(Dispatchers.IO) {
            previous?.cancelAndJoin()
            while (commands.tryReceive().isSuccess) Unit
            txPackets.set(0)
            rxPackets.set(0)
            lastRxAt.set(0)
            val target = "${config.host}:${config.targetPort}"
            mutableStatus.value = Status(enabled = true, target = target)
            while (isActive) {
                try {
                    runSession(config, target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    AppLog.add("UDP error: $message")
                    mutableStatus.value = mutableStatus.value.copy(error = message)
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    fun stop() {
        val previous = job
        job = null
        scope.launch {
            previous?.cancelAndJoin()
            mutableStatus.value = Status()
        }
    }

    fun send(command: ControlCommand) {
        commands.trySend(command)
    }

    private suspend fun runSession(config: Config, target: String) = withContext(Dispatchers.IO) {
        val address = InetSocketAddress(InetAddress.getByName(config.host), config.targetPort)
        val socket = if (config.localPort > 0) DatagramSocket(config.localPort) else DatagramSocket()
        socket.use {
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            AppLog.add("UDP link open to $target from local port ${socket.localPort}")
            mutableStatus.value = mutableStatus.value.copy(error = null)
            coroutineScope {
                launch { receiveLoop(socket) }
                launch { sendLoop(socket, address) }
                launch { publishStatus(target) }
                if (config.heartbeatMs > 0) {
                    launch {
                        while (isActive) {
                            send(ControlCommand.Heartbeat)
                            delay(config.heartbeatMs.toLong())
                        }
                    }
                }
            }
        }
    }

    private suspend fun receiveLoop(socket: DatagramSocket) = coroutineScope {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (isActive) {
            try {
                packet.length = buffer.size
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            rxPackets.incrementAndGet()
            lastRxAt.set(System.currentTimeMillis())
            codec.decode(buffer, packet.length).forEach { mutableEvents.tryEmit(it) }
        }
    }

    private suspend fun sendLoop(socket: DatagramSocket, address: InetSocketAddress) {
        for (command in commands) {
            val bytes = codec.encode(command) ?: continue
            socket.send(DatagramPacket(bytes, bytes.size, address))
            txPackets.incrementAndGet()
        }
    }

    private suspend fun publishStatus(target: String) = coroutineScope {
        while (isActive) {
            val last = lastRxAt.get()
            mutableStatus.value = mutableStatus.value.copy(
                enabled = true,
                target = target,
                txPackets = txPackets.get(),
                rxPackets = rxPackets.get(),
                lastRxAgeMs = if (last == 0L) null else System.currentTimeMillis() - last,
            )
            delay(STATUS_INTERVAL_MS)
        }
    }

    private companion object {
        const val RECEIVE_TIMEOUT_MS = 500
        const val RETRY_DELAY_MS = 2000L
        const val STATUS_INTERVAL_MS = 500L
        const val MAX_PACKET_BYTES = 2048
        const val PEER_TIMEOUT_MS = 3000L
    }
}
