package com.androidrvt.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidrvt.viewer.MainViewModel
import com.androidrvt.viewer.data.CameraProfile
import com.androidrvt.viewer.data.TEST_PATTERN_URL
import com.androidrvt.viewer.data.Transport

@Composable
fun SettingsScreen(viewModel: MainViewModel, onClose: () -> Unit) {
    val profiles by viewModel.profiles.collectAsState()
    val active by viewModel.activeProfile.collectAsState()
    var editing by remember { mutableStateOf(active) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF12171C))
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Camera profiles", color = Color.White, fontSize = 20.sp)
            PillButton("Close", onClick = onClose)
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            profiles.forEach { profile ->
                PillButton(profile.name, active = profile.id == editing.id) {
                    editing = profile
                    viewModel.selectProfile(profile)
                }
            }
            PillButton("+ New") { editing = CameraProfile() }
        }

        key(editing.id) {
            ProfileForm(
                initial = editing,
                canDelete = profiles.size > 1 && profiles.any { it.id == editing.id },
                onSave = {
                    editing = it
                    viewModel.saveProfile(it)
                    onClose()
                },
                onDelete = {
                    viewModel.deleteProfile(editing)
                    editing = viewModel.activeProfile.value
                },
            )
        }

        Text(
            "UDP uses the placeholder protocol: ${viewModel.link.codecName}. Replace it once the camera " +
                "protocol is available. Use \"$TEST_PATTERN_URL\" as the RTSP URL for a test picture.",
            color = Color.Gray,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ProfileForm(
    initial: CameraProfile,
    canDelete: Boolean,
    onSave: (CameraProfile) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.rtspUrl) }
    var transport by remember { mutableStateOf(initial.transport) }
    var latency by remember { mutableStateOf(initial.latencyMs.toString()) }
    var frameTimeout by remember { mutableStateOf(initial.frameTimeoutMs.toString()) }
    var connectTimeout by remember { mutableStateOf(initial.connectTimeoutMs.toString()) }
    var hardwareDecoder by remember { mutableStateOf(initial.hardwareDecoder) }
    var udpHost by remember { mutableStateOf(initial.udpHost) }
    var udpTarget by remember { mutableStateOf(initial.udpTargetPort.toString()) }
    var udpLocal by remember { mutableStateOf(initial.udpLocalPort.toString()) }
    var heartbeat by remember { mutableStateOf(initial.heartbeatMs.toString()) }
    var controlRate by remember { mutableStateOf(initial.controlRateHz.toString()) }
    var bindToWifi by remember { mutableStateOf(initial.bindToWifi) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormRow {
            TextEntry("Name", name, Modifier.weight(1f)) { name = it }
            TextEntry("RTSP URL", url, Modifier.weight(2f)) { url = it }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("RTSP transport", color = Color.White, fontSize = 14.sp)
            Transport.entries.forEach { option ->
                PillButton(option.label, active = transport == option) { transport = option }
            }
        }

        FormRow {
            NumberEntry("Latency (ms)", latency, Modifier.weight(1f)) { latency = it }
            NumberEntry("Reconnect after no frames (ms)", frameTimeout, Modifier.weight(1f)) { frameTimeout = it }
            NumberEntry("Connect timeout (ms)", connectTimeout, Modifier.weight(1f)) { connectTimeout = it }
        }

        FormRow {
            TextEntry("UDP camera IP (blank = off)", udpHost, Modifier.weight(2f)) { udpHost = it }
            NumberEntry("Camera port", udpTarget, Modifier.weight(1f)) { udpTarget = it }
            NumberEntry("Local port", udpLocal, Modifier.weight(1f)) { udpLocal = it }
        }

        FormRow {
            NumberEntry("Heartbeat (ms, 0 = off)", heartbeat, Modifier.weight(1f)) { heartbeat = it }
            NumberEntry("Control rate (Hz)", controlRate, Modifier.weight(1f)) { controlRate = it }
        }

        ToggleRow("Hardware video decoder", hardwareDecoder) { hardwareDecoder = it }
        ToggleRow("Stay on camera Wi-Fi without internet", bindToWifi) { bindToWifi = it }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Save and use", active = true) {
                onSave(
                    initial.copy(
                        name = name.trim().ifEmpty { "Camera" },
                        rtspUrl = url.trim(),
                        transport = transport,
                        latencyMs = number(latency, initial.latencyMs, 0, 5000),
                        frameTimeoutMs = number(frameTimeout, initial.frameTimeoutMs, 500, 60000),
                        connectTimeoutMs = number(connectTimeout, initial.connectTimeoutMs, 1000, 60000),
                        hardwareDecoder = hardwareDecoder,
                        udpHost = udpHost.trim(),
                        udpTargetPort = number(udpTarget, initial.udpTargetPort, 1, 65535),
                        udpLocalPort = number(udpLocal, initial.udpLocalPort, 0, 65535),
                        heartbeatMs = number(heartbeat, initial.heartbeatMs, 0, 60000),
                        controlRateHz = number(controlRate, initial.controlRateHz, 1, 100),
                        bindToWifi = bindToWifi,
                    ),
                )
            }
            if (canDelete) PillButton("Delete", onClick = onDelete)
        }
    }
}

private fun number(text: String, fallback: Int, minimum: Int, maximum: Int): Int =
    (text.trim().toIntOrNull() ?: fallback).coerceIn(minimum, maximum)

@Composable
private fun FormRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun TextEntry(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun NumberEntry(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}
