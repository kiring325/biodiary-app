package jp.morinote.app

data class PrefectureCapital(val prefecture: String, val latitude: Double, val longitude: Double)

object PrefectureCapitals {
    val all = listOf(
        PrefectureCapital("北海道", 43.0618, 141.3545), PrefectureCapital("青森県", 40.8244, 140.7400),
        PrefectureCapital("岩手県", 39.7036, 141.1527), PrefectureCapital("宮城県", 38.2682, 140.8694),
        PrefectureCapital("秋田県", 39.7186, 140.1024), PrefectureCapital("山形県", 38.2404, 140.3633),
        PrefectureCapital("福島県", 37.7503, 140.4676), PrefectureCapital("茨城県", 36.3418, 140.4468),
        PrefectureCapital("栃木県", 36.5658, 139.8836), PrefectureCapital("群馬県", 36.3911, 139.0608),
        PrefectureCapital("埼玉県", 35.8617, 139.6455), PrefectureCapital("千葉県", 35.6074, 140.1065),
        PrefectureCapital("東京都", 35.6895, 139.6917), PrefectureCapital("神奈川県", 35.4478, 139.6425),
        PrefectureCapital("新潟県", 37.9161, 139.0364), PrefectureCapital("富山県", 36.6953, 137.2113),
        PrefectureCapital("石川県", 36.5947, 136.6256), PrefectureCapital("福井県", 36.0652, 136.2216),
        PrefectureCapital("山梨県", 35.6642, 138.5684), PrefectureCapital("長野県", 36.6513, 138.1810),
        PrefectureCapital("岐阜県", 35.3912, 136.7223), PrefectureCapital("静岡県", 34.9769, 138.3831),
        PrefectureCapital("愛知県", 35.1802, 136.9066), PrefectureCapital("三重県", 34.7303, 136.5086),
        PrefectureCapital("滋賀県", 35.0045, 135.8686), PrefectureCapital("京都府", 35.0212, 135.7556),
        PrefectureCapital("大阪府", 34.6863, 135.5200), PrefectureCapital("兵庫県", 34.6913, 135.1830),
        PrefectureCapital("奈良県", 34.6851, 135.8049), PrefectureCapital("和歌山県", 34.2261, 135.1675),
        PrefectureCapital("鳥取県", 35.5039, 134.2377), PrefectureCapital("島根県", 35.4723, 133.0505),
        PrefectureCapital("岡山県", 34.6551, 133.9195), PrefectureCapital("広島県", 34.3853, 132.4553),
        PrefectureCapital("山口県", 34.1859, 131.4714), PrefectureCapital("徳島県", 34.0658, 134.5593),
        PrefectureCapital("香川県", 34.3401, 134.0434), PrefectureCapital("愛媛県", 33.8416, 132.7657),
        PrefectureCapital("高知県", 33.5597, 133.5311), PrefectureCapital("福岡県", 33.6064, 130.4183),
        PrefectureCapital("佐賀県", 33.2494, 130.2988), PrefectureCapital("長崎県", 32.7503, 129.8779),
        PrefectureCapital("熊本県", 32.7898, 130.7417), PrefectureCapital("大分県", 33.2382, 131.6126),
        PrefectureCapital("宮崎県", 31.9111, 131.4239), PrefectureCapital("鹿児島県", 31.5602, 130.5581),
        PrefectureCapital("沖縄県", 26.2124, 127.6809),
    )

    fun find(name: String): PrefectureCapital? = all.firstOrNull { it.prefecture == name }
}

fun Observation.mapPoint(): PrefectureCapital =
    if (locationPrivate) PrefectureCapitals.find(prefecture)
        ?: PrefectureCapital("", latitude, longitude)
    else PrefectureCapital("", latitude, longitude)
