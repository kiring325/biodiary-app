package jp.morinote.app

import android.app.DatePickerDialog
import org.json.JSONObject
import android.app.TimePickerDialog
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.exifinterface.media.ExifInterface
import androidx.webkit.WebViewAssetLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private val Spring = Color(0xFFB86E81)
private val Summer = Color(0xFF42815E)
private val Autumn = Color(0xFFB8803D)
private val Winter = Color(0xFF5D87A2)
private val Forest = Color(0xFF203620)
private val Paper = Color(0xFF203620)
private val PanelGreen = Color(0xFF3B663B)
private val NavigationOlive = Color(0xFF232916)
private val ObservationDark = Color(0xFF141611)
private val DialogBackground = Color(0xFF121A00)
private val CardText = Color(0xFFD9D917)
private val MutedWhite = Color.White

private enum class MainTab { MAP, ENCYCLOPEDIA, COMMUNITY, PERSONAL }
private enum class CatalogSort(val label: String) {
    NEWEST("新しい順"), NAME("あいうえお順"), OLDEST("古い順")
}

private enum class BuiltInGroup(val id: String, val label: String) {
    SEASON("season", "季節"), YEAR("year", "撮影年"), CATEGORY("category", "ジャンル"), MONTH("month", "月"), TIME("time", "時間帯"), LIKES("likes", "いいね"), SHARING("sharing", "共有")
}

private data class FilterGroup(val id: String, val label: String, val builtIn: BuiltInGroup? = null, val userGroup: UserTagGroup? = null)
private data class FilterTag(val key: String, val label: String, val colorHex: String)

private fun filterGroups(userGroups: List<UserTagGroup>) =
    BuiltInGroup.entries.map { FilterGroup(it.id, it.label, builtIn = it) } +
        userGroups.map { FilterGroup(it.id, it.name, userGroup = it) }

private fun optionsForGroup(observations: List<Observation>, group: FilterGroup, members: List<CommunityMember>): List<FilterTag> {
    val custom = group.userGroup
    if (custom != null) return custom.tags.map { FilterTag(it.name, it.name, it.colorHex) }
    return when (group.builtIn) {
        BuiltInGroup.SEASON -> Season.entries.map { FilterTag(it.name, "${it.label} ${it.range}", it.color.toMapHex()) }
        BuiltInGroup.YEAR -> observations.map { Calendar.getInstance().apply { timeInMillis = it.dateMillis }.get(Calendar.YEAR) }
            .distinct().sortedDescending().map { FilterTag(it.toString(), "${it}年", "#6D9FBF") }
        BuiltInGroup.CATEGORY -> LifeCategory.entries.filter { category -> observations.any { it.category == category } }
            .map { category -> FilterTag(category.name, category.label, category.colorHex) }
        BuiltInGroup.MONTH -> observations.map { Calendar.getInstance().apply { timeInMillis = it.dateMillis }.get(Calendar.MONTH) + 1 }
            .distinct().sorted().map { FilterTag(it.toString(), "${it}月", "#81947E") }
        BuiltInGroup.TIME -> TimeBand.entries.map { band ->
            FilterTag(band.name, band.label, band.color.toMapHex())
        }
        BuiltInGroup.LIKES -> listOf(FilterTag("mine", "自分がいいねした投稿", "#D9D917"))
        BuiltInGroup.SHARING -> members.map { FilterTag(it.accountId, it.displayName.ifBlank { it.loginName }, "#91BDF0") }
        null -> emptyList()
    }
}

private fun matchesGroup(observation: Observation, group: FilterGroup, key: String): Boolean {
    group.userGroup?.let { return key in observation.tagValues[it.id].orEmpty() }
    return when (group.builtIn) {
        BuiltInGroup.SEASON -> observation.season.name == key
        BuiltInGroup.YEAR -> Calendar.getInstance().apply { timeInMillis = observation.dateMillis }.get(Calendar.YEAR).toString() == key
        BuiltInGroup.CATEGORY -> observation.category.name == key
        BuiltInGroup.MONTH -> (Calendar.getInstance().apply { timeInMillis = observation.dateMillis }.get(Calendar.MONTH) + 1).toString() == key
        BuiltInGroup.TIME -> TimeBand.fromDate(observation.dateMillis).name == key
        BuiltInGroup.LIKES -> key == "mine" && observation.isShared && observation.likedByMe
        BuiltInGroup.SHARING -> observation.isShared && observation.ownerAccountId == key
        null -> false
    }
}

private fun tagColor(hex: String): Color = runCatching {
    val parsed = android.graphics.Color.parseColor(hex)
    Color(android.graphics.Color.argb(
        android.graphics.Color.alpha(parsed),
        (android.graphics.Color.red(parsed) * .85f).toInt(),
        (android.graphics.Color.green(parsed) * .85f).toInt(),
        (android.graphics.Color.blue(parsed) * .85f).toInt(),
    ))
}.getOrDefault(Forest)

internal val LifeCategory.colorHex: String
    get() = when (this) {
        LifeCategory.PLANT -> "#739B72"
        LifeCategory.BIRD -> "#7A94C2"
        LifeCategory.INSECT -> "#D99648"
        LifeCategory.MAMMAL -> "#BB7797"
        LifeCategory.FISH -> "#4DB6AC"
        LifeCategory.REPTILE -> "#A0B85A"
        LifeCategory.AMPHIBIAN -> "#6FC2D0"
        LifeCategory.FUNGI -> "#AC8ACB"
        LifeCategory.OTHER -> "#A5A5A5"
        LifeCategory.LEGACY_REPTILE_AMPHIBIAN -> "#BEB45E"
    }

internal val LifeCategory.color: Color
    get() = tagColor(colorHex)

private val Season.color: Color
    get() = when (this) {
        Season.SPRING -> Spring
        Season.SUMMER -> Summer
        Season.AUTUMN -> Autumn
        Season.WINTER -> Winter
    }

private val TimeBand.color: Color
    get() = when (this) {
        TimeBand.MORNING -> Color(0xFFB88C37)
        TimeBand.DAY -> Color(0xFFC46E3A)
        TimeBand.EVENING -> Color(0xFFAB5870)
        TimeBand.NIGHT -> Color(0xFF4C5B7B)
    }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Forest,
                onPrimary = Color.White,
                background = Paper,
                onBackground = Color.White,
                surface = DialogBackground,
                onSurface = Color.White,
                surfaceVariant = DialogBackground,
                onSurfaceVariant = Color.White,
                surfaceContainerLowest = DialogBackground,
                surfaceContainerLow = DialogBackground,
                surfaceContainer = DialogBackground,
                surfaceContainerHigh = DialogBackground,
                surfaceContainerHighest = DialogBackground,
            )) {
                NatureAlbumApp()
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NatureAlbumApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guidePreferences = remember { context.getSharedPreferences("biodiary_onboarding_guide", Context.MODE_PRIVATE) }
    val observations = remember { mutableStateListOf<Observation>() }
    val userTagGroups = remember { mutableStateListOf<UserTagGroup>() }
    val communityMembers = remember { mutableStateListOf<CommunityMember>().apply { addAll(CommunityMemberStore.load(context)) } }
    var selectedGroupIds by remember { mutableStateOf(setOf(BuiltInGroup.SEASON.id, BuiltInGroup.TIME.id)) }
    var selectedGroupValues by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var showCreateTagGroup by remember { mutableStateOf(false) }
    var manageTagGroupId by remember { mutableStateOf<String?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<UserTagGroup?>(null) }
    var deleteTagTarget by remember { mutableStateOf<Pair<UserTagGroup, TagDefinition>?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var observationMapCenter by remember { mutableStateOf(GeoPoint(36.2, 138.25)) }
    var observationMapZoom by remember { mutableStateOf(5.15f) }
    var observationMapFocusRequest by remember { mutableStateOf(0L) }
    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }
    var photoToCrop by remember { mutableStateOf<Uri?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showPhotoMenu by remember { mutableStateOf(false) }
    var showReleaseHistory by remember { mutableStateOf(false) }
    var avatarToCrop by remember { mutableStateOf<Uri?>(null) }
    var selectedTab by remember { mutableStateOf(if (BiodiarySessionStore.load(context) == null) MainTab.PERSONAL else MainTab.MAP) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var profileId by remember { mutableStateOf<String?>(null) }
    var reactionRevision by remember { mutableLongStateOf(0L) }
    val reactionRevisions = remember { mutableMapOf<String, Long>() }
    var editingObservation by remember { mutableStateOf<Observation?>(null) }
    var deleteTarget by remember { mutableStateOf<Observation?>(null) }
    var sharingTarget by remember { mutableStateOf<Observation?>(null) }
    var showBulkShare by remember { mutableStateOf(false) }
    var bulkShareSelection by remember { mutableStateOf(setOf<String>()) }
    var bulkShareBusy by remember { mutableStateOf(false) }
    var bulkShareStatus by remember { mutableStateOf("") }
    var sharingMessage by remember { mutableStateOf("") }
    var userProfile by remember { mutableStateOf(UserProfileStore.load(context)) }
    var serverSession by remember { mutableStateOf(BiodiarySessionStore.load(context)) }
    var showGuideFor by remember { mutableStateOf<String?>(null) }
    var avatarSyncAttemptedToken by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverSession?.token, serverSession?.communityMember, selectedTab) {
        val activeSession = serverSession ?: return@LaunchedEffect
        val account = runCatching { BiodiaryApi.accountMe(activeSession) }.getOrNull()
            ?: return@LaunchedEffect
        val isMember = account.optBoolean("communityMember", false)
        if (activeSession.communityMember != isMember) {
            val updatedSession = activeSession.copy(communityMember = isMember)
            BiodiarySessionStore.save(context, updatedSession.baseUrl, updatedSession.token,
                updatedSession.accountId, updatedSession.displayName, isMember)
            serverSession = updatedSession
        }
        var members = if (isMember) runCatching { BiodiaryApi.communityMembers(context, activeSession) }.getOrDefault(emptyList())
            else emptyList()
        val ownAvatarMissing = members.firstOrNull { it.accountId == activeSession.accountId }?.avatarUrl.isNullOrBlank()
        if (isMember && avatarSyncAttemptedToken != activeSession.token && ownAvatarMissing &&
            userProfile.accountId == activeSession.accountId && userProfile.avatarPath.isNotBlank() &&
            File(userProfile.avatarPath).isFile) {
            avatarSyncAttemptedToken = activeSession.token
            runCatching {
                BiodiaryApi.uploadAvatar(activeSession, userProfile.avatarPath)
                BiodiaryApi.communityMembers(context, activeSession)
            }.onSuccess { members = it }
                .onFailure { sharingMessage = "端末内のアイコンを共有できませんでした：${it.message}" }
        }
        communityMembers.clear()
        communityMembers.addAll(members)
        val ownAvatar = members.firstOrNull { it.accountId == activeSession.accountId }?.avatarPath.orEmpty()
            .ifBlank { runCatching { BiodiaryApi.accountAvatar(context, activeSession, account) }.getOrNull().orEmpty() }
        userProfile = userProfile.copy(
            accountId = activeSession.accountId,
            loginName = account.optString("loginName"),
            name = account.optString("displayName").ifBlank { userProfile.name },
            message = account.optString("message"),
            registeredAt = account.optLong("registeredAt").takeIf { it > 0 } ?: userProfile.registeredAt,
            avatarPath = ownAvatar.ifBlank {
                if (userProfile.accountId == activeSession.accountId) userProfile.avatarPath else ""
            },
        )
        UserProfileStore.save(context, userProfile)
        val guideKey = "guide_v1_${activeSession.accountId}"
        val syncedKey = "guide_synced_v1_${activeSession.accountId}"
        if (account.optLong("guideSeenAt") > 0L) {
            guidePreferences.edit().putBoolean(guideKey, true).putBoolean(syncedKey, true).apply()
        } else {
            if (!guidePreferences.getBoolean(guideKey, false)) {
                guidePreferences.edit().putBoolean(guideKey, true).apply()
                showGuideFor = activeSession.accountId
            }
            if (!guidePreferences.getBoolean(syncedKey, false)) {
                runCatching { BiodiaryApi.markGuideSeen(activeSession) }
                    .onSuccess { guidePreferences.edit().putBoolean(syncedKey, true).apply() }
            }
        }
    }

    LaunchedEffect(serverSession?.accountId) {
        showGuideFor = null
        val loaded = ObservationStore.load(context)
        val previousAccountId = userProfile.accountId.ifBlank { serverSession?.accountId.orEmpty() }
        val assigned = loaded.map { observation ->
            if (observation.localAccountId.isNotBlank()) observation
            else observation.copy(localAccountId = observation.ownerAccountId.ifBlank { previousAccountId })
        }
        if (assigned != loaded) ObservationStore.replaceAll(context, assigned)
        observations.clear()
        observations.addAll(assigned)
        selectedId = null
        profileId = null
        detailId = null
        detailId = null
    }

    LaunchedEffect(Unit) {
        userTagGroups.addAll(TagGroupStore.load(context))
        val legacyValues = ObservationStore.load(context).flatMap { it.tagValues[TagGroupStore.LEGACY_GROUP_ID].orEmpty() }.distinct()
        if (legacyValues.isNotEmpty() && userTagGroups.none { it.id == TagGroupStore.LEGACY_GROUP_ID }) {
            val legacyGroup = UserTagGroup(
                id = TagGroupStore.LEGACY_GROUP_ID,
                name = TagGroupStore.LEGACY_GROUP_NAME,
                tags = legacyValues.mapIndexed { index, name ->
                    TagDefinition(name, listOf("#7A94C2", "#739B72", "#D99648", "#BB7797")[index % 4])
                },
            )
            userTagGroups.add(legacyGroup)
            TagGroupStore.save(context, userTagGroups.toList())
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photoToCrop = uri
    }
    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) photoToCrop = cameraUri
    }
    val profilePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) avatarToCrop = uri
    }

    val accountObservations = serverSession?.let { session ->
        observations.filter { it.localAccountId == session.accountId ||
            (session.communityMember && it.isShared && it.ownerAccountId.isNotBlank()) }
    }.orEmpty()
    val selected = accountObservations.firstOrNull { it.id == selectedId }
    val allFilterGroups = filterGroups(userTagGroups.toList())
    val visibleObservations = accountObservations.filter { observation ->
        allFilterGroups.filter { it.id in selectedGroupIds }.all { group ->
            val values = selectedGroupValues[group.id].orEmpty()
            values.isEmpty() || values.any { matchesGroup(observation, group, it) }
        }
    }
    var mapPins by remember { mutableStateOf<List<MapPin>>(emptyList()) }
    LaunchedEffect(visibleObservations, communityMembers.toList()) {
        val members = communityMembers.toList()
        mapPins = withContext(Dispatchers.IO) {
            val memberById = members.associateBy { it.accountId }
            val avatarById = memberById.mapValues { (_, member) ->
                member.avatarPath.takeIf(String::isNotBlank)?.let(::photoDataUrl)
            }
            visibleObservations.map { observation ->
                val point = observation.mapPoint()
                MapPin(
                    id = observation.id,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    frameColor = observation.category.color.toMapHex(),
                    photoDataUrl = photoDataUrl(observation.photoPath),
                    ownerAvatarDataUrl = avatarById[observation.ownerAccountId].takeIf { observation.isShared },
                    ownerInitial = if (observation.isShared && observation.ownerAccountId.isNotBlank())
                        memberById[observation.ownerAccountId]?.let { it.displayName.ifBlank { it.loginName }.firstOrNull()?.uppercase() } ?: "?" else null,
                )
            }
        }
    }

    Scaffold(containerColor = Paper, topBar = {
        TopAppBar(title = {
            Column {
                Text("いきもの手帖", fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("コミュニティでシェアする自然アルバム", style = MaterialTheme.typography.labelSmall, color = MutedWhite)
            }
        }, actions = {
            NotificationBell(serverSession, communityMembers.toList(), onObservation = { observation ->
                val index = observations.indexOfFirst { it.id == observation.id }
                if (index >= 0) observations[index] = observation else observations.add(observation)
                profileId = null
                selectedTab = MainTab.ENCYCLOPEDIA
                detailId = observation.id
            })
            TextButton(onClick = { showReleaseHistory = true }) {
                Text("ver${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }, colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = PanelGreen, titleContentColor = Color.White))
    }, bottomBar = {
        if (serverSession != null) {
        NavigationBar(containerColor = NavigationOlive, contentColor = Color.White) {
            val navigationItemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.White,
                selectedTextColor = Color.White,
                unselectedIconColor = MutedWhite,
                unselectedTextColor = MutedWhite,
                indicatorColor = Color.White.copy(alpha = .14f),
            )
            NavigationBarItem(
                selected = selectedTab == MainTab.MAP,
                onClick = { selectedTab = MainTab.MAP },
                colors = navigationItemColors,
                icon = { Text("🗺️") }, label = { Text("地図") },
            )
            NavigationBarItem(
                selected = selectedTab == MainTab.ENCYCLOPEDIA,
                onClick = { selectedTab = MainTab.ENCYCLOPEDIA },
                colors = navigationItemColors,
                icon = { Text("📚") }, label = { Text("図鑑") },
            )
            NavigationBarItem(
                selected = selectedTab == MainTab.COMMUNITY,
                onClick = { selectedTab = MainTab.COMMUNITY },
                colors = navigationItemColors,
                icon = { Text("👥") }, label = { Text("コミュニティ") },
            )
            NavigationBarItem(
                selected = selectedTab == MainTab.PERSONAL,
                onClick = { selectedTab = MainTab.PERSONAL },
                colors = navigationItemColors,
                icon = { Text("👤") }, label = { Text("アカウント") },
            )
        }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (selectedTab == MainTab.ENCYCLOPEDIA) {
                CatalogScreen(accountObservations, communityMembers.toList(), selectedGroupValues[BuiltInGroup.SHARING.id].orEmpty(),
                    onProfileClick = { profileId = it },
                    onShareToggle = { id ->
                        selectedGroupIds = selectedGroupIds + BuiltInGroup.SHARING.id
                        val values = selectedGroupValues[BuiltInGroup.SHARING.id].orEmpty().toMutableSet()
                        if (!values.add(id)) values.remove(id)
                        selectedGroupValues = selectedGroupValues.toMutableMap().apply {
                            if (values.isEmpty()) remove(BuiltInGroup.SHARING.id) else put(BuiltInGroup.SHARING.id, values)
                        }
                    }) { id ->
                    detailId = id
                }
            } else if (selectedTab == MainTab.COMMUNITY) {
                CommunityScreen(serverSession, communityMembers.toList(),
                    onProfileClick = { profileId = it },
                    onSessionChanged = { updated -> serverSession = updated },
                    onSync = {
                        val activeSession = serverSession ?: error("先にログインしてください。")
                        val synchronized = BiodiaryApi.syncObservations(context, activeSession)
                        userTagGroups.clear()
                        userTagGroups.addAll(TagGroupStore.load(context))
                        observations.clear()
                        observations.addAll(synchronized.observations)
                        val members = runCatching { BiodiaryApi.communityMembers(context, activeSession) }
                            .getOrDefault(communityMembers.toList())
                        communityMembers.clear()
                        communityMembers.addAll(members)
                        synchronized
                    },
                )
            } else if (selectedTab == MainTab.PERSONAL || serverSession == null) {
                PersonalScreen(
                    profile = userProfile,
                    session = serverSession,
                    ownMember = communityMembers.firstOrNull { it.accountId == serverSession?.accountId },
                    observationCount = accountObservations.size,
                    onPickAvatar = { profilePhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onShareAvatar = {
                        val activeSession = serverSession
                        if (activeSession != null && userProfile.avatarPath.isNotBlank()) scope.launch {
                            runCatching {
                                BiodiaryApi.uploadAvatar(activeSession, userProfile.avatarPath)
                                if (activeSession.communityMember) BiodiaryApi.communityMembers(context, activeSession) else emptyList()
                            }.onSuccess { members ->
                                if (activeSession.communityMember) {
                                    communityMembers.clear()
                                    communityMembers.addAll(members)
                                }
                                sharingMessage = "アイコンを保存しました"
                            }.onFailure { sharingMessage = "アイコンを保存できませんでした：${it.message}" }
                        }
                    },
                    onSave = { updated ->
                        userProfile = updated
                        UserProfileStore.save(context, updated)
                    },
                    onSessionChanged = { updated ->
                        val wasLoggedOut = serverSession == null
                        serverSession = updated
                        if (updated != null) {
                            userProfile = userProfile.copy(name = updated.displayName, accountId = updated.accountId,
                                loginName = userProfile.loginName.takeIf { userProfile.accountId == updated.accountId }.orEmpty(),
                                avatarPath = userProfile.avatarPath.takeIf { userProfile.accountId == updated.accountId }.orEmpty())
                            UserProfileStore.save(context, userProfile)
                            if (wasLoggedOut) selectedTab = MainTab.MAP
                            scope.launch {
                                runCatching {
                                    val account = BiodiaryApi.accountMe(updated)
                                    val members = if (account.optBoolean("communityMember", false))
                                        BiodiaryApi.communityMembers(context, updated) else emptyList()
                                    account to members
                                }.onSuccess { (account, members) ->
                                    communityMembers.clear()
                                    communityMembers.addAll(members)
                                    userProfile = userProfile.copy(
                                        loginName = account.optString("loginName"),
                                        name = account.optString("displayName").ifBlank { userProfile.name },
                                        message = account.optString("message"),
                                        registeredAt = account.optLong("registeredAt").takeIf { it > 0 } ?: userProfile.registeredAt,
                                        avatarPath = members.firstOrNull { it.accountId == updated.accountId }?.avatarPath.orEmpty()
                                            .ifBlank { runCatching { BiodiaryApi.accountAvatar(context, updated, account) }.getOrNull().orEmpty() }
                                            .ifBlank { userProfile.avatarPath },
                                    )
                                    UserProfileStore.save(context, userProfile)
                                }
                            }
                        } else {
                            selectedTab = MainTab.PERSONAL
                        }
                    },
                )
            } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    SectionTitle("観察記録")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (serverSession?.communityMember == true) {
                            bulkShareSelection = emptySet()
                            bulkShareStatus = ""
                            showBulkShare = true
                        } else sharingMessage = "まとめて共有するにはコミュニティへの参加が必要です。"
                    }) { Text("まとめて共有", color = Color.White) }
                    Text("${visibleObservations.size}件", style = MaterialTheme.typography.bodySmall, color = MutedWhite)
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(allFilterGroups, key = { it.id }) { group ->
                    val active = group.id in selectedGroupIds
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = active,
                            onClick = {
                                selectedGroupIds = selectedGroupIds.toMutableSet().apply {
                                    if (!add(group.id)) remove(group.id)
                                }
                            },
                            label = { Text(group.label) },
                        )
                        group.userGroup?.let { userGroup ->
                            TextButton(onClick = { deleteGroupTarget = userGroup }) { Text("×", color = Color.White) }
                        }
                    }
                }
            }
            allFilterGroups.filter { it.id in selectedGroupIds }.forEach { group ->
                val options = optionsForGroup(accountObservations, group, communityMembers)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group.label, style = MaterialTheme.typography.labelSmall, color = MutedWhite, modifier = Modifier.padding(start = 14.dp, end = 4.dp))
                    LazyRow(
                        modifier = Modifier.weight(1f).padding(start = 0.dp, top = 1.dp, end = 8.dp, bottom = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(options, key = { it.key }) { option ->
                            val active = option.key in selectedGroupValues[group.id].orEmpty()
                            val tint = tagColor(option.colorHex)
                            FilterChip(
                                selected = active,
                                onClick = {
                                    val values = selectedGroupValues[group.id].orEmpty().toMutableSet()
                                    if (!values.add(option.key)) values.remove(option.key)
                                    selectedGroupValues = selectedGroupValues.toMutableMap().apply {
                                        if (values.isEmpty()) remove(group.id) else put(group.id, values)
                                    }
                                },
                                label = { Text(option.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = tint.copy(alpha = .2f),
                                    selectedLabelColor = Color.White,
                                ),
                                border = BorderStroke(1.dp, tint.copy(alpha = if (active) .8f else .35f)),
                            )
                        }
                    }
                    group.userGroup?.let { userGroup ->
                        TextButton(onClick = { manageTagGroupId = userGroup.id }) { Text("タグ管理") }
                    }
                }
                if (options.isEmpty()) {
                    Text("このグループにはまだタグがありません", style = MaterialTheme.typography.labelSmall, color = MutedWhite, modifier = Modifier.padding(start = 14.dp, bottom = 3.dp))
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp)) {
                OsmMap(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                    initialCenter = observationMapCenter,
                    initialZoom = observationMapZoom,
                    focusRequest = observationMapFocusRequest,
                    pins = mapPins,
                    onMapTap = {},
                    onPinTap = { selectedId = it },
                    onPinOwnerTap = { id -> accountObservations.firstOrNull { it.id == id }?.ownerAccountId?.takeIf { it.isNotBlank() }?.let { profileId = it } },
                )
                if (accountObservations.isEmpty()) {
                    Card(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 34.dp),
                        colors = CardDefaults.cardColors(containerColor = PanelGreen, contentColor = CardText),
                    ) {
                        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("最初の観察を記録しましょう", fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(5.dp))
                            Text("撮影した写真は、季節色の写真ピンになります", style = MaterialTheme.typography.bodySmall, color = CardText)
                        }
                    }
                }
            }
            selected?.let { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp).clickable { detailId = item.id },
                    colors = CardDefaults.cardColors(containerColor = ObservationDark, contentColor = CardText),
                    border = BorderStroke(1.dp, item.category.color.copy(alpha = .65f)),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(58.dp).clip(RoundedCornerShape(11.dp))) {
                                AsyncImage(
                                    model = File(item.photoPath), contentDescription = item.name, contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp)).border(3.dp, item.category.color, RoundedCornerShape(11.dp)),
                                )
                                OwnerAvatar(item, communityMembers.toList(), Modifier.align(Alignment.TopStart), onProfileClick = { profileId = it })
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text((if (item.locationPrivate) "${item.prefecture}・場所非公開" else item.place.ifBlank { "撮影地点" }) + " · " + formatDate(item.dateMillis), style = MaterialTheme.typography.labelSmall, color = item.season.color)
                                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                if (item.isShared && item.ownerAccountId.isNotBlank()) {
                                    val displayName = communityMembers.firstOrNull { it.accountId == item.ownerAccountId }
                                        ?.let { it.displayName.ifBlank { it.loginName } } ?: item.ownerLoginName.ifBlank { item.ownerAccountId }
                                    Text("共有者：$displayName", style = MaterialTheme.typography.bodySmall, color = Color.White)
                                }
                                val itemTimeBand = TimeBand.fromDate(item.dateMillis)
                                Text("${item.category.label} · ${itemTimeBand.label}の観察", style = MaterialTheme.typography.bodySmall, color = CardText)
                                val taxonomySummary = listOf(
                                    "種 ${item.species}", "属 ${item.genus}", "科 ${item.family}", "目 ${item.order}",
                                ).filterNot { it.endsWith(" ") }.joinToString(" · ")
                                if (taxonomySummary.isNotBlank()) Text(taxonomySummary, style = MaterialTheme.typography.labelSmall, color = CardText, maxLines = 2)
                                if (item.speciesScientificName.isNotBlank()) Text("種の学名 ${item.speciesScientificName}", style = MaterialTheme.typography.labelSmall, color = CardText, maxLines = 1)
                            }
                        }
                        if (item.comment.isNotBlank()) Text(item.comment, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        val recordTags = userTagGroups.flatMap { group ->
                            val values = item.tagValues[group.id].orEmpty().toSet()
                            group.tags.filter { it.name in values }
                        }
                        if (recordTags.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 5.dp)) {
                                items(recordTags) { tag ->
                                    val tint = tagColor(tag.colorHex)
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text(tag.name) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tint.copy(alpha = .2f), selectedLabelColor = Color.White),
                                        border = BorderStroke(1.dp, tint),
                                    )
                                }
                            }
                        }
                        val publicPoint = item.mapPoint()
                        TextButton(onClick = {
                            val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${publicPoint.latitude},${publicPoint.longitude}")
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }, modifier = Modifier.align(Alignment.End)) { Text("Googleマップで確認", color = Color.White) }
                        val ownedByAnotherAccount = item.ownerAccountId.isNotBlank() && serverSession?.accountId != item.ownerAccountId
                        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End) {
                            if (ownedByAnotherAccount) {
                                Text("コミュニティの共有記録", color = MutedWhite, modifier = Modifier.padding(12.dp))
                            } else {
                                TextButton(onClick = {
                                    if (serverSession?.communityMember != true) sharingMessage = "共有するには、コミュニティ画面から参加してください。"
                                    else sharingTarget = item
                                }) { Text(if (item.isShared) "共有を停止" else "共有する", color = if (item.isShared) Color(0xFFFFA0A0) else Color.White) }
                                TextButton(onClick = { editingObservation = item }) { Text("編集", color = Color.White) }
                                TextButton(onClick = { deleteTarget = item }) { Text("削除", color = Color(0xFFFF626B)) }
                            }
                        }
                    }
                }
            } ?: Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { showPhotoMenu = true }, modifier = Modifier.weight(1f)) { Text("観察を記録") }
                OutlinedButton(onClick = { showCreateTagGroup = true }, modifier = Modifier.weight(1f)) { Text("＋ タググループ") }
            }
            }
        }
    }

    if (showReleaseHistory) {
        ReleaseHistoryDialog(onDismiss = { showReleaseHistory = false })
    }

    LaunchedEffect(selectedTab, serverSession?.token) {
        val session = serverSession ?: return@LaunchedEffect
        if (selectedTab !in setOf(MainTab.MAP, MainTab.ENCYCLOPEDIA) || !session.communityMember) return@LaunchedEffect
        val revision = reactionRevision
        runCatching { BiodiaryApi.reactionSummary(session) }.onSuccess { summary ->
            val byId = (0 until summary.length()).mapNotNull { summary.optJSONObject(it) }.associateBy { it.optString("id") }
            val updates = observations.map { item ->
                byId[item.id]?.takeIf { (reactionRevisions[item.id] ?: 0L) <= revision }?.let { reaction -> item.copy(likeCount = reaction.optInt("likeCount").coerceAtLeast(0),
                    likedByMe = reaction.optBoolean("likedByMe"), postedAt = reaction.optLong("createdAt", item.postedAt)) } ?: item
            }
            observations.clear(); observations.addAll(updates)
            ObservationStore.replaceAll(context, updates)
        }
    }

    avatarToCrop?.let { uri ->
        PhotoCropDialog(
            sourceUri = uri, circularFrame = true,
            onDismiss = { avatarToCrop = null },
            onUseOriginal = {},
            onCropped = { croppedUri ->
                avatarToCrop = null
                val session = serverSession
                scope.launch {
                    val path = UserProfileStore.importAvatar(context, croppedUri)
                    if (path == null) {
                        sharingMessage = "アイコンを保存できませんでした。もう一度お試しください。"
                        return@launch
                    }
                    if (serverSession?.accountId != session?.accountId) return@launch
                    userProfile = userProfile.copy(avatarPath = path)
                    UserProfileStore.save(context, userProfile)
                    communityMembers.indexOfFirst { it.accountId == session?.accountId }.takeIf { it >= 0 }?.let { index ->
                        communityMembers[index] = communityMembers[index].copy(avatarPath = path)
                    }
                    if (session != null) {
                        runCatching {
                            BiodiaryApi.uploadAvatar(session, path)
                            if (session.communityMember) BiodiaryApi.communityMembers(context, session) else emptyList()
                        }.onSuccess { members ->
                            if (session.communityMember && serverSession?.accountId == session.accountId) {
                                communityMembers.clear()
                                communityMembers.addAll(members)
                            }
                        }.onFailure { sharingMessage = "アイコンは端末に保存しましたが、同期に失敗しました：${it.message}" }
                    }
                }
            },
        )
    }

    detailId?.let { id ->
        accountObservations.firstOrNull { it.id == id }?.let { observation ->
            val related = accountObservations.filter { it.name.trim() == observation.name.trim() }
                .sortedWith(compareByDescending<Observation> { if (it.isShared) it.likeCount else 0 }
                    .thenByDescending { it.postedAt.takeIf { time -> time > 0 } ?: it.dateMillis }
                    .thenByDescending { it.id })
            androidx.compose.runtime.key(observation.id) {
            ObservationDetailDialog(
                observation, communityMembers.toList(),
                related = related, onSelectRelated = { detailId = it },
                ownAccountId = serverSession?.accountId.orEmpty(),
                onEditRelated = { record -> detailId = null; editingObservation = record },
                session = serverSession, onProfileClick = { profileId = it },
                onReactionChanged = { reaction ->
                    reactionRevision += 1
                    reactionRevisions[observation.id] = reactionRevision
                    val index = observations.indexOfFirst { it.id == observation.id }
                    if (index >= 0) {
                        val updated = observations[index].copy(likeCount = reaction.optInt("likeCount").coerceAtLeast(0),
                            likedByMe = reaction.optBoolean("likedByMe"), postedAt = reaction.optLong("createdAt", observations[index].postedAt))
                        observations[index] = updated
                        scope.launch { ObservationStore.update(context, updated) }
                    }
                },
                onDismiss = { detailId = null },
                onShowMap = {
                    selectedGroupValues = selectedGroupValues.filter { (groupId, values) ->
                        val group = allFilterGroups.firstOrNull { it.id == groupId }
                        group == null || groupId !in selectedGroupIds || values.isEmpty() ||
                            values.any { matchesGroup(observation, group, it) }
                    }
                    val point = observation.mapPoint()
                    observationMapCenter = GeoPoint(point.latitude, point.longitude)
                    observationMapZoom = 13f
                    observationMapFocusRequest += 1
                    selectedId = observation.id
                    detailId = null
                    selectedTab = MainTab.MAP
                },
            )
            }
        }
    }

    profileId?.let { accountId ->
        serverSession?.takeIf { it.communityMember }?.let { session ->
            MemberProfileDialog(accountId, session, communityMembers.firstOrNull { it.accountId == accountId },
                onDismiss = { profileId = null },
                onObservation = { observation ->
                    val index = observations.indexOfFirst { it.id == observation.id }
                    if (index >= 0) observations[index] = observation else observations.add(observation)
                    profileId = null
                    selectedTab = MainTab.ENCYCLOPEDIA
                    detailId = observation.id
                })
        }
    }

    if (showCreateTagGroup) {
        CreateTagGroupDialog(
            onDismiss = { showCreateTagGroup = false },
            onCreate = { name ->
                val group = UserTagGroup(TagGroupStore.newId(), name)
                userTagGroups.add(group)
                selectedGroupIds = selectedGroupIds + group.id
                scope.launch { TagGroupStore.save(context, userTagGroups.toList()) }
                showCreateTagGroup = false
            },
        )
    }
    manageTagGroupId?.let { groupId ->
        userTagGroups.firstOrNull { it.id == groupId }?.let { group ->
            TagManagementDialog(
                group = group,
                onDismiss = { manageTagGroupId = null },
                onDeleteTag = { tag -> deleteTagTarget = group to tag },
            )
        }
    }
    deleteGroupTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteGroupTarget = null },
            title = { Text("タググループを削除") },
            text = { Text("「${target.name}」と、そのグループのタグをすべての観察記録から削除します。元に戻せません。") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val updatedObservations = ObservationStore.removeTagGroup(context, target.id)
                        observations.clear()
                        observations.addAll(updatedObservations)
                        userTagGroups.removeAll { it.id == target.id }
                        selectedGroupIds = selectedGroupIds - target.id
                        selectedGroupValues = selectedGroupValues - target.id
                        TagGroupStore.save(context, userTagGroups.toList())
                        if (manageTagGroupId == target.id) manageTagGroupId = null
                        deleteGroupTarget = null
                    }
                }) { Text("削除する") }
            },
            dismissButton = { TextButton(onClick = { deleteGroupTarget = null }) { Text("キャンセル", color = Color.White) } },
        )
    }
    deleteTagTarget?.let { (group, tag) ->
        val assignedCount = accountObservations.count { tag.name in it.tagValues[group.id].orEmpty() }
        AlertDialog(
            onDismissRequest = { deleteTagTarget = null },
            title = { Text("タグを削除") },
            text = { Text("「${tag.name}」を削除し、設定されている${assignedCount}件の観察記録からも外します。") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val updatedObservations = ObservationStore.removeTag(context, group.id, tag.name)
                        observations.clear()
                        observations.addAll(updatedObservations)
                        val index = userTagGroups.indexOfFirst { it.id == group.id }
                        if (index >= 0) userTagGroups[index] = userTagGroups[index].copy(tags = userTagGroups[index].tags.filterNot { it.name == tag.name })
                        val selected = selectedGroupValues[group.id].orEmpty() - tag.name
                        selectedGroupValues = selectedGroupValues.toMutableMap().apply {
                            if (selected.isEmpty()) remove(group.id) else put(group.id, selected)
                        }
                        TagGroupStore.save(context, userTagGroups.toList())
                        deleteTagTarget = null
                    }
                }) { Text("削除する") }
            },
            dismissButton = { TextButton(onClick = { deleteTagTarget = null }) { Text("キャンセル", color = Color.White) } },
        )
    }
    if (showGuideFor == serverSession?.accountId && showGuideFor != null) {
        AlertDialog(
            onDismissRequest = { showGuideFor = null },
            title = { Text("はじめての観察記録") },
            text = { Text(
                "① 地図の「観察を記録」を押し、写真を撮るか端末から選びます。\n\n" +
                    "② 生きものの名前・ジャンル・撮影場所を確認します。位置情報がなければ、現在地か地図から場所を選べます。\n\n" +
                    "③ 「保存」で自分の地図と図鑑に記録します。この時点では公開されません。\n\n" +
                    "④ 共有するにはコミュニティに参加し、記録の詳細から「共有する」を押します。複数の記録は地図の「まとめて共有」から選べます。"
            ) },
            confirmButton = { TextButton(onClick = { showGuideFor = null }) { Text("はじめる", color = Color.White) } },
        )
    }
    if (showPhotoMenu) {
        AlertDialog(
            onDismissRequest = { showPhotoMenu = false },
            title = { Text("写真を登録") },
            text = { Text("新しく撮影するか、端末の写真から選べます。") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showPhotoMenu = false
                        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
                        val file = File(directory, "${UUID.randomUUID()}.jpg")
                        file.createNewFile()
                        cameraUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        cameraCapture.launch(cameraUri!!)
                    }) { Text("カメラで撮影") }
                    TextButton(onClick = {
                        showPhotoMenu = false
                        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("端末の写真から選ぶ") }
                }
            },
            dismissButton = { TextButton(onClick = { showPhotoMenu = false }) { Text("キャンセル", color = Color.White) } },
        )
    }

    photoToCrop?.let { uri ->
        PhotoCropDialog(
            sourceUri = uri,
            onDismiss = { photoToCrop = null },
            onUseOriginal = { pendingPhoto = uri; photoToCrop = null },
            onCropped = { croppedUri -> pendingPhoto = croppedUri; photoToCrop = null },
        )
    }

    pendingPhoto?.let { uri ->
        ObservationEditor(
            photoUri = uri,
            original = null,
            tagGroups = userTagGroups.toList(),
            onAddTag = { groupId, tag ->
                val index = userTagGroups.indexOfFirst { it.id == groupId }
                if (index >= 0) {
                    userTagGroups[index] = userTagGroups[index].copy(tags = userTagGroups[index].tags + tag)
                    scope.launch { TagGroupStore.save(context, userTagGroups.toList()) }
                }
            },
            onDismiss = { pendingPhoto = null },
            onSave = { draft, photos ->
                scope.launch {
                    runCatching { ObservationStore.add(context, photos, draft) }
                        .onSuccess { saved -> observations.add(saved); selectedId = saved.id }
                        .onSuccess { pendingPhoto = null }
                        .onFailure { sharingMessage = "写真を保存できませんでした：${it.message}" }
                }
            },
        )
    }
    editingObservation?.let { original ->
        ObservationEditor(
            photoUri = Uri.fromFile(File(original.photoPath)),
            original = original,
            tagGroups = userTagGroups.toList(),
            onAddTag = { groupId, tag ->
                val index = userTagGroups.indexOfFirst { it.id == groupId }
                if (index >= 0) {
                    userTagGroups[index] = userTagGroups[index].copy(tags = userTagGroups[index].tags + tag)
                    scope.launch { TagGroupStore.save(context, userTagGroups.toList()) }
                }
            },
            onDismiss = { editingObservation = null },
            onSave = { edited, photos ->
                scope.launch {
                    runCatching {
                        val saved = ObservationStore.preparePhotos(context, photos, edited)
                        if (saved.isShared || saved.ownerAccountId == serverSession?.accountId) {
                            val activeSession = serverSession ?: error("共有中の記録を編集するには、コミュニティへ再ログインしてください。")
                            BiodiaryApi.setObservationShared(activeSession, saved, saved.isShared, userTagGroups.toList())
                        }
                        ObservationStore.update(context, saved)
                        saved
                    }
                        .onSuccess { saved ->
                            val index = observations.indexOfFirst { it.id == edited.id }
                            if (index >= 0) observations[index] = saved
                            selectedId = edited.id
                            editingObservation = null
                        }
                        .onFailure { sharingMessage = "変更を保存できませんでした：${it.message ?: "原因不明のエラー"}" }
                }
            },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("観察記録を削除") },
            text = { Text(if (target.ownerAccountId.isNotBlank()) "「${target.name}」を端末とコミュニティのサーバーから削除します。元に戻せません。" else "「${target.name}」の写真と記録を端末から削除します。元に戻せません。") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            if (target.ownerAccountId.isNotBlank()) {
                                val activeSession = serverSession ?: error("共有中の記録を削除するには、コミュニティへ再ログインしてください。")
                                BiodiaryApi.deleteObservation(activeSession, target.id)
                            }
                            ObservationStore.delete(context, target.id)
                        }.onSuccess {
                            observations.removeAll { it.id == target.id }
                            if (selectedId == target.id) selectedId = null
                            deleteTarget = null
                        }
                    }
                }) { Text("削除する") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("キャンセル", color = Color.White) } },
        )
    }
    if (showBulkShare) {
        val activeSession = serverSession
        val eligible = accountObservations.filter { item ->
            activeSession != null && !item.isShared && item.localAccountId == activeSession.accountId &&
                (item.ownerAccountId.isBlank() || item.ownerAccountId == activeSession.accountId)
        }.sortedByDescending { it.postedAt.takeIf { date -> date > 0 } ?: it.dateMillis }
        AlertDialog(
            onDismissRequest = { if (!bulkShareBusy) showBulkShare = false },
            title = { Text("まとめて共有") },
            text = {
                Column {
                    Text("未共有の自分の投稿を選んでください。選択した投稿の写真・日時・場所・タグ・コメントを公開します。")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${bulkShareSelection.size}件選択", modifier = Modifier.weight(1f))
                        TextButton(enabled = !bulkShareBusy && eligible.isNotEmpty(), onClick = {
                            bulkShareSelection = eligible.map { it.id }.toSet()
                        }) { Text("すべて選択") }
                        TextButton(enabled = !bulkShareBusy && bulkShareSelection.isNotEmpty(), onClick = {
                            bulkShareSelection = emptySet()
                        }) { Text("解除") }
                    }
                    if (eligible.isEmpty()) Text("未共有の投稿はありません。", color = MutedWhite)
                    else LazyColumn(Modifier.height(320.dp)) {
                        items(eligible, key = { it.id }) { item ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = item.id in bulkShareSelection, enabled = !bulkShareBusy,
                                    onCheckedChange = { checked ->
                                        bulkShareSelection = if (checked) bulkShareSelection + item.id else bulkShareSelection - item.id
                                    })
                                AsyncImage(model = File(item.photoPath), contentDescription = item.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(58.dp).clip(RoundedCornerShape(7.dp)))
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold)
                                    Text("${formatDate(item.dateMillis)}・写真${item.photoPaths.size}枚", style = MaterialTheme.typography.bodySmall)
                                    Text(if (item.locationPrivate) "公開場所：${item.prefecture}の県庁所在地" else
                                        "公開場所：${item.latitude}, ${item.longitude} ${item.place}",
                                        style = MaterialTheme.typography.bodySmall, color = MutedWhite)
                                    Text("タグ：${item.tagValues.values.flatten().joinToString("・").ifBlank { "なし" }}",
                                        style = MaterialTheme.typography.bodySmall, color = MutedWhite)
                                    if (item.comment.isNotBlank()) Text("コメント：${item.comment}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (bulkShareStatus.isNotBlank()) Text(bulkShareStatus, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                Button(enabled = !bulkShareBusy && activeSession?.communityMember == true &&
                    eligible.any { it.id in bulkShareSelection }, onClick = {
                    val session = activeSession ?: return@Button
                    val selectedItems = eligible.filter { it.id in bulkShareSelection }
                    bulkShareBusy = true
                    scope.launch {
                        val groupError = runCatching { BiodiaryApi.putTagGroups(session, userTagGroups.toList()) }.exceptionOrNull()
                        if (groupError != null) {
                            bulkShareStatus = "タグを送信できませんでした：${groupError.message}"
                            bulkShareBusy = false
                            return@launch
                        }
                        var success = 0
                        val failures = mutableListOf<String>()
                        selectedItems.forEachIndexed { index, item ->
                            bulkShareStatus = "共有中 ${index + 1}/${selectedItems.size}件…"
                            val error = runCatching {
                                require(!item.locationPrivate || item.prefecture.isNotBlank()) {
                                    "場所を非公開にする場合は編集画面で都道府県を選んでください"
                                }
                                BiodiaryApi.setObservationShared(session, item, true, syncGroups = false)
                                val updated = item.copy(isShared = true, ownerAccountId = session.accountId,
                                    ownerLoginName = communityMembers.firstOrNull { it.accountId == session.accountId }
                                        ?.let { member -> member.displayName.ifBlank { member.loginName } }.orEmpty()
                                        .ifBlank { session.displayName })
                                ObservationStore.update(context, updated)
                                val position = observations.indexOfFirst { it.id == item.id }
                                if (position >= 0) observations[position] = updated
                                bulkShareSelection = bulkShareSelection - item.id
                                success++
                            }.exceptionOrNull()
                            if (error != null) failures.add("${item.name}：${error.message ?: "共有できませんでした"}")
                        }
                        bulkShareBusy = false
                        if (failures.isEmpty()) {
                            showBulkShare = false
                            sharingMessage = "${success}件をコミュニティに共有しました"
                        } else bulkShareStatus = "${success}件共有しました。失敗した${failures.size}件は選択を残しています。\n" + failures.joinToString("\n")
                    }
                }) { Text("${bulkShareSelection.size}件を共有") }
            },
            dismissButton = { TextButton(enabled = !bulkShareBusy, onClick = { showBulkShare = false }) {
                Text("閉じる", color = Color.White)
            } },
        )
    }
    sharingTarget?.let { target ->
        val turningOn = !target.isShared
        AlertDialog(
            onDismissRequest = { sharingTarget = null },
            title = { Text(if (turningOn) "観察記録を共有" else "共有を停止") },
            text = {
                if (turningOn) Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    AsyncImage(model = File(target.photoPath), contentDescription = target.name,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(150.dp))
                    Text("写真：${target.photoPaths.size}枚　${target.name}（${target.category.label}）")
                    Text("撮影日時：${formatDate(target.dateMillis)} ${formatTime(target.dateMillis)}")
                    Text(if (target.locationPrivate) "公開する場所：${target.prefecture}の県庁所在地" else
                        "公開する場所：${target.latitude}, ${target.longitude} ${target.place}")
                    Text("タグ：${target.tagValues.values.flatten().joinToString("・").ifBlank { "なし" }}")
                    Text("コメント：${target.comment.ifBlank { "なし" }}")
                    Text("この内容をコミュニティのメンバーに共有します。", modifier = Modifier.padding(top = 8.dp))
                } else Text("「${target.name}」をコミュニティの一覧から非表示にします。サーバー上の記録は自分用として残ります。")
            },
            confirmButton = {
                Button(enabled = serverSession != null, onClick = {
                    val activeSession = serverSession
                    if (activeSession != null) scope.launch {
                        val updated = target.copy(isShared = turningOn, ownerAccountId = activeSession.accountId,
                            ownerLoginName = communityMembers.firstOrNull { it.accountId == activeSession.accountId }
                                ?.let { it.displayName.ifBlank { it.loginName } }.orEmpty().ifBlank { target.ownerLoginName })
                        runCatching { BiodiaryApi.setObservationShared(activeSession, target, turningOn, userTagGroups.toList()) }
                            .onSuccess {
                                ObservationStore.update(context, updated)
                                val index = observations.indexOfFirst { it.id == target.id }
                                if (index >= 0) observations[index] = updated
                                sharingMessage = if (turningOn) "コミュニティに共有しました" else "共有を停止しました"
                                selectedId = updated.id
                            }
                            .onFailure { sharingMessage = it.message ?: "共有設定を変更できませんでした" }
                        sharingTarget = null
                    }
                }) { Text(if (turningOn) "共有する" else "停止する") }
            },
            dismissButton = { TextButton(onClick = { sharingTarget = null }) { Text("キャンセル", color = Color.White) } },
        )
    }
    if (sharingMessage.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { sharingMessage = "" },
            text = { Text(sharingMessage) },
            confirmButton = { TextButton(onClick = { sharingMessage = "" }) { Text("閉じる", color = Color.White) } },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ObservationDetailDialog(
    observation: Observation,
    members: List<CommunityMember>,
    related: List<Observation>,
    onSelectRelated: (String) -> Unit,
    ownAccountId: String,
    onEditRelated: (Observation) -> Unit,
    session: BiodiarySession?,
    onProfileClick: (String) -> Unit,
    onReactionChanged: (JSONObject) -> Unit,
    onDismiss: () -> Unit,
    onShowMap: () -> Unit,
) {
    val context = LocalContext.current
    var photoIndex by remember(observation.id, observation.photoPaths) { mutableStateOf(0) }
    val mapPoint = observation.mapPoint()
    val ownerName = members.firstOrNull { it.accountId == observation.ownerAccountId }?.let { it.displayName.ifBlank { it.loginName } }
        ?: observation.ownerLoginName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(observation.name) },
        text = {
            Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState())) {
                if (related.size > 1) {
                    Text("同じ生きものの投稿（${related.size}件）", style = MaterialTheme.typography.titleSmall)
                    Text("写真を選ぶと、その投稿の詳細を開きます", style = MaterialTheme.typography.bodySmall, color = MutedWhite)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        items(related, key = { it.id }) { record ->
                            val selected = record.id == observation.id
                            val author = if (record.isShared) members.firstOrNull { it.accountId == record.ownerAccountId }
                                ?.let { it.displayName.ifBlank { it.loginName } } ?: record.ownerLoginName.ifBlank { record.ownerAccountId }
                                else "自分の記録"
                            Column(Modifier.width(88.dp).clip(RoundedCornerShape(10.dp))
                                .border(if (selected) 2.dp else 1.dp, if (selected) CardText else MutedWhite.copy(alpha = .4f), RoundedCornerShape(10.dp))
                                .clickable(onClickLabel = "$author・${formatDate(record.dateMillis)}の詳細を開く") { if (!selected) onSelectRelated(record.id) }
                                .padding(5.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(7.dp))
                                    .border(2.dp, record.category.color, RoundedCornerShape(7.dp))) {
                                    AsyncImage(model = File(record.photoPath), contentDescription = record.name,
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Text(author, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                Text(formatDate(record.dateMillis), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                if (selected) Text("表示中", style = MaterialTheme.typography.labelSmall, color = CardText)
                                if (ownAccountId.isNotBlank() && (record.ownerAccountId.ifBlank { record.localAccountId } == ownAccountId)) {
                                    Text("✎ 編集", color = Color.White, style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "${record.name}の記録を編集") { onEditRelated(record) })
                                }
                            }
                        }
                    }
                }
                if (related.isNotEmpty()) {
                    val first = related.minOf { it.dateMillis }
                    val last = related.maxOf { it.dateMillis }
                    Text("この生きものの観察：${related.size}件", style = MaterialTheme.typography.titleSmall)
                    Text("初回：${formatDate(first)}　最新：${formatDate(last)}",
                        style = MaterialTheme.typography.bodySmall, color = MutedWhite)
                }
                Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)).background(ObservationDark).border(3.dp, observation.category.color, RoundedCornerShape(14.dp))) {
                    AsyncImage(
                        model = File(observation.photoPaths[photoIndex]), contentDescription = "${observation.name}：写真${photoIndex + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                    )
                    OwnerAvatar(observation, members, Modifier.align(Alignment.TopStart), onProfileClick)
                    if (!File(observation.photoPaths[photoIndex]).isFile) {
                        Text("この写真は未取得です。共有記録を同期してください。", modifier = Modifier.align(Alignment.Center).padding(16.dp))
                    }
                    ObservationLikes(observation, session, onReactionChanged, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
                if (observation.photoPaths.size > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { photoIndex -= 1 }, enabled = photoIndex > 0) { Text("前へ") }
                        Text("${photoIndex + 1} / ${observation.photoPaths.size}")
                        TextButton(onClick = { photoIndex += 1 }, enabled = photoIndex < observation.photoPaths.lastIndex) { Text("次へ") }
                    }
                }
                Text(formatDate(observation.dateMillis) + " " + formatTime(observation.dateMillis), modifier = Modifier.padding(top = 12.dp))
                Text("${observation.category.label} · ${observation.season.label} · ${TimeBand.fromDate(observation.dateMillis).label}")
                if (ownerName.isNotBlank() && observation.isShared) Text("共有者：$ownerName")
                Text(if (observation.locationPrivate) "場所の詳細は非公開です（${observation.prefecture}の県庁所在地）" else observation.place.ifBlank { "場所のメモなし" }, modifier = Modifier.padding(top = 8.dp))
                Text("${mapPoint.latitude}, ${mapPoint.longitude}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = onShowMap,
                    enabled = mapPoint.latitude.isFinite() && mapPoint.longitude.isFinite() &&
                        mapPoint.latitude in -90.0..90.0 && mapPoint.longitude in -180.0..180.0,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("地図で見る") }
                if (observation.species.isNotBlank()) Text("種：${observation.species}")
                if (observation.speciesScientificName.isNotBlank()) Text("学名：${observation.speciesScientificName}")
                if (observation.genus.isNotBlank()) Text("属：${observation.genus}")
                if (observation.family.isNotBlank()) Text("科：${observation.family}")
                if (observation.order.isNotBlank()) Text("目：${observation.order}")
                if (observation.comment.isNotBlank()) Text(observation.comment, modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = {
                    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${mapPoint.latitude},${mapPoint.longitude}")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("この地点をGoogleマップで確認") }
                ObservationComments(observation, session, members, onProfileClick)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        dismissButton = {
            if (ownAccountId.isNotBlank() && observation.ownerAccountId.ifBlank { observation.localAccountId } == ownAccountId) {
                TextButton(onClick = { onEditRelated(observation) }) { Text("編集", color = Color.White) }
            }
        },
    )
}

@Composable
private fun OwnerAvatar(observation: Observation, members: List<CommunityMember>, modifier: Modifier = Modifier,
    onProfileClick: (String) -> Unit) {
    if (!observation.isShared || observation.ownerAccountId.isBlank()) return
    val member = members.firstOrNull { it.accountId == observation.ownerAccountId }
    Box(modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(PanelGreen).border(1.dp, Color.White, RoundedCornerShape(10.dp))
        .clickable(onClickLabel = "プロフィールを開く") { onProfileClick(observation.ownerAccountId) },
        contentAlignment = Alignment.Center) {
        if (member?.avatarPath?.let { File(it).isFile } == true) {
            AsyncImage(model = File(member.avatarPath), contentDescription = "${member.displayName.ifBlank { member.loginName }}のアイコン",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(member?.displayName?.ifBlank { member.loginName }?.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 10.sp)
        }
    }
}

private data class CatalogEntry(
    val name: String,
    val representative: Observation,
    val newestAt: Long,
    val oldestAt: Long,
)

@Composable
private fun CatalogScreen(
    observations: List<Observation>,
    members: List<CommunityMember>,
    selectedOwners: Set<String>,
    onShareToggle: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onObservationClick: (String) -> Unit,
) {
    var sort by remember { mutableStateOf(CatalogSort.NEWEST) }
    val visible = if (selectedOwners.isEmpty()) observations else observations.filter {
        it.isShared && it.ownerAccountId in selectedOwners
    }
    val entries = visible.groupBy { it.category to it.name.trim().ifBlank { "名前未入力" } }.map { (key, records) ->
        CatalogEntry(
            name = key.second,
            representative = records.maxWith(compareBy<Observation> { if (it.isShared) it.likeCount else 0 }
                .thenBy { it.postedAt.takeIf { time -> time > 0 } ?: it.dateMillis }.thenBy { it.id }),
            newestAt = records.maxOf { it.postedAt.takeIf { time -> time > 0 } ?: it.dateMillis },
            oldestAt = records.minOf { it.postedAt.takeIf { time -> time > 0 } ?: it.dateMillis },
        )
    }.sortedWith(when (sort) {
        CatalogSort.NEWEST -> compareByDescending<CatalogEntry> { it.newestAt }.thenBy { it.name }
        CatalogSort.NAME -> Comparator { a, b -> java.text.Collator.getInstance(Locale.JAPANESE).compare(a.name, b.name) }
        CatalogSort.OLDEST -> compareBy<CatalogEntry> { it.oldestAt }.thenBy { it.name }
    })
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxSize().padding(horizontal = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 11.dp, vertical = 12.dp)) {
                SectionTitle("生きもの図鑑")
                Text("写真をタップすると記録を開きます", style = MaterialTheme.typography.bodySmall, color = MutedWhite)
            }
        }
        if (members.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 11.dp)) {
                    Text("共有", style = MaterialTheme.typography.labelSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(members, key = { it.accountId }) { member ->
                            FilterChip(selected = member.accountId in selectedOwners, onClick = { onShareToggle(member.accountId) },
                                label = { Text(member.displayName.ifBlank { member.loginName }) })
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(modifier = Modifier.padding(horizontal = 11.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(CatalogSort.entries) { choice ->
                    FilterChip(selected = sort == choice, onClick = { sort = choice }, label = { Text(choice.label) })
                }
            }
        }
        if (visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("観察を記録すると、ここに図鑑ができます。", modifier = Modifier.padding(16.dp))
            }
        }
        LifeCategory.entries.forEach { category ->
            val categoryEntries = entries.filter { it.representative.category == category }
            if (categoryEntries.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(category.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 7.dp, top = 13.dp, bottom = 2.dp))
                }
            }
            gridItems(categoryEntries, key = { "${category.name}:${it.name}" }) { entry ->
                    val latest = entry.representative
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { onObservationClick(latest.id) }.padding(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(9.dp)).border(2.dp, latest.category.color, RoundedCornerShape(9.dp))) {
                            AsyncImage(
                                model = File(latest.photoPath), contentDescription = entry.name, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)),
                            )
                            OwnerAvatar(latest, members, Modifier.align(Alignment.TopStart), onProfileClick)
                        }
                        Text(entry.name, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                    }
            }
        }
    }
}

@Composable
private fun CommunityScreen(
    session: BiodiarySession?,
    members: List<CommunityMember>,
    onProfileClick: (String) -> Unit,
    onSessionChanged: (BiodiarySession) -> Unit,
    onSync: suspend () -> ObservationSyncResult,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inviteCode by remember { mutableStateOf("") }
    val syncPreferences = remember { context.getSharedPreferences("biodiary_observation_sync", Context.MODE_PRIVATE) }
    var status by remember(session?.accountId) { mutableStateOf(syncPreferences.getString("status_${session?.accountId}", "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var communityName by remember(session?.accountId) { mutableStateOf("コミュニティ") }
    var communityNameDraft by remember(session?.accountId) { mutableStateOf("") }
    var isCommunityAdmin by remember(session?.accountId) { mutableStateOf(false) }
    LaunchedEffect(session?.token, session?.communityMember) {
        isCommunityAdmin = false
        if (session?.communityMember == true) {
            runCatching { BiodiaryApi.communitySettings(session) }
                .onSuccess { settings ->
                    communityName = settings.getString("name")
                    communityNameDraft = communityName
                    isCommunityAdmin = settings.optBoolean("isAdmin")
                }.onFailure { status = "コミュニティ情報を取得できませんでした：${it.message}" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        SectionTitle("コミュニティ")
        if (session == null) {
            Text("アカウントにログインしてください。", modifier = Modifier.padding(top = 14.dp))
        } else if (!session.communityMember) {
            Text("参加前でも地図と図鑑を利用できます。招待コードがあればコミュニティへ参加できます。",
                modifier = Modifier.padding(top = 14.dp))
            TextField(value = inviteCode, onValueChange = { inviteCode = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("コミュニティの招待コード") },
                singleLine = true)
            Button(enabled = !busy && inviteCode.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    runCatching { BiodiaryApi.joinCommunity(session, inviteCode) }
                        .onSuccess {
                            val updated = session.copy(communityMember = it.optBoolean("communityMember", false))
                            BiodiarySessionStore.save(context, updated.baseUrl, updated.token,
                                updated.accountId, updated.displayName, updated.communityMember)
                            onSessionChanged(updated)
                            inviteCode = ""
                            status = "コミュニティに参加しました"
                        }.onFailure { status = it.message ?: "参加できませんでした" }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("参加する") }
        } else {
            Text(communityName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            if (isCommunityAdmin) {
                Text("管理者", style = MaterialTheme.typography.labelSmall, color = CardText)
                TextField(value = communityNameDraft, onValueChange = { communityNameDraft = it },
                    label = { Text("コミュニティ名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedButton(enabled = !busy && communityNameDraft.trim().length in 1..60, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { BiodiaryApi.renameCommunity(session, communityNameDraft) }
                            .onSuccess { settings ->
                                communityName = settings.getString("name")
                                communityNameDraft = communityName
                                status = "コミュニティ名を変更しました"
                            }.onFailure { status = it.message ?: "変更できませんでした" }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("コミュニティ名を変更") }
            }
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    status = "新しい共有記録を確認しています…"
                    runCatching { onSync() }
                        .onSuccess {
                            status = "同期完了（${java.text.SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(java.util.Date())}）：更新${it.changedCount}件・写真${it.photoCount}枚" +
                                if (it.failedPhotos > 0) "。未取得${it.failedPhotos}枚は次回再試行します。" else ""
                            syncPreferences.edit().putString("status_${session.accountId}", status).apply()
                        }
                        .onFailure {
                            status = "同期に失敗しました：${it.message ?: "通信エラー"}。ボタンから再試行できます。"
                            syncPreferences.edit().putString("status_${session.accountId}", status).apply()
                        }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("共有記録を同期") }
            if (members.isEmpty()) {
                Text("メンバーを読み込んでいます。", modifier = Modifier.padding(top = 14.dp))
            }
            members.forEach { member ->
            Card(Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = CardDefaults.cardColors(containerColor = ObservationDark, contentColor = Color.White)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(PanelGreen)
                        .clickable(onClickLabel = "プロフィールを開く") { onProfileClick(member.accountId) }, contentAlignment = Alignment.Center) {
                        if (member.avatarPath.isNotBlank() && File(member.avatarPath).isFile) {
                            AsyncImage(model = File(member.avatarPath), contentDescription = "${member.displayName.ifBlank { member.loginName }}のアイコン",
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else Text(member.displayName.ifBlank { member.loginName }.firstOrNull()?.uppercase() ?: "?", fontSize = 20.sp)
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(member.displayName.ifBlank { member.loginName }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("アカウントID：${member.accountId}", style = MaterialTheme.typography.bodySmall)
                        if (member.avatarUrl.isNotBlank() && (member.avatarPath.isBlank() || !File(member.avatarPath).isFile)) {
                            Text("アイコンを読み込めませんでした", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(member.message.ifBlank { "ひとことはまだありません" }, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            }
        }
        if (status.isNotBlank()) Text(status, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun PersonalScreen(
    profile: UserProfile,
    session: BiodiarySession?,
    ownMember: CommunityMember?,
    observationCount: Int,
    onPickAvatar: () -> Unit,
    onShareAvatar: () -> Unit,
    onSave: (UserProfile) -> Unit,
    onSessionChanged: (BiodiarySession?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(profile.accountId) { mutableStateOf(profile.name) }
    var message by remember(profile.accountId) { mutableStateOf(profile.message) }
    LaunchedEffect(profile.name, profile.message) {
        name = profile.name
        message = profile.message
    }
    var serverUrl by remember { mutableStateOf(session?.baseUrl ?: BiodiarySessionStore.baseUrl(context)) }
    var loginName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serverDisplayName by remember(profile.name) { mutableStateOf(profile.name) }
    var serverBusy by remember { mutableStateOf(false) }
    var serverMessage by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        SectionTitle("アカウント設定")
        Text("利用にはアカウント登録が必要です。コミュニティへの参加は登録後に選べます。",
            style = MaterialTheme.typography.bodySmall, color = Color.White)
        if (session != null) {
        Card(Modifier.fillMaxWidth().padding(top = 14.dp), colors = CardDefaults.cardColors(containerColor = PanelGreen, contentColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val displayedAvatar = ownMember?.avatarPath?.takeIf { it.isNotBlank() && File(it).isFile }
                    ?: profile.avatarPath.takeIf { it.isNotBlank() && File(it).isFile }
                if (displayedAvatar != null) {
                    AsyncImage(
                        model = File(displayedAvatar), contentDescription = "プロフィールアイコン", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(92.dp).clip(RoundedCornerShape(46.dp)),
                    )
                } else {
                    Box(Modifier.size(92.dp).clip(RoundedCornerShape(46.dp)).background(PanelGreen), contentAlignment = Alignment.Center) {
                        Text("🌿", fontSize = 36.sp)
                    }
                }
                TextButton(onClick = onPickAvatar) { Text("アイコンを選ぶ", color = Color.White) }
                if (session != null) {
                    val avatarStatus = when {
                        ownMember?.avatarUrl.isNullOrBlank() -> "アイコンはこのアカウントに保存されます"
                        ownMember?.avatarPath.isNullOrBlank() -> "アイコンを読み込めませんでした"
                        else -> "アイコンは保存されています"
                    }
                    Text(avatarStatus, style = MaterialTheme.typography.bodySmall, color = Color.White)
                    if (profile.avatarPath.isNotBlank()) {
                        TextButton(onClick = onShareAvatar) { Text("アイコンを再保存", color = Color.White) }
                    }
                }
                TextField(value = name, onValueChange = { if (it.length <= 80) name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("アカウント名") }, singleLine = true)
                TextField(value = profile.accountId, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("アカウントID") }, singleLine = true)
                TextField(value = profile.loginName, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("ログインID") }, singleLine = true)
                TextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("みんなに一言") }, placeholder = { Text("自然観察についてなど") }, maxLines = 3)
                Text("登録日時：${formatDate(profile.registeredAt)}", style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.align(Alignment.Start).padding(top = 12.dp))
                Button(
                    onClick = {
                        val updatedProfile = profile.copy(name = name.trim().ifBlank { "自然観察者" }, message = message.trim())
                        val activeSession = session
                        if (activeSession != null) scope.launch {
                            runCatching { BiodiaryApi.updateAccount(activeSession, updatedProfile.name, updatedProfile.message) }
                                .onSuccess { response ->
                                    val remote = response.optJSONObject("account")
                                    val updatedSession = activeSession.copy(displayName = remote?.optString("displayName") ?: updatedProfile.name)
                                    onSave(updatedProfile.copy(name = updatedSession.displayName))
                                    BiodiarySessionStore.save(context, updatedSession.baseUrl, updatedSession.token, updatedSession.accountId, updatedSession.displayName, updatedSession.communityMember)
                                    onSessionChanged(updatedSession)
                                    serverMessage = "プロフィールをサーバーにも保存しました"
                                }
                                .onFailure { serverMessage = "アカウント名を保存できませんでした：${it.message}" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("プロフィールを保存") }
                OutlinedButton(enabled = !serverBusy, onClick = {
                    serverBusy = true
                    scope.launch {
                        runCatching { BiodiaryApi.logout(session) }
                        BiodiarySessionStore.clear(context)
                        onSessionChanged(null)
                        serverMessage = "ログアウトしました。端末内の観察記録は残っています。"
                        serverBusy = false
                    }
                }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("ログアウト") }
            }
        }
        }
        if (session == null) {
            Card(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp),
                colors = CardDefaults.cardColors(containerColor = PanelGreen, contentColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    Text("ログイン・アカウント作成", style = MaterialTheme.typography.titleMedium)
                    TextField(value = loginName, onValueChange = { loginName = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        label = { Text("ログインID（英数字で3文字以上）") }, singleLine = true)
                    TextField(value = password, onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("パスワード（12文字以上）") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    TextField(value = serverDisplayName, onValueChange = { if (it.length <= 80) serverDisplayName = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("アカウント名（新規作成時）") }, singleLine = true)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !serverBusy, onClick = {
                            serverBusy = true
                            scope.launch {
                                runCatching { BiodiaryApi.login(serverUrl, loginName, password) }
                                    .onSuccess { response ->
                                        val account = response.getJSONObject("account")
                                        val updated = BiodiarySession(serverUrl.trim().trimEnd('/'),
                                            response.getString("token"), account.optString("accountId"),
                                            account.optString("displayName"), account.optBoolean("communityMember", false))
                                        BiodiarySessionStore.save(context, updated.baseUrl, updated.token,
                                            updated.accountId, updated.displayName, updated.communityMember)
                                        onSessionChanged(updated)
                                        serverMessage = "ログインしました"
                                    }.onFailure { serverMessage = it.message ?: "ログインできませんでした" }
                                serverBusy = false
                            }
                        }, modifier = Modifier.weight(1f)) { Text("ログイン") }
                        OutlinedButton(enabled = !serverBusy, onClick = {
                            serverBusy = true
                            scope.launch {
                                runCatching { BiodiaryApi.register(serverUrl, loginName, password, serverDisplayName) }
                                    .onSuccess { response ->
                                        val account = response.getJSONObject("account")
                                        val updated = BiodiarySession(serverUrl.trim().trimEnd('/'),
                                            response.getString("token"), account.optString("accountId"),
                                            account.optString("displayName"), account.optBoolean("communityMember", false))
                                        BiodiarySessionStore.save(context, updated.baseUrl, updated.token,
                                            updated.accountId, updated.displayName, updated.communityMember)
                                        onSessionChanged(updated)
                                        serverMessage = "アカウントを作成しました"
                                    }.onFailure { serverMessage = it.message ?: "作成できませんでした" }
                                serverBusy = false
                            }
                        }, modifier = Modifier.weight(1f)) { Text("作成") }
                    }
                    TextField(value = serverUrl, onValueChange = { serverUrl = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        label = { Text("CloudflareのAPI URL（https://...）") }, singleLine = true)
                    OutlinedButton(enabled = !serverBusy, onClick = {
                        serverBusy = true
                        scope.launch {
                            val result = runCatching { BiodiaryApi.status(serverUrl) }
                            serverMessage = result.fold({ "接続できました：$it" }, { it.message ?: "接続できませんでした" })
                            serverBusy = false
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("接続確認") }
                    if (serverMessage.isNotBlank())
                        Text(serverMessage, style = MaterialTheme.typography.bodySmall, color = Color.White,
                            modifier = Modifier.padding(top = 8.dp))
                }
            }
        } else if (serverMessage.isNotBlank()) {
            Text(serverMessage, style = MaterialTheme.typography.bodySmall, color = Color.White,
                modifier = Modifier.padding(top = 8.dp))
        }
        Card(Modifier.fillMaxWidth().padding(top = 14.dp), colors = CardDefaults.cardColors(containerColor = PanelGreen, contentColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Text("Googleアカウントへのバックアップ", style = MaterialTheme.typography.titleMedium)
                Text("このアプリはAndroidの自動バックアップ対象です。端末の設定でGoogleバックアップが有効な場合、プロフィール・記録・写真がGoogleアカウントに保存され、同じアカウントでの端末復元時に戻ります。", style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(top = 5.dp))
                Text("追加サーバーは不要です。Android標準のバックアップはアプリあたり25 MBまでのため、写真が多い場合は一部が含まれないことがあります。", style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(top = 7.dp))
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = PanelGreen, contentColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Text("観察記録", style = MaterialTheme.typography.titleMedium)
                Text("${observationCount}件", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = PanelGreen, contentColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Android版をダウンロード", style = MaterialTheme.typography.titleMedium)
                Text("公開中の最新版APKをダウンロードできます。", style = MaterialTheme.typography.bodySmall,
                    color = Color.White, modifier = Modifier.padding(top = 5.dp))
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://biodiary-album.pages.dev/Biodiary-latest.apk")))
                }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text("最新版APKをダウンロード")
                }
            }
        }
    }
}

@Composable
private fun ObservationEditor(
    photoUri: Uri,
    original: Observation?,
    tagGroups: List<UserTagGroup>,
    onAddTag: (String, TagDefinition) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Observation, List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    val photoUris = remember(photoUri, original?.id) {
        mutableStateListOf<Uri>().apply {
            addAll(original?.photoPaths?.map { Uri.fromFile(File(it)) } ?: listOf(photoUri))
        }
    }
    var activePhotoIndex by remember(photoUri, original?.id) { mutableStateOf(0) }
    var cropPhotoIndex by remember { mutableStateOf<Int?>(null) }
    var photoMessage by remember { mutableStateOf("") }
    var additionalCameraUri by remember { mutableStateOf<Uri?>(null) }
    val additionalPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { selected ->
        if (photoUris.size + selected.size > 4) photoMessage = "写真は1件につき4枚までです"
        else if (selected.isNotEmpty()) { photoUris.addAll(selected); photoMessage = ""; activePhotoIndex = photoUris.lastIndex }
    }
    val additionalCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && photoUris.size < 4) additionalCameraUri?.let { photoUris.add(it); activePhotoIndex = photoUris.lastIndex }
    }
    val metadata = remember(photoUri) { readPhotoMetadata(context, photoUri) }
    var name by remember(photoUri, original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var place by remember(photoUri, original?.id) { mutableStateOf(original?.place.orEmpty()) }
    var comment by remember(photoUri, original?.id) { mutableStateOf(original?.comment.orEmpty()) }
    var species by remember(photoUri, original?.id) { mutableStateOf(original?.species.orEmpty()) }
    var speciesScientificName by remember(photoUri, original?.id) { mutableStateOf(original?.speciesScientificName.orEmpty()) }
    var genus by remember(photoUri, original?.id) { mutableStateOf(original?.genus.orEmpty()) }
    var family by remember(photoUri, original?.id) { mutableStateOf(original?.family.orEmpty()) }
    var order by remember(photoUri, original?.id) { mutableStateOf(original?.order.orEmpty()) }
    var taxonomyCandidates by remember { mutableStateOf<List<TaxonomyCandidate>>(emptyList()) }
    var taxonomyLoading by remember { mutableStateOf(false) }
    var taxonomyMessage by remember { mutableStateOf("") }
    val taxonomyScope = rememberCoroutineScope()
    var selectedCustomTags by remember(photoUri, original?.id) {
        mutableStateOf(original?.tagValues?.mapValues { it.value.toSet() }.orEmpty())
    }
    var addTagGroup by remember { mutableStateOf<UserTagGroup?>(null) }
    var category by remember(photoUri, original?.id) { mutableStateOf<LifeCategory?>(original?.category) }
    var dateMillis by remember(photoUri, original?.id) { mutableStateOf(original?.dateMillis ?: metadata.dateMillis) }
    var location by remember(photoUri, original?.id) {
        mutableStateOf(original?.let { GeoPoint(it.latitude, it.longitude) } ?: metadata.location)
    }
    var locationPrivate by remember(photoUri, original?.id) { mutableStateOf(original?.locationPrivate ?: false) }
    var prefecture by remember(photoUri, original?.id) { mutableStateOf(original?.prefecture.orEmpty()) }
    var prefectureMenuOpen by remember { mutableStateOf(false) }
    var locationBusy by remember(photoUri, original?.id) { mutableStateOf(false) }
    var locationMessage by remember(photoUri, original?.id) { mutableStateOf("") }
    fun locateNow() {
        locationBusy = true
        locationMessage = "現在地を取得しています…"
        fetchCurrentGeoPoint(context) { point, error ->
            locationBusy = false
            if (point != null) {
                location = point
                locationMessage = "現在地を撮影場所に設定しました。地図をタップして調整できます。"
            } else {
                locationMessage = error ?: "現在地を取得できませんでした。"
            }
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) locateNow()
        else locationMessage = "位置情報の利用が許可されていません。端末の設定から許可してください。"
    }

    LaunchedEffect(name) {
        val query = name.trim()
        if (query.length < 2) {
            taxonomyCandidates = emptyList()
            taxonomyLoading = false
            taxonomyMessage = ""
            return@LaunchedEffect
        }
        taxonomyCandidates = emptyList()
        taxonomyMessage = ""
        delay(650)
        taxonomyLoading = true
        val result = runCatching { TaxonomyLookup.searchJapaneseName(query) }
        if (name.trim() == query) {
            val candidates = result.getOrDefault(emptyList())
            taxonomyCandidates = candidates
            taxonomyMessage = when {
                result.isFailure -> "分類候補を取得できません。通信環境を確認するか、下の欄に手入力してください。"
                candidates.isEmpty() -> "一致する分類候補がありません。下の欄に手入力できます。"
                else -> "候補を選ぶと種・属・科・目を入力します。"
            }
            taxonomyLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (original == null) "観察を記録" else "観察を編集") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AsyncImage(
                    model = photoUris[activePhotoIndex], contentDescription = "登録する写真${activePhotoIndex + 1}", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(155.dp).clip(RoundedCornerShape(16.dp)),
                )
                Text("写真 ${activePhotoIndex + 1} / ${photoUris.size}（最大4枚・1枚目を地図と図鑑に表示）", style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    photoUris.forEachIndexed { index, uri ->
                        AsyncImage(model = uri, contentDescription = "写真${index + 1}を選択", contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                                .border(if (index == activePhotoIndex) 2.dp else 1.dp, if (index == activePhotoIndex) CardText else MutedWhite, RoundedCornerShape(8.dp))
                                .clickable { activePhotoIndex = index })
                    }
                }
                Row {
                    TextButton(onClick = { additionalPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = photoUris.size < 4) { Text("写真を追加") }
                    TextButton(onClick = {
                        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
                        val file = File(directory, "${UUID.randomUUID()}.jpg").apply { createNewFile() }
                        additionalCameraUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        additionalCamera.launch(additionalCameraUri!!)
                    }, enabled = photoUris.size < 4) { Text("撮影") }
                }
                Row {
                    TextButton(onClick = { cropPhotoIndex = activePhotoIndex }) { Text("トリミング") }
                    TextButton(onClick = {
                        photoUris.removeAt(activePhotoIndex)
                        activePhotoIndex = activePhotoIndex.coerceAtMost(photoUris.lastIndex)
                    }, enabled = photoUris.size > 1) { Text("この写真を外す", color = Color(0xFFFF9B9B)) }
                }
                if (photoMessage.isNotBlank()) Text(photoMessage, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("生きものの名前") }, placeholder = { Text("例：アゲハチョウ") }, singleLine = true,
                )
                if (taxonomyLoading) {
                    Text("分類候補を検索しています…", style = MaterialTheme.typography.labelSmall, color = MutedWhite, modifier = Modifier.padding(top = 4.dp))
                } else if (taxonomyMessage.isNotBlank()) {
                    Text(taxonomyMessage, style = MaterialTheme.typography.labelSmall, color = MutedWhite, modifier = Modifier.padding(top = 4.dp))
                }
                taxonomyCandidates.forEach { candidate ->
                    TextButton(
                        onClick = {
                            taxonomyScope.launch {
                                taxonomyLoading = true
                                taxonomyMessage = "分類階層を取得しています…"
                                runCatching { TaxonomyLookup.loadLineage(candidate, name.trim()) }
                                    .onSuccess { lineage ->
                                        species = lineage.species
                                        speciesScientificName = lineage.speciesScientificName
                                        genus = lineage.genus
                                        family = lineage.family
                                        order = lineage.order
                                        taxonomyCandidates = emptyList()
                                        taxonomyMessage = "分類情報を入力しました。必要に応じて修正してください。"
                                    }
                                    .onFailure { taxonomyMessage = "分類を取得できませんでした。手入力してください。" }
                                taxonomyLoading = false
                            }
                        },
                        enabled = !taxonomyLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(candidate.japaneseName, fontWeight = FontWeight.Medium)
                            Text("${candidate.rank.toJapaneseRank()}の候補", style = MaterialTheme.typography.labelSmall, color = MutedWhite)
                        }
                    }
                }
                TextField(
                    value = place, onValueChange = { place = it }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    label = { Text("場所のメモ（任意）") }, placeholder = { Text("例：井の頭公園") }, singleLine = true,
                )
                TextField(
                    value = comment, onValueChange = { comment = it }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    label = { Text("ひとことコメント（任意）") }, placeholder = { Text("見つけたときの様子など") }, maxLines = 3,
                )
                Text("分類（任意）", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                Text("GBIFの分類データをオンライン検索します。候補を選んだ後も各欄は編集できます。", style = MaterialTheme.typography.labelSmall, color = MutedWhite)
                TextField(value = species, onValueChange = { species = it }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp), label = { Text("種（和名）") }, singleLine = true)
                TextField(value = speciesScientificName, onValueChange = { speciesScientificName = it }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp), label = { Text("種の学名") }, singleLine = true)
                TextField(value = genus, onValueChange = { genus = it }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp), label = { Text("属") }, singleLine = true)
                TextField(value = family, onValueChange = { family = it }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp), label = { Text("科") }, singleLine = true)
                TextField(value = order, onValueChange = { order = it }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp), label = { Text("目") }, singleLine = true)
                Text("タググループ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                Text("ジャンル（必須）", style = MaterialTheme.typography.labelSmall, color = MutedWhite, modifier = Modifier.padding(top = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(LifeCategory.selectableEntries) { item ->
                        val groupTag = FilterTag(item.name, item.label, item.colorHex)
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { Text(item.label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tagColor(groupTag.colorHex).copy(alpha = .2f), selectedLabelColor = Color.White),
                            border = BorderStroke(1.dp, tagColor(groupTag.colorHex).copy(alpha = if (category == item) .8f else .35f)),
                        )
                    }
                }
                Text("季節", style = MaterialTheme.typography.labelSmall, color = MutedWhite, modifier = Modifier.padding(top = 6.dp))
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("${Season.fromDate(dateMillis).label} ${Season.fromDate(dateMillis).range}") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Season.fromDate(dateMillis).color.copy(alpha = .2f), selectedLabelColor = Color.White),
                    border = BorderStroke(1.dp, Season.fromDate(dateMillis).color),
                )
                val timeBand = TimeBand.fromDate(dateMillis)
                Text("時間帯", style = MaterialTheme.typography.labelSmall, color = MutedWhite, modifier = Modifier.padding(top = 6.dp))
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(timeBand.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = timeBand.color.copy(alpha = .2f), selectedLabelColor = Color.White),
                    border = BorderStroke(1.dp, timeBand.color),
                )
                val date = Calendar.getInstance().apply { timeInMillis = dateMillis }
                val yearTint = tagColor("#6D9FBF")
                val monthTint = tagColor("#81947E")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    FilterChip(
                        selected = true, onClick = {}, label = { Text("${date.get(Calendar.YEAR)}年") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = yearTint.copy(alpha = .2f), selectedLabelColor = Color.White),
                        border = BorderStroke(1.dp, yearTint),
                    )
                    FilterChip(
                        selected = true, onClick = {}, label = { Text("${date.get(Calendar.MONTH) + 1}月") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = monthTint.copy(alpha = .2f), selectedLabelColor = Color.White),
                        border = BorderStroke(1.dp, monthTint),
                    )
                }
                tagGroups.forEach { group ->
                    Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, style = MaterialTheme.typography.labelSmall, color = MutedWhite, modifier = Modifier.weight(1f))
                        TextButton(onClick = { addTagGroup = group }) { Text("＋ タグ") }
                    }
                    if (group.tags.isEmpty()) {
                        Text("タグがありません。＋ タグから追加できます", style = MaterialTheme.typography.labelSmall, color = MutedWhite)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(group.tags, key = { it.name }) { tag ->
                                val active = tag.name in selectedCustomTags[group.id].orEmpty()
                                val tint = tagColor(tag.colorHex)
                                FilterChip(
                                    selected = active,
                                    onClick = {
                                        val values = selectedCustomTags[group.id].orEmpty().toMutableSet()
                                        if (!values.add(tag.name)) values.remove(tag.name)
                                        selectedCustomTags = selectedCustomTags.toMutableMap().apply {
                                            if (values.isEmpty()) remove(group.id) else put(group.id, values)
                                        }
                                    },
                                    label = { Text(tag.name) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tint.copy(alpha = .2f), selectedLabelColor = Color.White),
                                    border = BorderStroke(1.dp, tint.copy(alpha = if (active) .8f else .35f)),
                                )
                            }
                        }
                    }
                }
                Text("名前は手入力できます。入力した名前から分類候補を検索します。", style = MaterialTheme.typography.bodySmall, color = MutedWhite, modifier = Modifier.padding(top = 5.dp))
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            val current = Calendar.getInstance().apply { timeInMillis = dateMillis }
                            DatePickerDialog(context, { _, year, month, day ->
                                dateMillis = Calendar.getInstance().apply {
                                    timeInMillis = dateMillis
                                    set(Calendar.YEAR, year)
                                    set(Calendar.MONTH, month)
                                    set(Calendar.DAY_OF_MONTH, day)
                                }.timeInMillis
                            }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(formatDate(dateMillis), maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            val current = Calendar.getInstance().apply { timeInMillis = dateMillis }
                            TimePickerDialog(context, { _, hour, minute ->
                                dateMillis = Calendar.getInstance().apply {
                                    timeInMillis = dateMillis
                                    set(Calendar.HOUR_OF_DAY, hour)
                                    set(Calendar.MINUTE, minute)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }.timeInMillis
                            }, current.get(Calendar.HOUR_OF_DAY), current.get(Calendar.MINUTE), true).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("${formatTime(dateMillis)} · ${TimeBand.fromDate(dateMillis).label}", maxLines = 1) }
                }
                Text("季節：${Season.fromDate(dateMillis).label}", style = MaterialTheme.typography.bodySmall, color = MutedWhite)
                Spacer(Modifier.height(6.dp))
                Text(if (location == null) "場所を地図でタップして指定してください" else "場所をタップして調整できます", style = MaterialTheme.typography.bodySmall, color = MutedWhite)
                OutlinedButton(
                    onClick = {
                        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) locateNow()
                        else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    enabled = !locationBusy,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) { Text(if (locationBusy) "現在地を取得中…" else "現在地を取得") }
                if (locationMessage.isNotBlank()) {
                    Text(locationMessage, style = MaterialTheme.typography.labelSmall, color = MutedWhite)
                }
                LocationPickerMap(location = location, onLocationChange = { location = it })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Checkbox(checked = locationPrivate, onCheckedChange = { locationPrivate = it })
                    Text("場所の詳細を非公開にする")
                }
                if (locationPrivate) {
                    Text("地図には選択した都道府県の県庁所在地を表示します。共有時は正確な座標と場所メモを送信しません。", style = MaterialTheme.typography.bodySmall, color = MutedWhite)
                    if (prefecture.isBlank()) {
                        Text("保存するには都道府県を選んでください。", color = CardText, style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        OutlinedButton(onClick = { prefectureMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(prefecture.ifBlank { "都道府県を選ぶ（必須）" })
                        }
                        DropdownMenu(expanded = prefectureMenuOpen, onDismissRequest = { prefectureMenuOpen = false }) {
                            PrefectureCapitals.all.forEach { capital ->
                                DropdownMenuItem(text = { Text(capital.prefecture) }, onClick = {
                                    prefecture = capital.prefecture
                                    prefectureMenuOpen = false
                                })
                            }
                        }
                    }
                }
                if (metadata.location != null) {
                    Text("写真の位置情報を読み取りました", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (locationPrivate && prefecture.isBlank()) {
                    prefectureMenuOpen = true
                } else location?.let { point ->
                    onSave(
                        Observation(
                            id = original?.id ?: ObservationStore.newId(),
                            name = name.trim(),
                            photoPath = original?.photoPath.orEmpty(),
                            dateMillis = dateMillis,
                            latitude = point.latitude,
                            longitude = point.longitude,
                            place = place.trim(),
                            comment = comment.trim(),
                            category = requireNotNull(category),
                            species = species.trim(),
                            speciesScientificName = speciesScientificName.trim(),
                            genus = genus.trim(),
                            family = family.trim(),
                            order = order.trim(),
                            tagValues = selectedCustomTags.mapValues { it.value.toList() },
                            isShared = original?.isShared ?: false,
                            postedAt = original?.postedAt ?: System.currentTimeMillis(),
                            likeCount = original?.likeCount ?: 0,
                            likedByMe = original?.likedByMe ?: false,
                            ownerAccountId = original?.ownerAccountId.orEmpty(),
                            ownerLoginName = original?.ownerLoginName.orEmpty(),
                            locationPrivate = locationPrivate,
                            prefecture = if (locationPrivate) prefecture else "",
                        ),
                        photoUris.toList(),
                    )
                }
            }, enabled = name.isNotBlank() && category != null && location != null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル", color = Color.White) } },
    )
    cropPhotoIndex?.let { index ->
        PhotoCropDialog(
            sourceUri = photoUris[index],
            onDismiss = { cropPhotoIndex = null },
            onUseOriginal = { cropPhotoIndex = null },
            onCropped = { uri -> photoUris[index] = uri; cropPhotoIndex = null },
        )
    }
    addTagGroup?.let { group ->
        AddTagDialog(
            groupName = group.name,
            onDismiss = { addTagGroup = null },
            onCreate = { tag ->
                if (group.tags.none { it.name.equals(tag.name, ignoreCase = true) }) {
                    onAddTag(group.id, tag)
                    selectedCustomTags = selectedCustomTags.toMutableMap().apply {
                        put(group.id, get(group.id).orEmpty() + tag.name)
                    }
                }
                addTagGroup = null
            },
        )
    }
}

@Composable
private fun CreateTagGroupDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("タググループを作成") },
        text = {
            Column {
                Text("季節や類と同じ階層のグループを作ります。")
                TextField(value = name, onValueChange = { name = it }, label = { Text("グループ名") }, placeholder = { Text("例：観察場所") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("作成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル", color = Color.White) } },
    )
}

@Composable
private fun AddTagDialog(groupName: String, onDismiss: () -> Unit, onCreate: (TagDefinition) -> Unit) {
    val palette = listOf("#E76F51", "#F4A261", "#E9C46A", "#6A994E", "#2A9D8F", "#457B9D", "#7B6DCC", "#C76D9C")
    var name by remember(groupName) { mutableStateOf("") }
    var colorHex by remember(groupName) { mutableStateOf(palette[0]) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("「$groupName」にタグを追加") },
        text = {
            Column {
                TextField(value = name, onValueChange = { name = it }, label = { Text("タグ名") }, placeholder = { Text("例：公園") }, singleLine = true)
                Text("色", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    items(palette) { color ->
                        val tint = tagColor(color)
                        FilterChip(
                            selected = colorHex == color,
                            onClick = { colorHex = color },
                            label = { Text("●", color = tint) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tint.copy(alpha = .16f)),
                            border = BorderStroke(2.dp, tint),
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(TagDefinition(name.trim(), colorHex)) }, enabled = name.isNotBlank()) { Text("追加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル", color = Color.White) } },
    )
}

@Composable
private fun TagManagementDialog(
    group: UserTagGroup,
    onDismiss: () -> Unit,
    onDeleteTag: (TagDefinition) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${group.name}」のタグ") },
        text = {
            if (group.tags.isEmpty()) {
                Text("このグループにはタグがありません。")
            } else {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    group.tags.forEach { tag ->
                        val tint = tagColor(tag.colorHex)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text(tag.name) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tint.copy(alpha = .2f), selectedLabelColor = Color.White),
                                border = BorderStroke(1.dp, tint),
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onDeleteTag(tag) }) { Text("削除", color = Color.White) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun LocationPickerMap(location: GeoPoint?, onLocationChange: (GeoPoint) -> Unit) {
    val initial = location ?: GeoPoint(36.2, 138.25)
    OsmMap(
        modifier = Modifier.fillMaxWidth().height(205.dp).padding(top = 6.dp).clip(RoundedCornerShape(14.dp)),
        initialCenter = initial,
        initialZoom = if (location == null) 5.2f else 12f,
        pins = location?.let { listOf(MapPin("selected", it.latitude, it.longitude, "#426B50", null)) }.orEmpty(),
        onMapTap = onLocationChange,
        onPinTap = {},
    )
}

@Composable
private fun OsmMap(
    modifier: Modifier,
    initialCenter: GeoPoint = GeoPoint(36.2, 138.25),
    initialZoom: Float = 5.15f,
    focusRequest: Long = 0L,
    pins: List<MapPin>,
    onMapTap: (GeoPoint) -> Unit,
    onPinTap: (String) -> Unit,
    onPinOwnerTap: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val latestOnMapTap by rememberUpdatedState(onMapTap)
    val latestOnPinTap by rememberUpdatedState(onPinTap)
    val latestOnPinOwnerTap by rememberUpdatedState(onPinOwnerTap)
    var mapReady by remember { mutableStateOf(false) }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Biodiary/${BuildConfig.VERSION_NAME} (Android)"
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE,
                    android.view.MotionEvent.ACTION_POINTER_DOWN ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            // Emulator GPU logs show missing render nodes; use software composition there.
            if (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            addJavascriptInterface(object {
                private val mainHandler = Handler(Looper.getMainLooper())
                @JavascriptInterface fun onMapReady() { mainHandler.post { mapReady = true } }
                @JavascriptInterface fun onMapTap(latitude: Double, longitude: Double) {
                    mainHandler.post { latestOnMapTap(GeoPoint(latitude, longitude)) }
                }
                @JavascriptInterface fun onPinTap(id: String) { mainHandler.post { latestOnPinTap(id) } }
                @JavascriptInterface fun onPinOwnerTap(id: String) { mainHandler.post { latestOnPinOwnerTap(id) } }
            }, "Biodiary")
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    Log.i("BiodiaryMap", "Page started: $url")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.i("BiodiaryMap", "Page finished: $url")
                    view.post {
                        val density = view.resources.displayMetrics.density
                        val cssWidth = view.width / density
                        val cssHeight = view.height / density
                        Log.i("BiodiaryMap", "Applying native WebView size ${view.width}x${view.height}px (${cssWidth}x${cssHeight} CSS px)")
                        view.evaluateJavascript("window.moriSetSize && window.moriSetSize($cssWidth,$cssHeight);", null)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    Log.e("BiodiaryMap", "Load error ${error.errorCode}: ${error.description}; url=${request.url}")
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(Uri.parse(url))
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    val line = "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                    when (message.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e("BiodiaryMapJS", line)
                        ConsoleMessage.MessageLevel.WARNING -> Log.w("BiodiaryMapJS", line)
                        else -> Log.d("BiodiaryMapJS", line)
                    }
                    return true
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/map.html")
        }
    }
    DisposableEffect(webView) {
        onDispose { webView.stopLoading(); webView.destroy() }
    }
    LaunchedEffect(webView, mapReady, initialCenter, initialZoom, focusRequest) {
        if (mapReady) {
            webView.evaluateJavascript(
                "window.moriSetCenter(${initialCenter.latitude},${initialCenter.longitude},$initialZoom);",
                null,
            )
        }
    }
    LaunchedEffect(webView, mapReady, pins) {
        if (mapReady) {
            val json = org.json.JSONArray().apply {
                pins.forEach { pin ->
                    put(org.json.JSONObject().apply {
                        put("id", pin.id)
                        put("lat", pin.latitude)
                        put("lng", pin.longitude)
                        put("color", pin.frameColor)
                        put("photo", pin.photoDataUrl ?: org.json.JSONObject.NULL)
                        put("ownerAvatar", pin.ownerAvatarDataUrl ?: org.json.JSONObject.NULL)
                        put("ownerInitial", pin.ownerInitial ?: org.json.JSONObject.NULL)
                    })
                }
            }.toString()
            webView.evaluateJavascript("window.moriSetPins(JSON.parse(${org.json.JSONObject.quote(json)}));", null)
        }
    }
    AndroidView(factory = { webView }, modifier = modifier)
}

private fun photoDataUrl(path: String): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 240)
    }
    val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
    val bytes = java.io.ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
        bitmap.recycle()
        output.toByteArray()
    }
    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}.getOrNull()

private fun Color.toMapHex(): String = String.format(Locale.US, "#%06X", 0xFFFFFF and toArgb())

private data class GeoPoint(val latitude: Double, val longitude: Double)

private fun fetchCurrentGeoPoint(context: Context, onResult: (GeoPoint?, String?) -> Unit) {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val providers = listOfNotNull(
        LocationManager.NETWORK_PROVIDER.takeIf { (fine || coarse) && manager.isProviderEnabled(it) },
        LocationManager.GPS_PROVIDER.takeIf { fine && manager.isProviderEnabled(it) },
    )
    if (providers.isEmpty()) {
        onResult(null, "端末の位置情報をオンにしてください。")
        return
    }
    val handler = Handler(Looper.getMainLooper())
    var completed = false
    var signal: CancellationSignal? = null
    val timeout = Runnable {
        if (!completed) {
            completed = true
            signal?.cancel()
            onResult(null, "現在地を取得できませんでした。屋外で再試行するか、地図をタップして指定してください。")
        }
    }
    fun requestProvider(index: Int) {
        if (completed) return
        if (index >= providers.size) {
            completed = true
            handler.removeCallbacks(timeout)
            onResult(null, "現在地を取得できませんでした。地図をタップして指定してください。")
            return
        }
        signal = CancellationSignal()
        try {
            LocationManagerCompat.getCurrentLocation(manager, providers[index], signal, ContextCompat.getMainExecutor(context)) { found ->
                if (completed) return@getCurrentLocation
                if (found != null) {
                    completed = true
                    handler.removeCallbacks(timeout)
                    onResult(GeoPoint(found.latitude, found.longitude), null)
                } else {
                    requestProvider(index + 1)
                }
            }
        } catch (_: SecurityException) {
            requestProvider(index + 1)
        } catch (_: IllegalArgumentException) {
            requestProvider(index + 1)
        }
    }
    handler.postDelayed(timeout, 20_000)
    requestProvider(0)
}

private data class MapPin(
    val id: String, val latitude: Double, val longitude: Double, val frameColor: String,
    val photoDataUrl: String?, val ownerAvatarDataUrl: String? = null, val ownerInitial: String? = null,
)
private data class PhotoMetadata(val dateMillis: Long, val location: GeoPoint?)

private fun readPhotoMetadata(context: android.content.Context, uri: Uri): PhotoMetadata {
    return runCatching {
        context.contentResolver.openInputStream(uri).use { stream ->
            val exif = ExifInterface(requireNotNull(stream))
            val dateString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val parsedDate = dateString?.let {
                runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }.getOrNull()
            } ?: System.currentTimeMillis()
            val coordinates = FloatArray(2)
            val hasLocation = exif.getLatLong(coordinates)
            val latLng = if (hasLocation) GeoPoint(coordinates[0].toDouble(), coordinates[1].toDouble()) else null
            PhotoMetadata(parsedDate, latLng)
        }
    }.getOrElse { PhotoMetadata(System.currentTimeMillis(), null) }
}

private fun formatDate(timeMillis: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.JAPAN).format(Date(timeMillis))

private fun formatTime(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(timeMillis))

private fun String.toJapaneseRank(): String = when (uppercase(Locale.ROOT)) {
    "SPECIES" -> "種"
    "GENUS" -> "属"
    "FAMILY" -> "科"
    "ORDER" -> "目"
    else -> "分類"
}
