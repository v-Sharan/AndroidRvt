package com.androidrvt.viewer.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidrvt.viewer.AppLog

@Composable
fun LogScreen(onClose: () -> Unit) {
    val lines by AppLog.lines.collectAsState()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF12171C))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Connection log", color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
            PillButton("Share") {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
                context.startActivity(Intent.createChooser(send, "Share log"))
            }
            PillButton("Clear", onClick = AppLog::clear)
            PillButton("Close", onClick = onClose)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines.asReversed()) { line ->
                Text(line, color = Color.LightGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
