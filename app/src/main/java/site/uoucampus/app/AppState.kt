package site.uoucampus.app

import android.os.SystemClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDateTime

enum class Field(val label: String) { FROM("출발지"), TO("도착지") }

/** 폰에서 시트가 어느 자리에 있는지. HIDDEN 은 지도에서 곳을 고르는 중. */
enum class SheetState { EXPANDED, COLLAPSED, HIDDEN }

/** Client/src/App.tsx 의 상태와 손짓을 한 곳에 모았다. 화면의 단계는 여기서만 정한다. */
class AppState(val graph: CampusGraph, val locator: Locator, private val store: TimetableStore) {
  val indoorCount = graph.edges.count { it.surface == Surface.INDOOR }
  val shortcutCount = graph.edges.count { it.shortcut }

  var fromId by mutableStateOf<String?>(null)
  var toId by mutableStateOf<String?>(null)
  var options by mutableStateOf(RouteOptions())

  val route by derivedStateOf {
    val from = fromId
    val to = toId
    if (from != null && to != null) findRoute(graph, from, to, options) else null
  }

  /** 다른 기준으로 잡으면 길이 달라질 때만 있다. */
  val compare by derivedStateOf {
    val from = fromId
    val to = toId
    if (from == null || to == null) null
    else findRoute(graph, from, to, RouteOptions(options.profile.other, options.allowIndoor)).takeUnless { sameRoute(route, it) }
  }

  /** 큰길로만 돌았을 때의 기준선. */
  val roadsOnly by derivedStateOf {
    val from = fromId
    val to = toId
    if (from != null && to != null) findRoute(graph, from, to, options.copy(roadsOnly = true)) else null
  }

  /** 상단 띠, 진행 줄, 안내 목록이 나눠 쓴다. 따로 셈하면 '지금 몇 번째 줄' 이 어긋난다. */
  val steps by derivedStateOf { route?.let { Directions.steps(graph, it) } ?: emptyList() }

  val progress by derivedStateOf {
    val r = route
    val at = locator.here
    /* 캠퍼스 밖이면 '경로에서 수만 km' 가 뜬다. 따라갈 게 없다. */
    if (r != null && at != null && !offCampus) Progress.track(r, at) else null
  }

  /** 전체 화면 장소 목록을 띄운 칸. */
  var picker by mutableStateOf<Field?>(null)
  /** 지도에서 직접 고르는 중인 칸. 이때는 시트를 치우고 지도만 남긴다. */
  var picking by mutableStateOf<Field?>(null)
  var guiding by mutableStateOf(false)
  var timetableOpen by mutableStateOf(false)
  var slots by mutableStateOf(store.load())
    private set
  var now by mutableStateOf(LocalDateTime.now())

  /** 사람이 손으로 잡아 둔 시트 자리. 어느 구간을 보다가 잡은 것인지까지 같이 들고 있는다. */
  private var heldSheet by mutableStateOf<Pair<String, SheetState>?>(null)
  /** 다음에 잡히는 첫 좌표를 출발지로 삼을지. '현위치에서 출발' 을 눌렀을 때만 참이다. */
  private var claimFirstFix = true

  init {
    locator.onFirstFix = { at ->
      if (claimFirstFix) {
        claimFirstFix = false
        /* 건물만 골라 붙이면 열에 일곱은 시작하자마자 '경로에서 벗어남' 이다. 길목까지 포함한다. */
        graph.nearest(at)?.takeIf { it.second <= CAMPUS_REACH }?.let { fromId = it.first.id }
      }
    }
    locator.onFix = { rerouteIfLost() }
  }

  private var lostSince: Long? = null

  /** 안내 중 한참 벗어나 있으면 지금 선 자리에서 가장 가까운 곳을 출발지로 다시 세운다. */
  private fun rerouteIfLost() {
    val here = locator.here
    if (!guiding || !lost || locator.status != Locator.Status.READY || here == null) {
      lostSince = null
      return
    }
    val now = SystemClock.elapsedRealtime()
    val since = lostSince ?: now.also { lostSince = it }
    if (now - since < REROUTE_AFTER_MS) return
    /* 도착지 코앞이면 출발지와 도착지가 같아져 안내가 사라진다. */
    val near = graph.nearest(here)?.first?.takeIf { it.id != toId } ?: return
    lostSince = null
    fromId = near.id
  }

  /** 집에서 켜면 캠퍼스 끝 길목이 '현위치 근처' 로 들어앉고 지도는 집으로 날아간다. */
  val offCampus get() = locator.here?.let { (graph.nearest(it)?.second ?: Double.POSITIVE_INFINITY) > CAMPUS_REACH } ?: false

  companion object {
    /** 가장 가까운 곳이 이보다 멀면 캠퍼스 밖이다(m). 정문 건너 정류장쯤까지는 봐준다. */
    const val CAMPUS_REACH = 300.0
    /** 벗어난 채로 이만큼 지나면 선 자리에서 길을 다시 찾는다. GPS 가 한 번 튄 것으로는 안 바꾼다. */
    const val REROUTE_AFTER_MS = 5_000L
  }

  // ── 파생 값 ──────────────────────────────────────────────────────────────

  val toNode get() = toId?.let { graph.nodes[it] }

  /** 길목에는 이름이 없다. 현위치로 잡힌 자리는 가까운 건물 이름을 빌려 부른다. */
  val fromNode: CampusNode?
    get() {
      val node = fromId?.let { graph.nodes[it] } ?: return null
      if (node.name.isNotEmpty()) return node
      return node.copy(name = "${Directions.landmark(graph, node.at).ifEmpty { "현위치" }} 근처")
    }

  val hasRoute get() = route?.legs?.isNotEmpty() == true
  val unreachable get() = fromId != null && toId != null && fromId != toId && route == null
  val lost get() = progress?.let { it.offRoute > Progress.offRouteLimit(locator.accuracy) } ?: false
  val arrived get() = (progress?.remainingMeters ?: Double.POSITIVE_INFINITY) <= Progress.ARRIVED_METERS
  val stepIndex get() = progress?.let { p -> Progress.stepAt(steps.map { it.meters }, p.along) } ?: 0

  private val pair get() = "${fromId.orEmpty()}→${toId.orEmpty()}"

  /** 고를 게 남았으면 펼쳐 두고, 두 곳이 다 정해지면 접어서 지도를 내준다. */
  val sheet: SheetState
    get() {
      if (picking != null) return SheetState.HIDDEN
      heldSheet?.let { if (it.first == pair) return it.second }
      return if (fromId != null && toId != null) SheetState.COLLAPSED else SheetState.EXPANDED
    }

  fun holdSheet(at: SheetState) {
    heldSheet = pair to at
  }

  // ── 시간표 ───────────────────────────────────────────────────────────────

  val hasTimetable get() = slots.isNotEmpty()
  val upcoming get() = if (hasTimetable) Schedule.next(slots, now) else null
  val upcomingPlace get() = upcoming?.let { Room.place(graph, it.slot.room) }

  /** 다음 수업 건물을 도착지로 세운다. 출발지는 사람이 고른 것을 지킨다. */
  fun goToClass() {
    upcomingPlace?.let { toId = it.id }
  }

  fun saveTimetable(list: List<ClassSlot>) {
    slots = list
    store.save(list)
  }

  fun clearTimetable() {
    slots = emptyList()
    store.clear()
  }

  // ── 곳 고르기 ────────────────────────────────────────────────────────────

  private fun assign(field: Field, node: CampusNode) {
    if (field == Field.FROM) fromId = node.id else toId = node.id
  }

  /** 목록에서 골랐다. 반대편이 비어 있으면 이어서 그 칸을 연다. */
  fun pickFromList(node: CampusNode) {
    val field = picker ?: return
    assign(field, node)
    val otherEmpty = if (field == Field.FROM) toId == null else fromId == null
    picker = if (otherEmpty) (if (field == Field.FROM) Field.TO else Field.FROM) else null
  }

  fun startMapPick(field: Field) {
    picker = null
    picking = field
  }

  fun useHereAsOrigin() {
    claimFirstFix = true
    locator.start()
    picker = null
  }

  fun pickNode(node: CampusNode) {
    picking?.let {
      assign(it, node)
      picking = null
      return
    }
    /* 안내 중에 지도를 누르다 출발지가 바뀌면 걷던 길이 사라진다. */
    if (guiding) return
    when {
      fromId == null -> fromId = node.id
      toId == null && node.id != fromId -> toId = node.id
      else -> {
        fromId = node.id
        toId = null
      }
    }
  }

  /** 고르는 중이면 누른 자리에서 120m 안의 가장 가까운 곳을 잡는다. 빈 자리에 엉뚱한 건물이 들어오면 더 나쁘다. */
  fun tapMap(at: LatLng) {
    val field = picking ?: return
    val near = graph.nearest(at) { it.kind != NodeKind.JUNCTION } ?: return
    if (near.second > 120) return
    assign(field, near.first)
    picking = null
  }

  fun swap() {
    val from = fromId
    fromId = toId
    toId = from
  }

  // ── 안내 ─────────────────────────────────────────────────────────────────

  fun startGuide() {
    if (route == null) return
    /* 위치가 없으면 따라갈 게 없다. 다만 출발지는 사람이 고른 것을 지킨다. */
    if (!locator.active) {
      claimFirstFix = false
      locator.start()
    }
    guiding = true
    holdSheet(SheetState.COLLAPSED)
  }

  fun stopGuide() {
    guiding = false
  }
}
