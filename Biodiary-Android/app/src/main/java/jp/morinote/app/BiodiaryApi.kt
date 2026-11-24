package jp.morinote.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BiodiarySession(val baseUrl: String, val token: String, val accountId: String, val displayName: String, val communityMember: Boolean = false)
data class ObservationSyncResult(val observations: List<Observation>, val changedCount: Int, val photoCount: Int, val failedPhotos: Int)

object BiodiarySessionStore {
    private const val PREFS = "biodiary_connection"
    private const val KEY_ALIAS = "biodiary_session_key"
    // 接続先は local.properties の biodiary.apiUrl から埋め込む（ソースコードには書かない）
    private val DEFAULT_BASE_URL = BuildConfig.BIODIARY_API_URL

    // 以前の版で保存された、写真サーバー（Tailscale）への直接の接続先は使わない
    private fun isLegacyPiUrl(url: String): Boolean =
        Regex("""\.ts\.net(?:[/:]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    fun load(context: Context): BiodiarySession? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("token", null)?.let { decrypt(it) } ?: return null
        val url = prefs.getString("base_url", null)?.trimEnd('/') ?: return null
        if (isLegacyPiUrl(url)) return null
        return BiodiarySession(url, token, prefs.getString("account_id", "").orEmpty(), prefs.getString("display_name", "").orEmpty(), prefs.getBoolean("community_member", false))
    }

    fun baseUrl(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("base_url", "").orEmpty().takeUnless { it.isBlank() || isLegacyPiUrl(it) }
        ?: DEFAULT_BASE_URL

    fun save(context: Context, baseUrl: String, token: String, accountId: String, displayName: String, communityMember: Boolean = false) {
        val encrypted = encrypt(token) ?: error("ログイン情報を安全に保存できませんでした")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("base_url", baseUrl.trim().trimEnd('/'))
            .putString("token", encrypted)
            .putString("account_id", accountId)
            .putString("display_name", displayName)
            .putBoolean("community_member", communityMember)
            .apply()
    }

    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .remove("token").remove("account_id").remove("display_name").remove("community_member").apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(value: String): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()
}

object BiodiaryApi {
    private data class PiTicket(val baseUrl: String, val identity: String, val expiresAt: Long, val sessionToken: String)
    @Volatile private var cachedPiTicket: PiTicket? = null

    private fun piTicket(baseUrl: String, token: String): PiTicket {
        cachedPiTicket?.let { if (it.sessionToken == token && it.expiresAt * 1000 > System.currentTimeMillis() + 15_000) return it }
        synchronized(this) {
            cachedPiTicket?.let { if (it.sessionToken == token && it.expiresAt * 1000 > System.currentTimeMillis() + 15_000) return it }
            val response = request(baseUrl, "/api/v1/auth/pi-ticket", token = token)
            return PiTicket(response.getString("baseUrl"), response.getString("identity"),
                response.getLong("expiresAt"), token).also { cachedPiTicket = it }
        }
    }

    suspend fun communityMembers(context: Context, session: BiodiarySession): List<CommunityMember> = withContext(Dispatchers.IO) {
        val response = request(session.baseUrl, "/api/v1/community/members", token = session.token)
        val items = response.optJSONArray("members") ?: JSONArray()
        val prior = CommunityMemberStore.load(context).associateBy { it.accountId }
        val members = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("accountId")
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return@mapNotNull null
            val updatedAt = item.optLong("avatarUpdatedAt")
            val cached = prior[id]
            val avatarUrl = item.optString("avatarUrl")
            val currentCache = avatarUrl.isNotBlank() && cached != null && cached.avatarUpdatedAt == updatedAt &&
                cached.avatarPath.isNotBlank() && File(cached.avatarPath).isFile
            val downloaded = if (avatarUrl.isNotBlank() && !currentCache)
                downloadAvatar(context, session, avatarUrl, id, updatedAt) else null
            val avatarPath = when {
                avatarUrl.isBlank() -> ""
                currentCache -> cached?.avatarPath.orEmpty()
                downloaded != null -> downloaded
                else -> cached?.avatarPath.orEmpty()
            }
            val cachedVersion = if (avatarUrl.isNotBlank() && !currentCache && downloaded == null)
                cached?.avatarUpdatedAt ?: 0 else updatedAt
            CommunityMember(id, item.optString("loginName"), item.optString("message"), avatarPath, cachedVersion, avatarUrl,
                item.optString("displayName"), item.optLong("registeredAt"))
        }
        CommunityMemberStore.save(context, members)
        members
    }

    suspend fun likes(session: BiodiarySession, observationId: String, method: String = "GET") = withContext(Dispatchers.IO) {
        require(method in listOf("GET", "PUT", "DELETE"))
        request(session.baseUrl, "/api/v1/observations/$observationId/likes", method, token = session.token).getJSONObject("reaction")
    }

    suspend fun reactionSummary(session: BiodiarySession) = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/observations/reactions/summary", token = session.token).optJSONArray("reactions") ?: JSONArray()
    }

    suspend fun notifications(session: BiodiarySession, before: Long? = null) = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/notifications?limit=30" + (before?.let { "&before=$it" } ?: ""), token = session.token)
    }

    suspend fun readNotifications(session: BiodiarySession, ids: List<Long> = emptyList(), throughId: Long? = null) = withContext(Dispatchers.IO) {
        val body = if (throughId != null) JSONObject().put("all", true).put("throughId", throughId)
            else JSONObject().put("ids", JSONArray(ids))
        request(session.baseUrl, "/api/v1/notifications/read", "PATCH", body, session.token)
    }

    suspend fun comments(session: BiodiarySession, observationId: String, before: String? = null) = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/observations/$observationId/comments" +
            (before?.let { "?before=$it" } ?: ""), token = session.token)
    }

    suspend fun postComment(session: BiodiarySession, observationId: String, body: String, parentId: String?) = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/observations/$observationId/comments", "POST",
            JSONObject().put("body", body).put("parentId", parentId ?: JSONObject.NULL), session.token)
    }

    suspend fun deleteComment(session: BiodiarySession, observationId: String, commentId: String) = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/observations/$observationId/comments/$commentId", "DELETE", token = session.token)
    }

    suspend fun editComment(session: BiodiarySession, observationId: String, commentId: String, body: String) = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/observations/$observationId/comments/$commentId", "PATCH",
            JSONObject().put("body", body), session.token)
    }

    suspend fun memberProfile(context: Context, session: BiodiarySession, accountId: String): CommunityMember = withContext(Dispatchers.IO) {
        require(accountId.matches(Regex("BD-[A-F0-9]{8,12}")))
        val item = request(session.baseUrl, "/api/v1/community/members/$accountId", token = session.token).getJSONObject("member")
        val cached = CommunityMemberStore.load(context).firstOrNull { it.accountId == accountId }
        val version = item.optLong("avatarUpdatedAt")
        val avatarUrl = item.optString("avatarUrl")
        val avatarPath = if (avatarUrl.isBlank()) "" else if (cached?.avatarUpdatedAt == version && File(cached.avatarPath).isFile)
            cached.avatarPath else downloadAvatar(context, session, avatarUrl, accountId, version) ?: cached?.avatarPath.orEmpty()
        CommunityMember(accountId, item.optString("loginName"), item.optString("message"), avatarPath,
            version, avatarUrl, item.optString("displayName"), item.optLong("registeredAt"))
    }

    suspend fun memberPosts(session: BiodiarySession, accountId: String, offset: Int = 0) = withContext(Dispatchers.IO) {
        require(accountId.matches(Regex("BD-[A-F0-9]{8,12}")))
        request(session.baseUrl, "/api/v1/community/members/$accountId/observations?limit=20&offset=$offset", token = session.token)
    }

    suspend fun postThumbnail(context: Context, session: BiodiarySession, item: JSONObject): String? = withContext(Dispatchers.IO) {
        val id = item.optString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        downloadPhoto(session, item.optString("photoUrl"), context, id)
    }

    suspend fun sharedObservation(context: Context, session: BiodiarySession, id: String): Observation = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val item = request(session.baseUrl, "/api/v1/observations/$id", token = session.token).getJSONObject("observation")
        check(item.optBoolean("shared")) { "この投稿は共有されていません" }
        val local = ObservationStore.load(context).toMutableList()
        val previous = local.firstOrNull { it.id == id }
        val urls = item.optJSONArray("photoUrls")?.let { array -> List(minOf(4, array.length())) { array.optString(it) } }
            .orEmpty().ifEmpty { listOf(item.optString("photoUrl")) }
        val paths = urls.mapIndexed { index, url ->
            downloadPhoto(session, url, context, if (index == 0) id else "$id-photo${index + 1}")
                ?: previous?.photoPaths?.getOrNull(index).orEmpty()
        }
        check(paths.firstOrNull()?.isNotBlank() == true) { "写真を取得できませんでした。再試行してください" }
        val values = item.optJSONObject("tagValues")
        val tagValues = buildMap<String, List<String>> {
            values?.keys()?.forEach { key ->
                val tags = values.optJSONArray(key) ?: JSONArray()
                put(key, List(tags.length()) { tags.optString(it) })
            }
        }
        var result = Observation(id = id, name = item.optString("name"), photoPath = paths[0], extraPhotoPaths = paths.drop(1),
            dateMillis = item.optLong("dateMillis"), latitude = item.optDouble("latitude"), longitude = item.optDouble("longitude"),
            place = item.optString("place"), comment = item.optString("comment"), category = LifeCategory.fromStorage(item.optString("category")),
            species = item.optString("species"), speciesScientificName = item.optString("speciesScientificName"),
            genus = item.optString("genus"), family = item.optString("family"), order = item.optString("order"), tagValues = tagValues,
            isShared = true, ownerAccountId = item.optJSONObject("owner")?.optString("accountId").orEmpty(),
            ownerLoginName = item.optJSONObject("owner")?.optString("loginName").orEmpty(),
            localAccountId = item.optJSONObject("owner")?.optString("accountId").orEmpty(),
            locationPrivate = item.optBoolean("locationPrivate"), prefecture = item.optString("prefecture"),
            postedAt = item.optLong("createdAt"), likeCount = item.optInt("likeCount"), likedByMe = item.optBoolean("likedByMe"))
        if (previous != null && previous.locationPrivate && result.locationPrivate && result.ownerAccountId == session.accountId)
            result = result.copy(latitude = previous.latitude, longitude = previous.longitude, place = previous.place)
        val index = local.indexOfFirst { it.id == id }
        if (index >= 0) local[index] = result else local.add(result)
        ObservationStore.replaceAll(context, local)
        result
    }

    suspend fun uploadAvatar(session: BiodiarySession, path: String) = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 512)
        val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("アイコン画像を読み込めませんでした")
        val side = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(cropped, 256, 256, true)
        val image = java.io.ByteArrayOutputStream().apply { small.compress(Bitmap.CompressFormat.JPEG, 82, this) }.toByteArray()
        if (small !== cropped) small.recycle()
        if (cropped !== source) cropped.recycle()
        source.recycle()
        val boundary = "Biodiary-${System.nanoTime()}"
        val ticket = piTicket(session.baseUrl, session.token)
        val connection = (URL(ticket.baseUrl.trimEnd('/') + "/api/v1/account/me/avatar").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; connectTimeout = 12_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("X-Biodiary-Identity", ticket.identity)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { output ->
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"avatar\"; filename=\"avatar.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                output.write(image)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error(runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                    .ifBlank { "アイコンの共有に失敗しました（$code）" })
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            check(JSONObject(body).optJSONObject("account")?.optString("avatarUrl").orEmpty().isNotBlank()) {
                "サーバーがアイコンの保存を確認できませんでした"
            }
            request(session.baseUrl, "/api/v1/account/me/avatar-updated", "POST", token = session.token)
        } finally { connection.disconnect() }
    }

    private fun downloadAvatar(context: Context, session: BiodiarySession, path: String, id: String, version: Long): String? {
        if (!path.startsWith("/api/v1/community/members/") || path.contains("..") || !path.endsWith("/avatar")) return null
        val ticket = piTicket(session.baseUrl, session.token)
        val connection = (URL(ticket.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 20_000
            setRequestProperty("X-Biodiary-Identity", ticket.identity)
        }
        try {
            if (connection.responseCode !in 200..299 || connection.contentLengthLong > 1_000_000) return null
            val directory = File(context.filesDir, "community_avatars").apply { mkdirs() }
            val destination = File(directory, "$id-$version.jpg")
            connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
            return destination.absolutePath
        } catch (_: Exception) { return null } finally { connection.disconnect() }
    }

    suspend fun status(baseUrl: String) = withContext(Dispatchers.IO) {
        request(baseUrl, "/api/v1/status").optString("service")
    }

    suspend fun login(baseUrl: String, loginName: String, password: String): JSONObject = withContext(Dispatchers.IO) {
        request(baseUrl, "/api/v1/auth/login", "POST", JSONObject().put("loginName", loginName).put("password", password))
    }

    suspend fun register(baseUrl: String, loginName: String, password: String, displayName: String): JSONObject = withContext(Dispatchers.IO) {
        request(baseUrl, "/api/v1/auth/register", "POST", JSONObject()
            .put("loginName", loginName.trim()).put("password", password).put("displayName", displayName.trim()))
    }

    suspend fun joinCommunity(session: BiodiarySession, inviteCode: String): JSONObject = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/community/join", "POST", JSONObject().put("inviteCode", inviteCode.trim()), session.token)
            .getJSONObject("account").also { cachedPiTicket = null }
    }

    suspend fun logout(session: BiodiarySession) = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/auth/logout", "POST", token = session.token)
        cachedPiTicket = null
    }

    suspend fun accountMe(session: BiodiarySession): JSONObject = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/account/me", token = session.token).getJSONObject("account")
    }

    suspend fun markGuideSeen(session: BiodiarySession) = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/account/me/guide-seen", "POST", token = session.token)
    }

    suspend fun accountAvatar(context: Context, session: BiodiarySession, account: JSONObject): String? = withContext(Dispatchers.IO) {
        val path = account.optString("avatarUrl")
        if (path.isBlank()) null else downloadAvatar(context, session, path, session.accountId,
            account.optLong("avatarUpdatedAt"))
    }

    suspend fun updateAccount(session: BiodiarySession, displayName: String, message: String) = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/account/me", "PATCH", JSONObject().put("displayName", displayName).put("message", message), session.token)
    }

    suspend fun communitySettings(session: BiodiarySession): JSONObject = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/community", token = session.token).getJSONObject("community")
    }

    suspend fun renameCommunity(session: BiodiarySession, name: String): JSONObject = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/community", "PATCH", JSONObject().put("name", name.trim()), session.token).getJSONObject("community")
    }

    suspend fun putTagGroups(session: BiodiarySession, groups: List<UserTagGroup>) = withContext(Dispatchers.IO) {
        val encoded = JSONArray().apply {
            groups.forEach { group ->
                put(JSONObject().put("id", group.id).put("name", group.name).put("tags", JSONArray().apply {
                    group.tags.forEach { tag -> put(JSONObject().put("name", tag.name).put("colorHex", tag.colorHex)) }
                }))
            }
        }
        request(session.baseUrl, "/api/v1/tag-groups", "PUT", JSONObject().put("groups", encoded), session.token)
    }

    suspend fun deleteObservation(session: BiodiarySession, id: String) = withContext(Dispatchers.IO) {
        request(session.baseUrl, "/api/v1/observations/$id", "DELETE", token = session.token)
    }

    suspend fun syncObservations(context: Context, session: BiodiarySession): ObservationSyncResult = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences("biodiary_observation_sync", Context.MODE_PRIVATE)
        val cursorKey = "cursor_${session.accountId}"
        val since = preferences.getLong(cursorKey, 0).coerceAtLeast(0)
        val items = JSONArray()
        var visibleIds: Set<String>? = null
        var serverTime = 0L
        var offset = 0
        var batchLength: Int
        do {
            val page = request(session.baseUrl, "/api/v1/observations?limit=200&offset=$offset&since=$since", token = session.token)
            if (offset == 0) {
                serverTime = page.optLong("serverTime", 0)
                page.optJSONArray("visibleIds")?.let { ids ->
                    visibleIds = (0 until ids.length()).map { ids.optString(it) }.toSet()
                }
            }
            val batch = page.optJSONArray("observations") ?: JSONArray()
            batchLength = batch.length()
            for (index in 0 until batch.length()) items.put(batch.getJSONObject(index))
            offset += batchLength
        } while (batchLength == 200)
        val tagGroups = TagGroupStore.load(context).associateBy { it.id }.toMutableMap()
        val existingById = ObservationStore.load(context).associateBy { it.id }
        var photoCount = 0
        var failedPhotos = 0
        val remote = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            item.optJSONArray("tagGroups")?.let { groups ->
                for (groupIndex in 0 until groups.length()) {
                    val group = groups.optJSONObject(groupIndex) ?: continue
                    val remoteTags = group.optJSONArray("tags") ?: JSONArray()
                    val tags = (0 until remoteTags.length()).mapNotNull tagLoop@{ tagIndex ->
                        val tag = remoteTags.optJSONObject(tagIndex) ?: return@tagLoop null
                        TagDefinition(tag.optString("name"), tag.optString("colorHex", "#719B78"))
                    }
                    val id = group.optString("id")
                    if (id.isNotBlank()) tagGroups[id] = UserTagGroup(id, group.optString("name"), tags)
                }
            }
            val id = item.optString("id")
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return@mapNotNull null
            val urls = item.optJSONArray("photoUrls")?.let { values ->
                (0 until minOf(4, values.length())).map { values.optString(it) }
            }.orEmpty().ifEmpty { listOf(item.optString("photoUrl")) }
            val previous = existingById[id]
            val revision = item.optLong("updatedAt", 0)
            var itemPhotoFailed = false
            val paths = urls.mapIndexed { index, url ->
                val cached = previous?.photoPaths?.getOrNull(index).orEmpty()
                if (revision > 0 && previous?.remoteUpdatedAt == revision && File(cached).isFile) cached
                else {
                    val downloaded = downloadPhoto(session, url, context, if (index == 0) id else "$id-photo${index + 1}")
                    if (downloaded == null) { failedPhotos++; itemPhotoFailed = true } else photoCount++
                    downloaded ?: cached
                }
            }
            val photoPath = paths.firstOrNull().orEmpty()
            if (photoPath.isBlank()) return@mapNotNull null
            val valuesObject = item.optJSONObject("tagValues")
            val tagValues = buildMap {
                if (valuesObject != null) {
                    val keys = valuesObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val values = valuesObject.optJSONArray(key) ?: continue
                        put(key, (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) })
                    }
                }
            }
            val legacyTags = item.optJSONArray("tags")?.let { tags ->
                (0 until tags.length()).mapNotNull { tags.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            Observation(
                id = id, name = item.optString("name"), photoPath = photoPath, extraPhotoPaths = paths.drop(1),
                dateMillis = item.optLong("dateMillis"), latitude = item.optDouble("latitude"), longitude = item.optDouble("longitude"),
                postedAt = item.optLong("createdAt"), likeCount = item.optInt("likeCount"), likedByMe = item.optBoolean("likedByMe"),
                place = item.optString("place"), comment = item.optString("comment"),
                category = LifeCategory.fromStorage(item.optString("category")), species = item.optString("species"),
                speciesScientificName = item.optString("speciesScientificName"), genus = item.optString("genus"),
                family = item.optString("family"), order = item.optString("order"), tags = legacyTags,
                tagValues = tagValues, isShared = item.optBoolean("shared"),
                ownerAccountId = item.optJSONObject("owner")?.optString("accountId").orEmpty(),
                ownerLoginName = item.optJSONObject("owner")?.optString("loginName").orEmpty(),
                localAccountId = item.optJSONObject("owner")?.optString("accountId").orEmpty(),
                locationPrivate = item.optBoolean("locationPrivate", false),
                prefecture = item.optString("prefecture"),
                remoteUpdatedAt = if (!itemPhotoFailed && paths.all { File(it).isFile }) revision else 0,
            )
        }
        val local = ObservationStore.load(context).toMutableList()
        val staleRemote = local.filter { visibleIds != null && it.ownerAccountId.isNotBlank() && it.id !in visibleIds!! }
        val obsoletePaths = staleRemote.flatMap { it.photoPaths }.toMutableSet()
        local.removeAll(staleRemote.toSet())
        remote.forEach { serverItem ->
            val index = local.indexOfFirst { it.id == serverItem.id }
            if (index >= 0) {
                val previous = local[index]
                obsoletePaths.addAll(previous.photoPaths.filterNot { it in serverItem.photoPaths })
                local[index] = if (serverItem.locationPrivate && previous.locationPrivate &&
                    serverItem.ownerAccountId == session.accountId) {
                    serverItem.copy(latitude = previous.latitude, longitude = previous.longitude, place = previous.place)
                } else serverItem
            } else local.add(serverItem)
        }
        TagGroupStore.save(context, tagGroups.values.toList())
        ObservationStore.replaceAll(context, local)
        val retainedPaths = local.flatMap { it.photoPaths }.toSet()
        obsoletePaths.filterNot { it in retainedPaths }.forEach { File(it).delete() }
        if (serverTime > 0 && failedPhotos == 0) preferences.edit().putLong(cursorKey, serverTime).apply()
        ObservationSyncResult(local, remote.size, photoCount, failedPhotos)
    }

    private fun downloadPhoto(session: BiodiarySession, relativePath: String, context: Context, id: String): String? {
        if (!relativePath.startsWith("/api/v1/observations/") || relativePath.contains("..")) return null
        val ticket = piTicket(session.baseUrl, session.token)
        val connection = (URL(ticket.baseUrl.trimEnd('/') + relativePath).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 30_000
            setRequestProperty("X-Biodiary-Identity", ticket.identity)
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val extension = when (connection.contentType?.substringBefore(';')?.lowercase()) {
                "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg"
            }
            val directory = File(context.filesDir, "community_photos").apply { mkdirs() }
            val destination = File(directory, "$id.$extension")
            val temporary = File.createTempFile(".download-", ".tmp", directory)
            try {
                connection.inputStream.use { input -> temporary.outputStream().use { input.copyTo(it) } }
                require(temporary.length() > 0) { "写真が空です" }
                check(temporary.renameTo(destination)) { "写真を保存できませんでした" }
                return destination.absolutePath
            } finally { temporary.delete() }
        } catch (_: Exception) {
            return null
        } finally { connection.disconnect() }
    }

    suspend fun setObservationShared(session: BiodiarySession, observation: Observation, shared: Boolean,
                                     groups: List<UserTagGroup> = emptyList(), syncGroups: Boolean = true) = withContext(Dispatchers.IO) {
        val data = observationJson(observation).put("shared", shared)
        if (shared && syncGroups) putTagGroups(session, groups)
        val creating = observation.ownerAccountId != session.accountId
        run {
            val photos = observation.photoPaths.map(::File)
            require(photos.size in 1..4 && photos.all { it.isFile }) { "写真ファイルが見つかりません。全写真を同期してから再試行してください" }
            val boundary = "Biodiary-${System.nanoTime()}"
            val endpoint = if (creating) "/api/v1/observations" else "/api/v1/observations/${observation.id}"
            val ticket = piTicket(session.baseUrl, session.token)
            if (photos.size > 1) {
                val statusConnection = URL(ticket.baseUrl.trimEnd('/') + "/api/v1/status").openConnection() as HttpURLConnection
                try {
                    statusConnection.connectTimeout = 12_000; statusConnection.readTimeout = 12_000
                    val status = JSONObject(statusConnection.inputStream.bufferedReader().use { it.readText() })
                    require(status.optInt("maxPhotosPerObservation", 1) >= photos.size) { "複数写真を共有するには写真サーバーの更新が必要です" }
                } finally { statusConnection.disconnect() }
            }
            val connection = (URL(ticket.baseUrl.trimEnd('/') + endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = if (creating) "POST" else "PUT"
                connectTimeout = 12_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("X-Biodiary-Identity", ticket.identity)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { output ->
                    fun field(name: String, value: String) {
                        output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
                    }
                    field("metadata", data.toString())
                    photos.forEachIndexed { index, photo ->
                        val mime = if (observation.locationPrivate) "image/jpeg" else when (photo.extension.lowercase()) {
                            "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg"
                        }
                        val fieldName = if (index == 0) "photo" else "photo${index + 1}"
                        val filename = if (observation.locationPrivate) "observation.jpg" else photo.name
                        output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$fieldName\"; filename=\"$filename\"\r\nContent-Type: $mime\r\n\r\n".toByteArray(Charsets.UTF_8))
                        if (observation.locationPrivate) output.write(photoWithoutExif(photo))
                        else photo.inputStream().use { it.copyTo(output) }
                        output.write("\r\n".toByteArray(Charsets.UTF_8))
                    }
                    output.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val responseText = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val response = runCatching { JSONObject(responseText) }.getOrElse { JSONObject() }
                if (code !in 200..299) error(response.optString("error").ifBlank { "共有に失敗しました（$code）" })
            } finally { connection.disconnect() }
        }
    }

    private fun request(baseUrl: String, path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): JSONObject {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://", true) || normalized.startsWith("http://", true)) { "接続先URLは https:// から入力してください" }
        if (normalized.startsWith("http://", true) && !normalized.contains("localhost") && !normalized.contains("127.0.0.1")) {
            error("インターネット経由の接続には https:// が必要です")
        }
        val onPi = token != null && (path.startsWith("/api/v1/tag-groups") || path.startsWith("/api/v1/observations") || path.startsWith("/api/v1/notifications") ||
            path.substringBefore('?').matches(Regex("/api/v1/community/members/BD-[A-F0-9]{8,12}/observations")))
        val ticket = if (onPi) piTicket(normalized, token!!) else null
        val connection = (URL((ticket?.baseUrl?.trimEnd('/') ?: normalized) + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (ticket != null) setRequestProperty("X-Biodiary-Identity", ticket.identity)
            else token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) error(json.optString("error").ifBlank { "サーバーとの通信に失敗しました（$code）" })
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun observationJson(item: Observation) = JSONObject().apply {
        val point = item.mapPoint()
        put("id", item.id); put("name", item.name); put("dateMillis", item.dateMillis); put("photoCount", item.photoPaths.size)
        put("latitude", point.latitude); put("longitude", point.longitude)
        put("place", if (item.locationPrivate) "" else item.place)
        put("locationPrivate", item.locationPrivate); put("prefecture", item.prefecture)
        put("comment", item.comment); put("category", item.category.name); put("species", item.species)
        put("speciesScientificName", item.speciesScientificName); put("genus", item.genus); put("family", item.family); put("order", item.order)
        put("tags", JSONArray(item.tags))
        put("tagValues", JSONObject().apply { item.tagValues.forEach { (key, values) -> put(key, JSONArray(values)) } })
    }

    private fun photoWithoutExif(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1800) sample *= 2
        val source = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("写真を読み込めませんでした")
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            }
        }
        val oriented = if (matrix.isIdentity()) source else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val bytes = java.io.ByteArrayOutputStream().use { stream ->
            oriented.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            stream.toByteArray()
        }
        if (oriented !== source) oriented.recycle()
        source.recycle()
        return bytes
    }
}
