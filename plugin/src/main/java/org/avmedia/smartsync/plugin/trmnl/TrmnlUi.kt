package org.avmedia.smartsync.plugin.trmnl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.avmedia.smartsync.plugin.api.PluginUi
import java.text.DateFormat
import java.util.Date

/** Settings entry and screen: on/off, one URL for all watches or one per watch, minimum interval. */
internal class TrmnlUi(private val plugin: TrmnlPlugin) : PluginUi {
    @Composable
    override fun SettingsEntry(onOpen: () -> Unit) {
        val status by plugin.status.collectAsState()
        val host = plugin.host()
        val on = host?.let { Settings.load(it).enabled } == true
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp)) {
            Text(plugin.name, style = MaterialTheme.typography.titleMedium)
            Text(statusLine(on, status), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    override fun SettingsScreen(onBack: () -> Unit) {
        val host = plugin.host() ?: return
        var s by remember { mutableStateOf(Settings.load(host)) }
        var interval by remember { mutableStateOf(s.intervalMinutes.toString()) }
        val watches = remember { host.watches() }
        val urls = remember { watches.associate { it.id to mutableStateOf(Settings.watchUrl(host, it.id)) } }
        fun save(next: Settings) { s = next; next.save(host); plugin.settingsChanged() }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("TRMNL", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Sends steps, heart rate, battery and last sync to a TRMNL private plugin (webhook). Only changed values " +
                    "are sent, at most every ${Limits.MIN_INTERVAL_MINUTES} minutes (TRMNL allows ${Limits.TRMNL_REQUESTS_PER_HOUR} per hour).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send to TRMNL", Modifier.weight(1f))
                Switch(checked = s.enabled, onCheckedChange = { save(s.copy(enabled = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.mode == UrlMode.SHARED, onClick = { save(s.copy(mode = UrlMode.SHARED)) })
                Text("One URL for all watches")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.mode == UrlMode.PER_WATCH, onClick = { save(s.copy(mode = UrlMode.PER_WATCH)) })
                Text("One URL per watch")
            }
            if (s.mode == UrlMode.SHARED) {
                UrlField("Webhook URL", s.sharedUrl) { save(s.copy(sharedUrl = it)) }
            } else {
                for (w in watches) {
                    val u = urls.getValue(w.id)
                    UrlField("Webhook URL: ${w.name}", u.value) {
                        u.value = it
                        Settings.saveWatchUrl(host, w.id, it)
                        plugin.settingsChanged()
                    }
                }
            }
            OutlinedTextField(
                value = interval,
                onValueChange = { t ->
                    interval = t.filter { it.isDigit() }.take(3)
                    interval.toIntOrNull()?.let { save(s.copy(intervalMinutes = Limits.clampInterval(it))) }
                },
                label = { Text("Minimum minutes between sends (${Limits.MIN_INTERVAL_MINUTES} to ${Limits.MAX_INTERVAL_MINUTES})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            val status by plugin.status.collectAsState()
            Text(statusLine(s.enabled, status), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun UrlField(label: String, value: String, onChange: (String) -> Unit) {
        var text by remember(value) { mutableStateOf(value) }
        val bad = text.isNotBlank() && !Settings.validUrl(text.trim())
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onChange(it) },
            label = { Text(label) },
            isError = bad,
            supportingText = { if (bad) Text("An https URL, from the plugin's Webhook URL field on TRMNL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun statusLine(on: Boolean, st: SendStatus): String {
        if (!on) return "Off"
        val t = DateFormat.getTimeInstance(DateFormat.SHORT)
        return when {
            st.failing != null -> "Not sent: ${st.failing}" + (st.nextTryMillis?.let { ", next try ${t.format(Date(it))}" } ?: "")
            st.lastSentMillis != null -> "Last sent ${t.format(Date(st.lastSentMillis))}"
            else -> "On, nothing sent yet"
        }
    }
}
