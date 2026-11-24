package jp.morinote.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun NotificationBell(session: BiodiarySession?, members: List<CommunityMember>, onObservation: (Observation) -> Unit) {
    key(session?.token) { NotificationBellContent(session, members, onObservation) }
}

@Composable
private fun NotificationBellContent(session: BiodiarySession?, members: List<CommunityMember>, onObservation: (Observation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var unread by remember { mutableStateOf(0) }
    var newest by remember { mutableLongStateOf(0) }
    var before by remember { mutableStateOf<Long?>(null) }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    val currentOnObservation by rememberUpdatedState(onObservation)
    val gold = Color(0xFFD9D917)
    val bell = remember {
        ImageVector.Builder("通知", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 22f); curveTo(13.1f, 22f, 14f, 21.1f, 14f, 20f)
                lineTo(10f, 20f); curveTo(10f, 21.1f, 10.9f, 22f, 12f, 22f); close()
                moveTo(18f, 16f); lineTo(18f, 10f); curveTo(18f, 6.9f, 16.4f, 4.4f, 13.5f, 3.7f)
                lineTo(13.5f, 3f); curveTo(13.5f, 2.2f, 12.8f, 1.5f, 12f, 1.5f)
                curveTo(11.2f, 1.5f, 10.5f, 2.2f, 10.5f, 3f); lineTo(10.5f, 3.7f)
                curveTo(7.6f, 4.4f, 6f, 6.9f, 6f, 10f); lineTo(6f, 16f); lineTo(4f, 18f)
                lineTo(4f, 19f); lineTo(20f, 19f); lineTo(20f, 18f); close()
            }
        }.build()
    }

    suspend fun load(cursor: Long? = null) {
        if (session?.communityMember != true || busy) return
        busy = true; error = ""
        try {
            val response = BiodiaryApi.notifications(session, cursor)
            val array = response.optJSONArray("notifications")
            val page = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
            rows = (if (cursor == null) page else rows + page).distinctBy { it.getLong("id") }
            unread = response.optInt("unreadCount").coerceAtLeast(0)
            newest = response.optLong("newestId")
            before = response.optLong("nextBefore").takeIf { response.optBoolean("hasMore") && it > 0 }
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "通知を取得できませんでした" }
        finally { busy = false }
    }

    LaunchedEffect(session?.token, session?.communityMember, lifecycleState) {
        if (session?.communityMember != true || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        while (true) {
            if (!open) load()
            delay(60_000)
        }
    }

    IconButton(onClick = { open = true; scope.launch { load() } }) {
        BadgedBox(badge = {
            if (unread > 0) Badge(containerColor = Color(0xFFCE4242), contentColor = Color.White) {
                Text(if (unread > 99) "99+" else "$unread")
            }
        }) { Icon(bell, contentDescription = "通知を開く・未読${unread}件", tint = Color.White) }
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text("通知") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                if (session?.communityMember != true) {
                    Text(if (session == null) "ログインすると通知を確認できます。" else "コミュニティに参加すると、コメント・返信・いいねの通知を確認できます。")
                } else {
                    Text("未読${unread}件", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = !busy, onClick = { scope.launch { load() } }) { Text("更新") }
                        TextButton(enabled = !busy && unread > 0 && newest > 0, onClick = {
                            scope.launch {
                                busy = true; error = ""
                                val snapshot = newest
                                try {
                                    val result = BiodiaryApi.readNotifications(session, throughId = snapshot)
                                    unread = result.optInt("unreadCount").coerceAtLeast(0)
                                    rows = rows.map { if (it.getLong("id") <= snapshot) JSONObject(it.toString()).put("read", true) else it }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message ?: "既読にできませんでした" }
                                finally { busy = false }
                            }
                        }) { Text("すべて既読にする") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF9B9B), style = MaterialTheme.typography.bodySmall)
                    if (loaded && rows.isEmpty() && !busy) Text("通知はまだありません。")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 410.dp)) {
                        items(rows, key = { it.getLong("id") }) { item ->
                            val actor = item.optJSONObject("actor") ?: JSONObject()
                            val member = members.firstOrNull { it.accountId == actor.optString("accountId") }
                            val name = member?.displayName?.takeIf(String::isNotBlank) ?: actor.optString("displayName").ifBlank { actor.optString("loginName", "メンバー") }
                            val kind = item.optString("kind")
                            val text = when (kind) {
                                "like" -> "${name}さんがあなたの投稿にいいねしました"
                                "reply" -> "${name}さんがあなたのコメントに返信しました"
                                else -> "${name}さんがあなたの投稿にコメントしました"
                            }
                            Row(Modifier.fillMaxWidth().background(if (item.optBoolean("read")) Color.Transparent else gold.copy(alpha = .08f))
                                .clickable(enabled = !busy, onClickLabel = "通知の投稿を開く") {
                                    scope.launch {
                                        busy = true; error = ""
                                        try {
                                            val observation = BiodiaryApi.sharedObservation(context, session, item.getString("observationId"))
                                            val result = BiodiaryApi.readNotifications(session, ids = listOf(item.getLong("id")))
                                            unread = result.optInt("unreadCount").coerceAtLeast(0)
                                            rows = rows.map { if (it.getLong("id") == item.getLong("id")) JSONObject(it.toString()).put("read", true) else it }
                                            open = false
                                            currentOnObservation(observation)
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { error = e.message ?: "投稿を開けませんでした" }
                                        finally { busy = false }
                                    }
                                }.padding(vertical = 10.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF3B663B)), contentAlignment = Alignment.Center) {
                                    if (member?.avatarPath?.let { File(it).isFile } == true) AsyncImage(File(member.avatarPath), null,
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    else Text(name.firstOrNull()?.uppercase() ?: "?")
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(text, style = MaterialTheme.typography.bodySmall)
                                    Text(item.optString("observationName"), color = gold, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (item.optString("commentBody").isNotBlank()) Text(item.optString("commentBody"), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(item.optLong("createdAt"))), style = MaterialTheme.typography.labelSmall)
                                }
                                if (!item.optBoolean("read")) Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(gold))
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = .12f))
                        }
                        if (before != null) item {
                            TextButton(enabled = !busy, onClick = { scope.launch { load(before) } }, modifier = Modifier.fillMaxWidth()) { Text("以前の通知を読み込む") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("閉じる") } },
    )
}
