package jp.morinote.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object ObservationStore {
    private const val DATA_FILE = "observations.json"

    suspend fun load(context: Context): List<Observation> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, DATA_FILE)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val legacyTags = item.optJSONArray("tags")?.let { tags ->
                    (0 until tags.length()).mapNotNull { tags.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty()
                val tagValuesJson = item.optJSONObject("tagValues")
                val tagValues = if (tagValuesJson != null) {
                    buildMap {
                        val keys = tagValuesJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val values = tagValuesJson.optJSONArray(key) ?: continue
                            put(key, (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) })
                        }
                    }
                } else if (legacyTags.isNotEmpty()) {
                    mapOf(TagGroupStore.LEGACY_GROUP_ID to legacyTags)
                } else emptyMap()
                Observation(
                    id = item.getString("id"),
                    name = item.optString("name"),
                    photoPath = item.getString("photoPath"),
                    extraPhotoPaths = item.optJSONArray("extraPhotoPaths")?.let { paths ->
                        (0 until minOf(3, paths.length())).map { paths.optString(it) }
                    }.orEmpty(),
                    dateMillis = item.getLong("dateMillis"),
                    postedAt = item.optLong("postedAt", item.getLong("dateMillis")),
                    likeCount = item.optInt("likeCount", 0).coerceAtLeast(0),
                    likedByMe = item.optBoolean("likedByMe", false),
                    remoteUpdatedAt = item.optLong("remoteUpdatedAt", 0),
                    latitude = item.getDouble("latitude"),
                    longitude = item.getDouble("longitude"),
                    place = item.optString("place"),
                    comment = item.optString("comment"),
                    category = LifeCategory.fromStorage(item.optString("category", LifeCategory.OTHER.name)),
                    species = item.optString("species"),
                    speciesScientificName = item.optString("speciesScientificName"),
                    genus = item.optString("genus"),
                    family = item.optString("family"),
                    order = item.optString("order"),
                    tags = legacyTags,
                    tagValues = tagValues,
                    isShared = item.optBoolean("isShared", false),
                    ownerAccountId = item.optString("ownerAccountId"),
                    ownerLoginName = item.optString("ownerLoginName"),
                    localAccountId = item.optString("localAccountId"),
                    locationPrivate = item.optBoolean("locationPrivate", false),
                    prefecture = item.optString("prefecture"),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun preparePhotos(context: Context, sources: List<Uri>, observation: Observation): Observation = withContext(Dispatchers.IO) {
        require(sources.size in 1..4) { "写真は1件につき1〜4枚で登録してください" }
        val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
        val created = mutableListOf<File>()
        try {
            val paths = sources.map { source ->
                val existing = if (source.scheme == "file") File(source.path.orEmpty()) else null
                if (existing?.isFile == true && existing.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)) {
                    existing.absolutePath
                } else {
                    val bitmap = requireNotNull(decodeOrientedBitmap(context, source)) { "写真を読み込めませんでした" }
                    try {
                        val scale = minOf(1f, 1800f / maxOf(bitmap.width, bitmap.height))
                        val compact = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap,
                            (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                        val destination = File(photoDir, "${observation.id}-${UUID.randomUUID()}.jpg")
                        created.add(destination)
                        try {
                            destination.outputStream().use { output -> check(compact.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output)) }
                        } finally { if (compact !== bitmap) compact.recycle() }
                        destination.absolutePath
                    } finally { bitmap.recycle() }
                }
            }
            observation.copy(photoPath = paths.first(), extraPhotoPaths = paths.drop(1))
        } catch (error: Exception) { created.forEach { it.delete() }; throw error }
    }

    suspend fun add(context: Context, sources: List<Uri>, observation: Observation): Observation = withContext(Dispatchers.IO) {
        val prepared = preparePhotos(context, sources, observation)
        val saved = prepared.copy(
            postedAt = observation.postedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            localAccountId = observation.localAccountId.ifBlank { BiodiarySessionStore.load(context)?.accountId.orEmpty() })
        val all = load(context) + saved
        write(context, all)
        saved
    }

    suspend fun update(context: Context, observation: Observation) = withContext(Dispatchers.IO) {
        val existing = load(context).toMutableList()
        val index = existing.indexOfFirst { it.id == observation.id }
        val previous = existing.getOrNull(index)
        val owned = observation.copy(localAccountId = observation.localAccountId.ifBlank {
            observation.ownerAccountId.ifBlank { BiodiarySessionStore.load(context)?.accountId.orEmpty() }
        })
        if (index >= 0) existing[index] = owned else existing.add(owned)
        write(context, existing)
        previous?.photoPaths?.filterNot { it in owned.photoPaths }?.forEach { File(it).delete() }
    }

    suspend fun replaceAll(context: Context, observations: List<Observation>) = withContext(Dispatchers.IO) {
        write(context, observations)
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        val existing = load(context)
        val deleted = existing.firstOrNull { it.id == id }
        write(context, existing.filterNot { it.id == id })
        deleted?.photoPaths?.forEach { File(it).delete() }
    }

    suspend fun removeTagGroup(context: Context, groupId: String): List<Observation> = withContext(Dispatchers.IO) {
        load(context).map { observation ->
            observation.copy(
                tagValues = observation.tagValues - groupId,
                tags = if (groupId == TagGroupStore.LEGACY_GROUP_ID) emptyList() else observation.tags,
            )
        }.also { write(context, it) }
    }

    suspend fun removeTag(context: Context, groupId: String, tagName: String): List<Observation> = withContext(Dispatchers.IO) {
        load(context).map { observation ->
            val updatedValues = observation.tagValues[groupId].orEmpty().filterNot { it == tagName }
            observation.copy(
                tagValues = observation.tagValues.toMutableMap().apply {
                    if (updatedValues.isEmpty()) remove(groupId) else put(groupId, updatedValues)
                },
                tags = if (groupId == TagGroupStore.LEGACY_GROUP_ID) observation.tags.filterNot { it == tagName } else observation.tags,
            )
        }.also { write(context, it) }
    }

    private fun write(context: Context, items: List<Observation>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("photoPath", item.photoPath)
                put("extraPhotoPaths", JSONArray(item.extraPhotoPaths))
                put("dateMillis", item.dateMillis)
                put("postedAt", item.postedAt)
                put("likeCount", item.likeCount)
                put("likedByMe", item.likedByMe)
                put("remoteUpdatedAt", item.remoteUpdatedAt)
                put("latitude", item.latitude)
                put("longitude", item.longitude)
                put("place", item.place)
                put("comment", item.comment)
                put("category", item.category.name)
                put("species", item.species)
                put("speciesScientificName", item.speciesScientificName)
                put("genus", item.genus)
                put("family", item.family)
                put("order", item.order)
                put("tags", JSONArray(item.tags))
                put("tagValues", JSONObject().apply {
                    item.tagValues.forEach { (groupId, values) -> put(groupId, JSONArray(values)) }
                })
                put("isShared", item.isShared)
                put("ownerAccountId", item.ownerAccountId)
                put("ownerLoginName", item.ownerLoginName)
                put("localAccountId", item.localAccountId)
                put("locationPrivate", item.locationPrivate)
                put("prefecture", item.prefecture)
            })
        }
        File(context.filesDir, DATA_FILE).writeText(json.toString())
    }

    fun newId(): String = UUID.randomUUID().toString()
}
