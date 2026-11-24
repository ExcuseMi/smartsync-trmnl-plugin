package org.avmedia.smartsync.plugin.trmnl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.avmedia.smartsync.plugin.api.PluginApi
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginNeeds
import org.avmedia.smartsync.plugin.api.PluginUi
import org.avmedia.smartsync.plugin.api.WatchEvent
import org.avmedia.smartsync.plugin.api.WatchPlugin

/**
 * Pushes each watch's values to a TRMNL private plugin (webhook strategy), so a TRMNL shows the watch without Home
 * Assistant. Events only mark a watch as changed; sending runs in the plugin's own scope and never holds the watch.
 */
class TrmnlPlugin(
    private val transport: WebhookTransport = HttpTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) : WatchPlugin {
    override val id = "trmnl"
    override val name = "TRMNL"
    override val apiVersion = PluginApi.VERSION

    private var host: PluginHost? = null
    private var sender: Sender? = null
    private var scope: CoroutineScope? = null
    private val flushLock = Mutex()
    private val lock = Any()
    private var job: Job? = null
    private var wakeAt = Long.MAX_VALUE

    private val _status = MutableStateFlow(SendStatus())
    val status: StateFlow<SendStatus> get() = _status

    override suspend fun activate(host: PluginHost) {
        this.host = host
        host.declareNeeds(PluginNeeds(values = Payload.VALUE_KEYS, healthReads = true))
        sender = Sender(host, transport, clock)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settingsChanged()
    }

    override suspend fun deactivate() {
        scope?.cancel()
        scope = null
        synchronized(lock) { job = null; wakeAt = Long.MAX_VALUE }
        sender = null
        host = null
    }

    override suspend fun onEvent(event: WatchEvent) {
        val s = sender ?: return
        val watch = event.watch ?: return
        when (event) {
            is WatchEvent.ValuesUpdated, is WatchEvent.HealthRead, is WatchEvent.Connected, is WatchEvent.Disconnected,
            is WatchEvent.Condition, is WatchEvent.SettingChanged -> {
                s.markDirty(watch)
                schedule(clock() + Limits.COALESCE_MILLIS)
            }
            else -> Unit
        }
    }

    /** After a settings change (or at start): every watch is checked again; only changed payloads go out. */
    fun settingsChanged() {
        sender?.markAllDirty()
        schedule(clock() + Limits.COALESCE_MILLIS)
    }

    internal fun host(): PluginHost? = host

    private fun schedule(at: Long) {
        val sc = scope ?: return
        synchronized(lock) {
            if (job?.isActive == true && wakeAt <= at) return
            job?.cancel()
            wakeAt = at
            job = sc.launch {
                delay((at - clock()).coerceAtLeast(0))
                runFlush()
            }
        }
    }

    private suspend fun runFlush() {
        val s = sender ?: return
        val next = flushLock.withLock {
            try {
                s.flush()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                host?.failure("TRMNL: send failed", e)
                clock() + Limits.COALESCE_MILLIS * 5
            }
        }
        _status.value = s.status
        synchronized(lock) { job = null; wakeAt = Long.MAX_VALUE }
        val again = next ?: if (s.pending()) clock() + Limits.COALESCE_MILLIS else null
        if (again != null) schedule(again)
    }

    override val ui: PluginUi = TrmnlUi(this)

    companion object {
        /** The plugin's version (plugin/build.gradle, tag v<version>). */
        const val VERSION = "0.1.0"
    }
}
