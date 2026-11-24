package jp.morinote.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CommunityMember(
    val accountId: String,
    val loginName: String,
    val message: String,
    val avatarPath: String = "",
    val avatarUpdatedAt: Long = 0,
    val avatarUrl: String = "",
    val displayName: String = "",
    val registeredAt: Long = 0,
)

object CommunityMemberStore {
    private fun file(context: Context) = File(context.filesDir, "community_members.json")

    fun load(context: Context): List<CommunityMember> = runCatching {
        val array = JSONArray(file(context).readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            CommunityMember(
                accountId = item.optString("accountId"),
                loginName = item.optString("loginName"),
                message = item.optString("message"),
                avatarPath = item.optString("avatarPath"),
                avatarUpdatedAt = item.optLong("avatarUpdatedAt"),
                avatarUrl = item.optString("avatarUrl"),
                displayName = item.optString("displayName"),
                registeredAt = item.optLong("registeredAt"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, members: List<CommunityMember>) {
        val array = JSONArray()
        members.forEach { member ->
            array.put(JSONObject().put("accountId", member.accountId).put("loginName", member.loginName)
                .put("message", member.message).put("avatarPath", member.avatarPath)
                .put("avatarUpdatedAt", member.avatarUpdatedAt).put("avatarUrl", member.avatarUrl)
                .put("displayName", member.displayName).put("registeredAt", member.registeredAt))
        }
        file(context).writeText(array.toString())
    }
}
