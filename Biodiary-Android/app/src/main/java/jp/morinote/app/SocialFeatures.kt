package jp.morinote.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SocialGold = Color(0xFFD9D917)
private fun socialDate(value: Long) = if (value > 0) SimpleDateFormat("yyyy/M/d HH:mm", Locale.JAPAN).format(Date(value)) else "未取得"

@Composable
internal fun ProfileAvatar(member: CommunityMember?, onClick: () -> Unit, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Color(0xFF3B663B))
        .clickable(role = Role.Button, onClickLabel = "プロフィールを開く", onClick = onClick), contentAlignment = Alignment.Center) {
        if (member?.avatarPath?.let { File(it).isFile } == true) {
            AsyncImage(File(member.avatarPath), contentDescription = "${member.displayName.ifBlank { member.loginName }}のプロフィール",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else Text(member?.displayName?.ifBlank { member.loginName }?.firstOrNull()?.uppercase() ?: "?", color = Color.White)
    }
}

private data class PostComment(val id: String, val parentId: String?, val body: String, val createdAt: Long,
    val deleted: Boolean, val author: CommunityMember, val editedAt: Long = 0)

private fun readComment(item: JSONObject): PostComment {
    val author = item.optJSONObject("author") ?: JSONObject()
    return PostComment(item.getString("id"), item.optString("parentId").takeUnless { it.isBlank() || it == "null" },
        item.optString("body"), item.optLong("createdAt"), item.optBoolean("deleted"),
        CommunityMember(author.optString("accountId"), author.optString("loginName"), "", displayName = author.optString("displayName")), item.optLong("editedAt"))
}

private fun commentThreads(comments: List<PostComment>): List<Pair<PostComment, Int>> {
    val ids = comments.map { it.id }.toSet()
    val sorted = comments.sortedWith(compareBy<PostComment> { it.createdAt }.thenBy { it.id })
    val children = sorted.groupBy { it.parentId }
    val stack = ArrayDeque<Pair<PostComment, Int>>()
    sorted.filter { it.parentId !in ids }.asReversed().forEach { stack.addLast(it to 0) }
    val result = mutableListOf<Pair<PostComment, Int>>()
    val visited = mutableSetOf<String>()
    while (stack.isNotEmpty()) {
        val (comment, depth) = stack.removeLast()
        if (!visited.add(comment.id)) continue
        result.add(comment to depth)
        children[comment.id].orEmpty().asReversed().forEach { stack.addLast(it to depth + 1) }
    }
    return result
}

@Composable
internal fun ObservationLikes(observation: Observation, session: BiodiarySession?, onChanged: (JSONObject) -> Unit, modifier: Modifier = Modifier) {
    if (!observation.isShared || observation.ownerAccountId.isBlank() || session?.communityMember != true) return
    val scope = rememberCoroutineScope()
    var count by remember(observation.id, session.token) { mutableStateOf(observation.likeCount) }
    var liked by remember(observation.id, session.token) { mutableStateOf(false) }
    var available by remember(observation.id, session.token) { mutableStateOf(false) }
    var busy by remember(observation.id, session.token) { mutableStateOf(true) }
    var error by remember(observation.id, session.token) { mutableStateOf("") }
    val own = observation.ownerAccountId == session.accountId
    fun applyReaction(reaction: JSONObject) {
        count = reaction.optInt("likeCount").coerceAtLeast(0)
        liked = reaction.optBoolean("likedByMe")
        onChanged(reaction)
    }
    LaunchedEffect(observation.id, session.token) {
        busy = true
        runCatching { BiodiaryApi.likes(session, observation.id) }
            .onSuccess { applyReaction(it); available = true }
            .onFailure { error = it.message ?: "いいねを取得できませんでした" }
        busy = false
    }
        Button(onClick = {
            if (busy) return@Button
            val method = if (!available || error.isNotBlank()) "GET" else if (own) return@Button else if (liked) "DELETE" else "PUT"
            scope.launch {
                busy = true;error = ""
                runCatching { BiodiaryApi.likes(session, observation.id, method) }
                    .onSuccess { applyReaction(it); available = true }.onFailure { error = it.message ?: "いいねを変更できませんでした" }
                busy = false
            }
        }, enabled = !busy && ((!own && available) || error.isNotBlank()), shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xEB181A14), contentColor = SocialGold,
                disabledContainerColor = Color(0xEB181A14), disabledContentColor = SocialGold),
            modifier = modifier.size(56.dp).semantics {
                contentDescription = if (error.isNotBlank()) "$error。タップして再取得" else "いいね $count 件" +
                    if (own) "。自分の投稿にはいいねを付けられません" else if (liked) "。タップして取り消し" else "。タップしていいね"
            }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (error.isNotBlank()) "!" else if (liked) "♥" else "♡", fontSize = 22.sp)
                Text(if (error.isNotBlank()) "再取得" else count.toString(), fontSize = 11.sp)
            }
        }
}

@Composable
internal fun ObservationComments(observation: Observation, session: BiodiarySession?, members: List<CommunityMember>,
    onProfileClick: (String) -> Unit) {
    Text("コメント", style = MaterialTheme.typography.titleMedium, color = SocialGold, modifier = Modifier.padding(top = 20.dp))
    if (!observation.isShared || observation.ownerAccountId.isBlank() || session?.communityMember != true) {
        Text("コミュニティに共有した投稿には、自分やメンバーがコメントできます。", style = MaterialTheme.typography.bodySmall)
        return
    }
    val scope = rememberCoroutineScope()
    var comments by remember(observation.id, session.token) { mutableStateOf<List<PostComment>>(emptyList()) }
    var draft by remember(observation.id, session.token) { mutableStateOf("") }
    var reply by remember(observation.id, session.token) { mutableStateOf<PostComment?>(null) }
    var nextBefore by remember(observation.id, session.token) { mutableStateOf<String?>(null) }
    var busy by remember(observation.id, session.token) { mutableStateOf(false) }
    var error by remember(observation.id, session.token) { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<PostComment?>(null) }
    var editTarget by remember(observation.id, session.token) { mutableStateOf<PostComment?>(null) }
    var editDraft by remember(observation.id, session.token) { mutableStateOf("") }
    suspend fun load(before: String? = null) {
        val page = BiodiaryApi.comments(session, observation.id, before)
        val array = page.optJSONArray("comments")
        val fetched = if (array == null) emptyList() else List(array.length()) { readComment(array.getJSONObject(it)) }
        comments = if (before == null) fetched else (fetched + comments).distinctBy { it.id }
        nextBefore = page.optString("nextBefore").takeUnless { it.isBlank() || it == "null" }
    }
    fun refresh(before: String? = null) {
        if (busy) return
        scope.launch {
            busy = true; error = ""
            runCatching { load(before) }.onFailure { error = it.message ?: "コメントを取得できませんでした" }
            busy = false
        }
    }
    LaunchedEffect(observation.id, session.token) {
        busy = true
        runCatching { load() }.onFailure { error = it.message ?: "コメントを取得できませんでした" }
        busy = false
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { refresh() }, enabled = !busy) { Text("更新", color = Color.White) }
    }
    if (error.isNotBlank()) Text(error, color = Color(0xFFFFAAAA), style = MaterialTheme.typography.bodySmall)
    nextBefore?.let { before ->
        OutlinedButton(onClick = { refresh(before) }, enabled = !busy) { Text("以前のコメントを読み込む") }
    }
    if (comments.isEmpty()) Text(if (busy) "読み込み中…" else "まだコメントはありません。", style = MaterialTheme.typography.bodySmall)
    commentThreads(comments).forEach { (comment, depth) ->
        val author = members.firstOrNull { it.accountId == comment.author.accountId } ?: comment.author
        Column(Modifier.fillMaxWidth().padding(start = minOf(depth, 2).times(10).dp, top = 12.dp)
            .background(Color.White.copy(alpha = .04f), RoundedCornerShape(8.dp)).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileAvatar(author, { onProfileClick(author.accountId) })
                Column {
                    Text(author.displayName.ifBlank { author.loginName }, style = MaterialTheme.typography.labelMedium, color = SocialGold)
                    Text(socialDate(comment.createdAt) + if (comment.editedAt > 0 && !comment.deleted) "（編集済み）" else "", style = MaterialTheme.typography.labelSmall)
                }
            }
            comment.parentId?.let { parentId ->
                val parent = comments.firstOrNull { it.id == parentId }
                Text("返信先：${parent?.author?.displayName?.ifBlank { parent.author.loginName } ?: "以前のコメント"}",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
            }
            Text(if (comment.deleted) "このコメントは削除されました" else comment.body, modifier = Modifier.padding(top = 6.dp))
            if (!comment.deleted) Row {
                TextButton(onClick = { reply = comment }, enabled = !busy) { Text("返信", color = Color.White) }
                if (comment.author.accountId == session.accountId) {
                    TextButton(onClick = { editTarget = comment; editDraft = comment.body; error = "" }, enabled = !busy) { Text("編集", color = Color.White) }
                    TextButton(onClick = { deleteTarget = comment }, enabled = !busy) { Text("削除", color = Color(0xFFFFAAAA)) }
                }
            }
        }
    }
    reply?.let { target ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${target.author.displayName.ifBlank { target.author.loginName }}への返信", modifier = Modifier.weight(1f))
            TextButton(onClick = { reply = null }, enabled = !busy) { Text("解除", color = Color.White) }
        }
    }
    OutlinedTextField(draft, onValueChange = { if (it.length <= 1000) draft = it }, label = { Text(if (reply == null) "コメントを書く" else "返信を書く") },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !busy, minLines = 2, maxLines = 6,
        supportingText = { Text("${draft.length}/1000") })
    Button(onClick = {
        if (busy) return@Button
        scope.launch {
            busy = true; error = ""
            runCatching { BiodiaryApi.postComment(session, observation.id, draft.trim(), reply?.id) }
                .onSuccess { result ->
                    comments = comments + readComment(result.getJSONObject("comment"))
                    draft = ""; reply = null
                }.onFailure { error = it.message ?: "コメントを送信できませんでした" }
            busy = false
        }
    }, enabled = !busy && draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "処理中…" else "送信") }
    editTarget?.let { target ->
        AlertDialog(onDismissRequest = {}, title = { Text("コメントを編集") },
            text = {
                Column {
                    OutlinedTextField(editDraft, onValueChange = { if (it.length <= 1000) editDraft = it },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 3, maxLines = 7,
                        supportingText = { Text("${editDraft.length}/1000") })
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFFAAAA))
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    busy = true; error = ""
                    runCatching { BiodiaryApi.editComment(session, observation.id, target.id, editDraft.trim()) }
                        .onSuccess { response ->
                            val edited = readComment(response.getJSONObject("comment"))
                            comments = comments.map { if (it.id == edited.id) edited else it }
                            editTarget = null; editDraft = ""
                        }.onFailure { error = it.message ?: "編集内容を保存できませんでした" }
                    busy = false
                }
            }, enabled = !busy && editDraft.isNotBlank()) { Text("保存", color = Color.White) } },
            dismissButton = { TextButton(onClick = { editTarget = null; editDraft = ""; error = "" }, enabled = !busy) { Text("キャンセル", color = Color.White) } })
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { if (!busy) deleteTarget = null }, title = { Text("コメントを削除") },
            text = { Text("このコメントを削除しますか？返信は残ります。") },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    busy = true
                    runCatching { BiodiaryApi.deleteComment(session, observation.id, target.id) }
                        .onSuccess { response ->
                            val deleted = readComment(response.getJSONObject("comment"))
                            comments = comments.map { if (it.id == deleted.id) deleted else it }
                            deleteTarget = null
                        }.onFailure { error = it.message ?: "削除できませんでした"; deleteTarget = null }
                    busy = false
                }
            }, enabled = !busy) { Text("削除", color = Color(0xFFFFAAAA)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !busy) { Text("キャンセル", color = Color.White) } })
    }
}

@Composable
internal fun MemberProfileDialog(accountId: String, session: BiodiarySession, cachedMember: CommunityMember?,
    onDismiss: () -> Unit, onObservation: (Observation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var member by remember(accountId) { mutableStateOf(cachedMember) }
    var posts by remember(accountId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember(accountId) { mutableStateOf<Int?>(null) }
    var nextOffset by remember(accountId) { mutableStateOf(0) }
    var hasMore by remember(accountId) { mutableStateOf(false) }
    var busy by remember(accountId) { mutableStateOf(false) }
    var error by remember(accountId) { mutableStateOf("") }
    suspend fun loadPosts(offset: Int) {
        val response = BiodiaryApi.memberPosts(session, accountId, offset)
        val array = response.optJSONArray("observations")
        val page = if (array == null) emptyList() else List(array.length()) { array.getJSONObject(it) }
        posts = if (offset == 0) page else (posts + page).distinctBy { it.optString("id") }
        total = response.optInt("total");nextOffset = response.optInt("nextOffset");hasMore = response.optBoolean("hasMore")
    }
    fun refresh(offset: Int = 0) {
        if (busy) return
        scope.launch {
            busy = true;error = ""
            runCatching { loadPosts(offset) }.onFailure { error = it.message ?: "投稿を取得できませんでした" }
            busy = false
        }
    }
    LaunchedEffect(accountId, session.token) {
        busy = true
        runCatching { member = BiodiaryApi.memberProfile(context, session, accountId) }
            .onFailure { error = it.message ?: "プロフィールを取得できませんでした" }
        runCatching { loadPosts(0) }.onFailure { error = it.message ?: "投稿を取得できませんでした" }
        busy = false
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("プロフィール") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileAvatar(member, {}, 64.dp)
                    Column {
                        Text(member?.displayName?.ifBlank { member?.loginName.orEmpty() } ?: "読み込み中…",
                            style = MaterialTheme.typography.titleLarge, color = SocialGold)
                    }
                }
                Text("アカウントID：$accountId", style = MaterialTheme.typography.bodySmall)
                Text("登録日時：${socialDate(member?.registeredAt ?: 0)}", style = MaterialTheme.typography.bodySmall)
                Text(member?.message?.ifBlank { "ひとことはまだありません" } ?: "", style = MaterialTheme.typography.bodyMedium)
                Text("投稿件数（共有）：${total?.toString() ?: "取得中"}", color = SocialGold)
                if (error.isNotBlank()) Text(error, color = Color(0xFFFFAAAA))
                OutlinedButton(onClick = { refresh() }, enabled = !busy) { Text("投稿一覧を更新") }
                if (posts.isEmpty()) Text(if (busy) "読み込み中…" else "共有された投稿はありません。")
                posts.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { item ->
                            RemotePostTile(item, session, enabled = !busy, modifier = Modifier.weight(1f)) {
                                scope.launch {
                                    busy = true;error = ""
                                    runCatching { BiodiaryApi.sharedObservation(context, session, item.getString("id")) }
                                        .onSuccess(onObservation).onFailure { error = it.message ?: "投稿を開けませんでした" }
                                    busy = false
                                }
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (hasMore) OutlinedButton(onClick = { refresh(nextOffset) }, enabled = !busy) { Text("さらに読み込む") }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる", color = Color.White) } })
}

@Composable
private fun RemotePostTile(item: JSONObject, session: BiodiarySession, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val category = LifeCategory.fromStorage(item.optString("category"))
    var path by remember(item.optString("id")) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.optString("id"), session.token) { path = runCatching { BiodiaryApi.postThumbnail(context, session, item) }.getOrNull() }
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
        .background(Color.White.copy(alpha = .05f)).padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).border(2.dp, category.color, RoundedCornerShape(8.dp))) {
            path?.let { AsyncImage(File(it), contentDescription = item.optString("name"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(item.optString("name"), color = SocialGold, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
