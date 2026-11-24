package jp.morinote.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TagDefinition(val name: String, val colorHex: String)

data class UserTagGroup(
    val id: String,
    val name: String,
    val tags: List<TagDefinition> = emptyList(),
)

object TagGroupStore {
    const val LEGACY_GROUP_ID = "legacy-notes"
    const val LEGACY_GROUP_NAME = "メモ"
    private const val DATA_FILE = "tag-groups.json"

    suspend fun load(context: Context): List<UserTagGroup> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, DATA_FILE)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val tags = item.optJSONArray("tags") ?: JSONArray()
                UserTagGroup(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = item.optString("name").ifBlank { "名称未設定" },
                    tags = (0 until tags.length()).mapNotNull { tagIndex ->
                        tags.optJSONObject(tagIndex)?.let { tag ->
                            TagDefinition(tag.optString("name"), tag.optString("colorHex", "#719B78"))
                        }
                    }.filter { it.name.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(context: Context, groups: List<UserTagGroup>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        groups.forEach { group ->
            array.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("tags", JSONArray().apply {
                    group.tags.forEach { tag ->
                        put(JSONObject().apply {
                            put("name", tag.name)
                            put("colorHex", tag.colorHex)
                        })
                    }
                })
            })
        }
        File(context.filesDir, DATA_FILE).writeText(array.toString())
    }

    fun newId(): String = UUID.randomUUID().toString()
}
