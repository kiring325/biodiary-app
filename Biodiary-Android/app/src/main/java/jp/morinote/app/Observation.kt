package jp.morinote.app

import java.util.Calendar

enum class Season(val label: String, val range: String) {
    SPRING("春", "3–5月"), SUMMER("夏", "6–8月"), AUTUMN("秋", "9–11月"), WINTER("冬", "12–2月");

    companion object {
        fun fromDate(timeMillis: Long): Season {
            val month = Calendar.getInstance().apply { timeInMillis = timeMillis }.get(Calendar.MONTH) + 1
            return when (month) {
                in 3..5 -> SPRING
                in 6..8 -> SUMMER
                in 9..11 -> AUTUMN
                else -> WINTER
            }
        }
    }
}

enum class LifeCategory(val label: String) {
    PLANT("植物"), BIRD("鳥類"), INSECT("昆虫"), MAMMAL("哺乳類"), FISH("魚類"),
    REPTILE("爬虫類"), AMPHIBIAN("両生類"), FUNGI("菌類"), OTHER("その他"),
    LEGACY_REPTILE_AMPHIBIAN("爬虫類・両生類（旧記録）");

    companion object {
        val selectableEntries: List<LifeCategory>
            get() = entries.filterNot { it == LEGACY_REPTILE_AMPHIBIAN }

        fun fromStorage(value: String): LifeCategory = when (value) {
            "REPTILE_AMPHIBIAN" -> LEGACY_REPTILE_AMPHIBIAN
            else -> entries.firstOrNull { it.name == value || it.label == value } ?: OTHER
        }
    }
}

enum class TimeBand(val label: String) {
    MORNING("朝"), DAY("昼"), EVENING("夕方"), NIGHT("夜");

    companion object {
        fun fromDate(timeMillis: Long): TimeBand {
            val hour = Calendar.getInstance().apply { timeInMillis = timeMillis }.get(Calendar.HOUR_OF_DAY)
            return when (hour) {
                in 5..10 -> MORNING
                in 11..15 -> DAY
                in 16..18 -> EVENING
                else -> NIGHT
            }
        }
    }
}

data class Observation(
    val id: String,
    val name: String,
    val photoPath: String,
    val dateMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val place: String = "",
    val comment: String = "",
    val category: LifeCategory = LifeCategory.OTHER,
    val species: String = "",
    val speciesScientificName: String = "",
    val genus: String = "",
    val family: String = "",
    val order: String = "",
    // Old versions stored free-form values here. ObservationStore migrates them to the legacy group.
    val tags: List<String> = emptyList(),
    val tagValues: Map<String, List<String>> = emptyMap(),
    val isShared: Boolean = false,
    val ownerAccountId: String = "",
    val ownerLoginName: String = "",
    val localAccountId: String = "",
    val locationPrivate: Boolean = false,
    val prefecture: String = "",
    val extraPhotoPaths: List<String> = emptyList(),
    val postedAt: Long = 0,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val remoteUpdatedAt: Long = 0,
) {
    val season: Season get() = Season.fromDate(dateMillis)
    val photoPaths: List<String> get() = (listOf(photoPath) + extraPhotoPaths).take(4)
}
