package jp.morinote.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray

private data class ReleaseHistory(val version: String, val date: String, val changes: List<String>)

@Composable
internal fun ReleaseHistoryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val releases = remember(context) {
        val source = context.assets.open("release-history.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val entries = JSONArray(source)
        List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val changes = entry.getJSONArray("changes")
            ReleaseHistory(entry.getString("version"), entry.getString("date"),
                List(changes.length()) { changes.getString(it) })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121A00),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("更新履歴") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                releases.forEach { release ->
                    val dateLabel = release.date.split('-').drop(1).joinToString("/") { it.toInt().toString() }
                    Text("ver${release.version}（${dateLabel}）",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFD9D917), modifier = Modifier.padding(top = 8.dp))
                    release.changes.forEach { change ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", color = Color.White)
                            Text(change, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる", color = Color.White) } },
    )
}
