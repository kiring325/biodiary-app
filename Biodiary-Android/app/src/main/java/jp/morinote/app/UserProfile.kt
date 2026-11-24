package jp.morinote.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class UserProfile(
    val name: String,
    val avatarPath: String,
    val accountId: String,
    val message: String,
    val registeredAt: Long,
    val loginName: String = "",
)

object UserProfileStore {
    private const val PREFS = "user_profile"

    fun load(context: Context): UserProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("account_id", null) ?: "BD-${UUID.randomUUID().toString().take(8).uppercase()}"
        val registeredAt = prefs.getLong("registered_at", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        prefs.edit().putString("account_id", id).putLong("registered_at", registeredAt).apply()
        return UserProfile(
            name = prefs.getString("name", null) ?: "自然観察者",
            avatarPath = prefs.getString("avatar_path", "").orEmpty(),
            accountId = id,
            message = prefs.getString("message", "").orEmpty(),
            registeredAt = registeredAt,
            loginName = prefs.getString("login_name", "").orEmpty(),
        )
    }

    fun save(context: Context, profile: UserProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", profile.name)
            .putString("avatar_path", profile.avatarPath)
            .putString("account_id", profile.accountId)
            .putString("message", profile.message)
            .putLong("registered_at", profile.registeredAt)
            .putString("login_name", profile.loginName)
            .apply()
    }

    suspend fun importAvatar(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.filesDir, "profile").apply { mkdirs() }
            val destination = File(directory, "avatar-${UUID.randomUUID()}.jpg")
            val source = requireNotNull(decodeOrientedBitmap(context, uri)) { "画像を読み込めませんでした" }
            val output = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            try {
                val side = minOf(source.width, source.height)
                val left = (source.width - side) / 2
                val top = (source.height - side) / 2
                Canvas(output).apply {
                    drawColor(android.graphics.Color.rgb(59, 102, 59))
                    drawBitmap(source, Rect(left, top, left + side, top + side), Rect(0, 0, 256, 256),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                destination.outputStream().use { check(output.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                output.recycle()
                source.recycle()
            }
            destination.absolutePath
        }.getOrNull()
    }
}
