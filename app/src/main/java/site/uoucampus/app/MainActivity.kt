package site.uoucampus.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
  private lateinit var locator: Locator

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val graph = CampusGraph.parse(assets.open("campus.json").bufferedReader().use { it.readText() })
    locator = Locator(this)
    val state = AppState(graph, locator, TimetableStore(this))
    val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      locator.permissionResult(result.values.any { it })
    }
    locator.askPermission = {
      permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    setContent { CampusApp(state) }
  }

  override fun onDestroy() {
    locator.stop()
    super.onDestroy()
  }
}

/** 세로로 들면 아래에서 올라오는 시트, 가로로 돌리면 왼쪽 기둥. 남는 자리의 모양이 정반대라서다. */
@Composable
fun CampusApp(state: AppState) {
  BackHandler(state.timetableOpen) { state.timetableOpen = false }
  BackHandler(!state.timetableOpen && state.picker != null) { state.picker = null }
  BackHandler(state.picker == null && state.picking != null) { state.picking = null }

  /* '다음 수업까지 30분' 은 가만 두면 거짓말이 된다. 시간표가 있을 때만 자주 돌리고, 돌아올 때 한 번 맞춘다. */
  LaunchedEffect(state.hasTimetable) {
    while (true) {
      state.now = LocalDateTime.now()
      delay(if (state.hasTimetable) 30_000L else 600_000L)
    }
  }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) state.now = LocalDateTime.now() }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }

  val density = LocalDensity.current
  BoxWithConstraints(Modifier.fillMaxSize().background(UColor.gray50)) {
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    val safeTop = bars.calculateTopPadding()
    val safeBottom = bars.calculateBottomPadding()
    val safeLeft = bars.calculateLeftPadding(LayoutDirection.Ltr)
    val safeRight = bars.calculateRightPadding(LayoutDirection.Ltr)
    val landscape = maxWidth > maxHeight
    val railWidth = minOf(maxWidth * 0.44f, 330.dp)
    var sheetHeight by remember { mutableIntStateOf(0) }

    CampusMap(
      state,
      MapInsets(
        top = if (landscape) 0 else with(density) { safeTop.roundToPx() },
        left = if (landscape) with(density) { (safeLeft + railWidth + 32.dp).roundToPx() } else 0,
        bottom = if (landscape) 0 else sheetHeight,
      ),
      landscape,
      Modifier.fillMaxSize(),
    )

    Attribution(
      Modifier.align(Alignment.BottomEnd)
        .padding(bottom = if (landscape) safeBottom else with(density) { sheetHeight.toDp() })
        .padding(2.dp),
    )

    if (landscape) Rail(state, railWidth, bars) else Sheet(state, bars) { sheetHeight = it }

    TopBar(
      state, landscape, safeTop,
      Modifier.align(Alignment.TopStart).padding(
        start = if (landscape) safeLeft + 8.dp + (if (state.picking == null) railWidth else 0.dp) else 0.dp,
        end = if (landscape) safeRight + 8.dp else 0.dp,
      ),
    )

    state.picker?.let { PlacePicker(state, it, landscape) }
    if (state.timetableOpen) TimetableSheet(state)
  }
}

@Composable
private fun BoxWithConstraintsScope.Sheet(state: AppState, bars: PaddingValues, onHeight: (Int) -> Unit) {
  val density = LocalDensity.current
  var height by remember { mutableIntStateOf(0) }
  val hidden = state.sheet == SheetState.HIDDEN
  val bodyMax = maxHeight * 0.76f - 72.dp - bars.calculateBottomPadding()
  val offset by animateIntAsState(if (hidden) height + with(density) { 20.dp.roundToPx() } else 0, label = "sheet")
  val shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
  Column(
    Modifier.align(Alignment.BottomCenter)
      .fillMaxWidth()
      .offset { IntOffset(0, offset) }
      .onSizeChanged {
        height = it.height
        onHeight(it.height)
      }
      .shadow(12.dp, shape)
      .background(UColor.surface, shape)
      .blockTouches()
      .padding(bottom = bars.calculateBottomPadding())
      .animateContentSize(),
  ) {
    SheetHandle(state)
    if (state.sheet == SheetState.EXPANDED) {
      Column(
        Modifier.heightIn(max = bodyMax)
          .verticalScroll(rememberScrollState()),
      ) { PanelBody(state, rail = false) }
    }
  }
}

@Composable
private fun Rail(state: AppState, width: Dp, bars: PaddingValues) {
  val left = bars.calculateLeftPadding(LayoutDirection.Ltr)
  val offset by animateDpAsState(if (state.sheet == SheetState.HIDDEN) -(width + left + 20.dp) else 0.dp, label = "rail")
  val shape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
  Column(
    Modifier.fillMaxHeight()
      .width(width + left)
      .offset(x = offset)
      .shadow(12.dp, shape)
      .background(UColor.surface, shape)
      .blockTouches()
      .padding(start = left, top = bars.calculateTopPadding(), bottom = bars.calculateBottomPadding())
      .verticalScroll(rememberScrollState()),
  ) { PanelBody(state, rail = true) }
}

/** 접힌 시트에서 유일하게 보이는 부분. 끌어서 접고 펴고, 그냥 눌러도 뒤집힌다. */
@Composable
private fun SheetHandle(state: AppState) {
  val density = LocalDensity.current
  var dragged by remember { mutableFloatStateOf(0f) }
  val toggle = { state.holdSheet(if (state.sheet == SheetState.EXPANDED) SheetState.COLLAPSED else SheetState.EXPANDED) }

  Column(
    Modifier.fillMaxWidth()
      .draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { dragged += it },
        onDragStopped = {
          val snap = with(density) { 24.dp.toPx() }
          if (dragged > snap) state.holdSheet(SheetState.COLLAPSED)
          else if (dragged < -snap) state.holdSheet(SheetState.EXPANDED)
          dragged = 0f
        },
      )
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = toggle)
      .padding(top = 7.dp, bottom = 10.dp),
  ) {
    Box(Modifier.align(Alignment.CenterHorizontally).size(38.dp, 4.dp).background(UColor.gray300, CircleShape))
    Row(
      Modifier.heightIn(min = 46.dp).padding(start = 16.dp, end = 16.dp, top = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(Modifier.weight(1f)) {
        val progress = state.progress
        val route = state.route
        /* 걷는 중에는 전체 길이보다 남은 만큼을 본다. */
        val metric = when {
          state.guiding && progress != null ->
            formatDuration(progress.remainingSeconds) to "${formatMeters(progress.remainingMeters)} 남음"
          state.hasRoute && route != null -> formatDuration(route.seconds) to formatMeters(route.meters)
          else -> null
        }
        metric?.let { (value, sub) ->
          Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, color = UColor.textPrimary, style = Type.metric.copy(fontSize = 19.sp))
            Text(sub, color = UColor.textSecondary, style = Type.metricSmall, modifier = Modifier.padding(bottom = 2.dp))
          }
        }
        val note = when {
          state.guiding -> "위로 끌면 안내 목록이 나옵니다"
          state.unreachable -> "이어진 길이 없습니다"
          route != null && route.legs.isEmpty() -> "출발지와 도착지가 같습니다"
          state.hasRoute -> null
          state.fromId != null || state.toId != null -> "한 곳만 더 고르면 됩니다"
          else -> "출발지와 도착지를 고르세요"
        }
        note?.let {
          Text(
            it, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (state.unreachable) UColor.warn else UColor.textSecondary,
          )
        }
      }
      if (state.hasRoute) {
        Pill(
          if (state.guiding) "안내 종료" else "안내 시작", Color.White,
          fill = if (state.guiding) UColor.gray900 else UColor.accent, stroke = null,
          style = Type.bodyStrong, h = 16.dp, v = 9.dp,
        ) { if (state.guiding) state.stopGuide() else state.startGuide() }
      }
      Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = toggle), contentAlignment = Alignment.Center) {
        Text(if (state.sheet == SheetState.EXPANDED) "▼" else "▲", fontSize = 11.sp, color = UColor.textTertiary)
      }
    }
  }
}

/** 지도 위 상단 띠. 걷는 중에는 다음 동작 하나만 크게 — 걸으면서 읽는 글은 한 줄이 넘어가면 안 읽힌다. */
@Composable
private fun TopBar(state: AppState, landscape: Boolean, safeTop: Dp, modifier: Modifier) {
  val picking = state.picking
  val route = state.route
  if (picking != null) {
    Bar(landscape, safeTop, UColor.accentSoft, UColor.accent, modifier) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("지도에서 ${picking.label}를 고르세요", Modifier.weight(1f), color = UColor.accent, style = Type.bodyStrong)
        Pill("취소", UColor.textSecondary, fill = UColor.surface, h = 12.dp, v = 6.dp) { state.picking = null }
      }
      Text("건물을 누르거나, 그 근처를 대충 눌러도 가장 가까운 곳이 잡힙니다.", color = UColor.textSecondary, style = Type.caption)
    }
  } else if (state.guiding && route != null) {
    val warn = state.lost && !state.arrived
    Bar(landscape, safeTop, if (warn) UColor.warnSoft else UColor.surface, if (warn) UColor.warn else UColor.outline, modifier) {
      Guide(state, route)
    }
  }
}

@Composable
private fun ColumnScope.Guide(state: AppState, route: Route) {
  val progress = state.progress
  val arrived = progress != null && state.arrived
  val index = state.stepIndex
  val steps = state.steps
  val next = steps.getOrNull(index + 1)

  /* 권한이 막혔는데 '기다리는 중' 이라고 해 두면 사람은 계속 기다린다. */
  val noFix = when (state.locator.status) {
    Locator.Status.DENIED -> "위치 권한이 막혀 있습니다" to "설정 → 앱 → 캠퍼스 길찾기 → 권한에서 위치를 허용하면 따라갑니다."
    Locator.Status.UNSUPPORTED -> "이 기기는 위치를 못 씁니다" to "경로와 안내 목록은 그대로 볼 수 있습니다."
    Locator.Status.FAILED -> "현위치를 못 찾았습니다" to "건물 안이면 창가나 밖으로 나가면 잡힙니다."
    else -> "위치를 기다리는 중" to "위치가 잡히면 여기서부터 안내합니다. 실내에서는 조금 걸립니다."
  }
  val (until, brief) = when {
    progress == null -> "···" to noFix.first
    arrived -> "도착" to Directions.arrival(route.to)
    next != null -> {
      /* 이번 줄이 끝나는 자리까지 남은 거리 — 그게 다음에 꺾는 자리다. */
      val untilTurn = max0(steps.take(index + 1).sumOf { it.meters } - progress.along)
      "${formatMeters(untilTurn)} 뒤" to next.brief
    }
    else -> formatMeters(progress.remainingMeters) to "곧 ${route.to.name}"
  }

  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(
      until, color = Color.White, style = Type.metricSmall,
      modifier = Modifier.heightIn(min = 30.dp).background(UColor.accent, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp),
    )
    Text(brief, Modifier.weight(1f), color = UColor.textPrimary, style = Type.section.copy(fontSize = 17.sp))
    Pill(if (arrived) "끝내기" else "안내 종료", UColor.textSecondary, fill = UColor.surface, h = 12.dp, v = 6.dp) { state.stopGuide() }
  }
  if (state.lost && !arrived && progress != null) {
    Text("경로에서 ${formatMeters(progress.offRoute)} 벗어났습니다.", color = UColor.warn, style = Type.bodyStrong)
  } else if (progress != null && !arrived) {
    (next ?: steps.getOrNull(index))?.let {
      Text(it.text, color = UColor.textSecondary, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
  if (progress != null && !arrived) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(formatDuration(progress.remainingSeconds), color = UColor.textPrimary, style = Type.metricSmall)
      Text(formatMeters(progress.remainingMeters), color = UColor.textPrimary, style = Type.metricSmall)
      Text("남음", color = UColor.textTertiary, style = Type.caption)
    }
  }
  if (progress == null) Text(noFix.second, color = UColor.textSecondary, style = Type.caption)
}

private fun max0(x: Double) = if (x < 0) 0.0 else x

@Composable
private fun Bar(landscape: Boolean, safeTop: Dp, fill: Color, stroke: Color, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
  val shape = if (landscape) RoundedCornerShape(10.dp) else RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
  Column(
    modifier.fillMaxWidth()
      .padding(top = if (landscape) 8.dp else 0.dp)
      .shadow(12.dp, shape)
      .background(fill, shape)
      .border(1.dp, stroke, shape)
      .blockTouches()
      .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 12.dp + if (landscape) 0.dp else safeTop),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    content = content,
  )
}

/** ODbL 과 타일 사용 정책이 요구하는 최소 표기. */
@Composable
private fun Attribution(modifier: Modifier) {
  val uri = LocalUriHandler.current
  Text(
    "© OpenStreetMap", fontSize = 10.sp, fontWeight = FontWeight.Normal, color = UColor.textSecondary,
    modifier = modifier.background(Color.White.copy(alpha = 0.8f))
      .clickable { uri.openUri("https://www.openstreetmap.org/copyright") }
      .padding(horizontal = 4.dp, vertical = 1.dp),
  )
}
