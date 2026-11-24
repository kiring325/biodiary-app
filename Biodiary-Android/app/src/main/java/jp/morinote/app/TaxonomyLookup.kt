package jp.morinote.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class TaxonomyCandidate(
    val key: Int,
    val scientificName: String,
    val canonicalName: String,
    val rank: String,
    val japaneseName: String,
)

data class TaxonomyLineage(
    val species: String,
    val speciesScientificName: String,
    val genus: String,
    val family: String,
    val order: String,
)

object TaxonomyLookup {
    private const val API_ROOT = "https://api.gbif.org/v1"
    private const val GBIF_BACKBONE_KEY = "d7dddbf4-2cf0-4f39-9b2a-bb099caae36c"
    private val japaneseScript = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]")

    private fun japaneseOnly(value: String): String? = value.trim().takeIf {
        it.isNotEmpty() && japaneseScript.containsMatchIn(it)
    }

    suspend fun searchJapaneseName(query: String): List<TaxonomyCandidate> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        fun parse(response: JSONObject): List<TaxonomyCandidate> {
            val results = response.optJSONArray("results") ?: JSONArray()
            return (0 until results.length()).mapNotNull { index ->
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                val key = item.optInt("key", -1)
                if (key < 0) return@mapNotNull null
                TaxonomyCandidate(
                    key = key,
                    scientificName = item.optString("scientificName"),
                    canonicalName = item.optString("canonicalName").ifBlank { item.optString("scientificName") },
                    rank = item.optString("rank"),
                    japaneseName = japaneseOnly(item.optString("vernacularName"))
                        ?: japaneseOnly(query).orEmpty(),
                )
            }.distinctBy { it.key }
        }

        val base = "$API_ROOT/species/search?q=$encoded&qField=VERNACULAR&rank=SPECIES&status=ACCEPTED&datasetKey=$GBIF_BACKBONE_KEY&limit=8"
        val vernacularResults = parse(getObject(base))
        if (vernacularResults.isNotEmpty()) vernacularResults
        else parse(getObject(base.replace("qField=VERNACULAR", "qField=SCIENTIFIC")))
    }

    suspend fun loadLineage(candidate: TaxonomyCandidate, searchedName: String): TaxonomyLineage = withContext(Dispatchers.IO) {
        val taxon = getObject("$API_ROOT/species/${candidate.key}")
        val parentArray = getArray("$API_ROOT/species/${candidate.key}/parents")
        val usages = (0 until parentArray.length()).mapNotNull { parentArray.optJSONObject(it) } + taxon
        val byRank = usages.associateBy { it.optString("rank").uppercase() }

        suspend fun localizedName(rank: String): String {
            val usage = byRank[rank] ?: return ""
            val scientificName = usage.optString("canonicalName").ifBlank { usage.optString("scientificName") }
            val key = usage.optInt("key", -1)
            if (rank == "SPECIES") {
                return japaneseVernacular(key)
                    ?: japaneseOnly(candidate.japaneseName)
                    ?: japaneseOnly(searchedName).orEmpty()
            }
            if (key < 0) return scientificName
            return japaneseVernacular(key) ?: scientificName
        }

        val values = coroutineScope {
            listOf("SPECIES", "GENUS", "FAMILY", "ORDER").map { rank ->
                async(Dispatchers.IO) { rank to localizedName(rank) }
            }.awaitAll().toMap()
        }
        TaxonomyLineage(
            species = values["SPECIES"].orEmpty(),
            speciesScientificName = taxon.optString("canonicalName").ifBlank { taxon.optString("scientificName") },
            genus = values["GENUS"].orEmpty(),
            family = values["FAMILY"].orEmpty(),
            order = values["ORDER"].orEmpty(),
        )
    }

    private suspend fun japaneseVernacular(key: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val names = getArray("$API_ROOT/species/$key/vernacularNames")
            val japanese = (0 until names.length()).mapNotNull { names.optJSONObject(it) }
                .filter { it.optString("language").equals("ja", ignoreCase = true) }
                .sortedByDescending { it.optBoolean("preferred") }
            japanese.firstNotNullOfOrNull { japaneseOnly(it.optString("vernacularName")) }
        }.getOrNull()
    }

    private fun getObject(address: String): JSONObject = JSONObject(request(address))

    private fun getArray(address: String): JSONArray = JSONArray(request(address))

    private fun request(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Biodiary-Android/${BuildConfig.VERSION_NAME}")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "GBIF API returned HTTP $status" }
            response
        } finally {
            connection.disconnect()
        }
    }
}
