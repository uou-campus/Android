package site.uoucampus.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Client/src/components/RoutePanel. 시트와 기둥이 같은 내용을 쓴다. */
@Composable
fun PanelBody(state: AppState, rail: Boolean) {
  Column(Modifier.fillMaxWidth()) {
    Header(state)
    Fields(state)
    NextClassRow(state)
    GeoRow(state)
    /* 기둥에는 손잡이가 없다. 같은 단추를 여기에 둔다. */
    if (rail && state.hasRoute) {
      Text(
        if (state.guiding) "안내 종료" else "안내 시작 — 현위치를 따라갑니다",
        color = Color.White, style = Type.bodyStrong, textAlign = TextAlign.Center,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth()
          .clip(CircleShape)
          .background(if (state.guiding) UColor.gray900 else UColor.accent)
          .clickable { if (state.guiding) state.stopGuide() else state.startGuide() }
          .padding(vertical = 9.dp),
      )
    }
    Controls(state)
    Result(state)
  }
}

@Composable
private fun Header(state: AppState) {
  Row(
    Modifier.fillMaxWidth().bottomRule().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text("울산대 캠퍼스 길찾기", Modifier.weight(1f), color = UColor.textPrimary, style = Type.appTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    val has = state.hasTimetable
    Pill(
      "시간표 넣기", if (has) UColor.accent else UColor.textSecondary,
      fill = if (has) UColor.accentSoft else Color.Transparent, stroke = if (has) UColor.accent else UColor.outline,
    ) { state.timetableOpen = true }
  }
}

@Composable
private fun Fields(state: AppState) {
  Row(
    Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp).height(IntrinsicSize.Min),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      FieldButton(state, Field.FROM, "출발", state.fromNode?.name, "어디서 출발하나요")
      FieldButton(state, Field.TO, "도착", state.toNode?.name, "어디로 가나요")
    }
    Box(
      Modifier.width(44.dp).fillMaxHeight()
        .border(1.dp, UColor.outline, RoundedCornerShape(6.dp))
        .clip(RoundedCornerShape(6.dp))
        .clickable { state.swap() },
      contentAlignment = Alignment.Center,
    ) { Text("⇅", fontSize = 17.sp, color = UColor.textSecondary) }
  }
}

@Composable
private fun FieldButton(state: AppState, field: Field, label: String, value: String?, placeholder: String) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 44.dp)
      .border(1.dp, if (value == null) UColor.outline else UColor.accent, RoundedCornerShape(6.dp))
      .clip(RoundedCornerShape(6.dp))
      .clickable { state.picker = field }
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(label, Modifier.width(26.dp), color = UColor.textTertiary, style = Type.caption)
    Text(
      value ?: placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis,
      color = if (value == null) UColor.textTertiary else UColor.textPrimary,
      style = if (value == null) Type.body else Type.bodyStrong,
    )
  }
}

@Composable
private fun GeoRow(state: AppState) {
  val locator = state.locator
  Row(
    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Pill(
      if (locator.active) "현위치 끄기" else "현위치에서 출발",
      if (locator.active) UColor.here else UColor.textSecondary,
      fill = if (locator.active) UColor.hereSoft else Color.Transparent,
      stroke = if (locator.active) UColor.here else UColor.outline, h = 11.dp,
    ) { if (locator.active) locator.stop() else state.useHereAsOrigin() }
    val message = if (state.offCampus) "캠퍼스 밖이라 출발지로 못 씁니다" else when (locator.status) {
      Locator.Status.LOCATING -> "현위치를 찾는 중입니다"
      Locator.Status.COARSE -> "아직 어림한 자리입니다 — 다듬는 중"
      Locator.Status.DENIED -> "위치 권한이 막혀 있습니다"
      Locator.Status.UNSUPPORTED -> "이 기기는 위치를 못 씁니다"
      Locator.Status.FAILED -> "현위치를 못 찾았습니다"
      else -> null
    }
    message?.let { Text(it, color = UColor.textTertiary, style = Type.caption) }
    /* GPS 는 '이 안쪽' 을 알려 준다. 얼마나 어림한지 적어 두지 않으면 점이 튀는 걸 고장으로 읽는다. */
    val accuracy = locator.accuracy
    if (locator.active && accuracy != null) Text("±${jsRound(accuracy).toInt()}m", color = UColor.textTertiary, style = Type.caption)
  }
}

@Composable
private fun Controls(state: AppState) {
  Column(
    Modifier.fillMaxWidth().bottomRule().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(
      Modifier.padding(bottom = 8.dp).background(UColor.gray100, CircleShape).padding(3.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      for (profile in Profile.entries) {
        /* 표시된 지름길이 하나도 없으면 그 기준은 아무 일도 안 한다. */
        if (profile == Profile.SHORTCUT && state.shortcutCount == 0) continue
        val on = state.options.profile == profile
        Text(
          profile.label,
          color = if (on) UColor.textPrimary else UColor.textSecondary,
          style = TextStyle(fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
          modifier = Modifier.then(if (on) Modifier.shadow(1.dp, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(if (on) UColor.surface else Color.Transparent)
            .clickable { state.options = state.options.copy(profile = profile) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        )
      }
    }

    val indoor = state.options.allowIndoor
    val enabled = state.indoorCount > 0
    Row(
      Modifier.alpha(if (enabled) 1f else 0.45f)
        .clickable(enabled = enabled) { state.options = state.options.copy(allowIndoor = !indoor) }
        .padding(vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
        Modifier.padding(top = 1.dp).size(18.dp)
          .background(if (indoor) UColor.accent else UColor.surface, RoundedCornerShape(4.dp))
          .border(1.5.dp, if (indoor) UColor.accent else UColor.gray300, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
      ) { if (indoor) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("건물 안으로 질러가기", color = UColor.textPrimary, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        Text(
          if (enabled) "등록된 실내 구간 ${state.indoorCount}개. 문 닫히는 시간엔 꺼 두세요" else "아직 등록된 실내 구간이 없습니다",
          color = UColor.textTertiary, style = Type.caption,
        )
      }
    }
  }
}

@Composable
private fun Result(state: AppState) {
  val route = state.route
  if (route != null && route.legs.isNotEmpty()) {
    if (state.locator.active) ProgressBox(state)
    Summary(route, state.compare, state.roadsOnly)
    DirectionsList(route, state.steps)
  }
  if (route != null && route.legs.isEmpty()) {
    Text("출발지와 도착지가 같습니다.", Modifier.padding(16.dp), color = UColor.textTertiary, style = Type.body)
  }
  if (state.unreachable) {
    Text(
      "이어진 길이 없습니다. 두 곳 사이를 잇는 길이 아직 지도에 없습니다.",
      Modifier.padding(16.dp).note(UColor.warnSoft), color = UColor.warn, style = Type.body,
    )
  }
}

/** 걷는 동안 보이는 줄. 남은 거리·시간과 지금 할 일만 크게 띄운다. */
@Composable
private fun ProgressBox(state: AppState) {
  val progress = state.progress
  Column(
    Modifier.fillMaxWidth().bottomRule()
      .background(if (state.lost) UColor.warnSoft else UColor.accentSoft)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (progress == null) {
      Text(
        if (state.offCampus) "캠퍼스 밖에 있습니다. 들어서면 경로 위 어디쯤인지 표시합니다." else "위치를 기다리는 중입니다. 잡히면 경로 위 어디쯤인지 표시합니다.",
        color = UColor.warn, style = Type.caption,
      )
      return@Column
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(formatDuration(progress.remainingSeconds), color = UColor.textPrimary, style = Type.metric)
      Text("${formatMeters(progress.remainingMeters)} 남음", Modifier.weight(1f).padding(bottom = 3.dp), color = UColor.textSecondary, style = Type.metricSmall)
      Text("경로에서 ${formatMeters(progress.offRoute)}", Modifier.padding(bottom = 3.dp), color = UColor.textTertiary, style = Type.caption)
    }
    if (state.lost) {
      Text("경로에서 많이 벗어났습니다. 지도를 보고 되돌아가거나 출발지를 다시 잡으세요.", color = UColor.warn, style = Type.caption)
    } else {
      state.steps.getOrNull(state.stepIndex)?.let { Text(it.text, color = UColor.textPrimary, style = Type.bodyStrong) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(route: Route, compare: Route?, roadsOnly: Route?) {
  Column(
    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(formatDuration(route.seconds), color = UColor.textPrimary, style = Type.metric)
      Text(formatMeters(route.meters), Modifier.padding(bottom = 3.dp), color = UColor.textSecondary, style = Type.metricSmall)
    }

    val stats = listOf(
      "보행로·계단" to route.footMeters, "계단" to route.stairsMeters,
      "표시한 지름길" to route.shortcutMeters, "건물 안" to route.indoorMeters,
    ).filter { it.second > 0 }
    if (stats.isNotEmpty()) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, meters) in stats) {
          Row(
            Modifier.background(UColor.gray100, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Text(label, color = UColor.textSecondary, style = Type.caption)
            Text(formatMeters(meters), color = UColor.textPrimary, style = Type.caption.copy(fontWeight = FontWeight.Bold))
          }
        }
      }
    }

    /* 큰길만 따라 돌면 얼마나 손해인지. 이게 이 경로의 '지름길 이득'이다. 이 정도는 벌어져야 말할 값어치가 있다. */
    if (roadsOnly != null && route.footMeters > 0 &&
      (roadsOnly.seconds - route.seconds > 20 || roadsOnly.meters - route.meters > 30)
    ) {
      Text(
        rich(
          "큰길로만 돌아가면 ", Bold(formatDuration(roadsOnly.seconds)), " 걸립니다. 보행로와 계단을 타서 ",
          Bold(formatDuration(roadsOnly.seconds - route.seconds)), " · ", Bold(formatMeters(roadsOnly.meters - route.meters)), " 를 벌었습니다.",
        ),
        Modifier.note(UColor.accentSoft), color = UColor.ok, style = Type.caption.copy(lineHeight = 19.sp),
      )
    }

    if (roadsOnly == null && route.footMeters > 0) {
      Text(
        "견줄 만한 차도 경로를 못 찾았습니다 — 출발지나 도착지 언저리가 보행로로만 이어져 있습니다.",
        Modifier.note(UColor.gray100), color = UColor.textSecondary, style = Type.caption,
      )
    }

    if (compare != null) {
      val label = compare.options.profile.label
      Column(Modifier.note(UColor.warnSoft), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
          Canvas(Modifier.size(14.dp, 2.dp)) {
            drawLine(
              UColor.warn, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
            )
          }
          Text(
            rich(
              Bold(label),
              "${josaRo(label)} 가면 ${formatDelta(compare.meters, route.meters, ::formatMeters)} · " +
                formatDelta(compare.seconds, route.seconds, ::formatDuration),
            ),
            color = UColor.warn, style = Type.caption,
          )
        }
        Text("지도에 주황 점선으로 겹쳐 뒀습니다", Modifier.alpha(0.75f), color = UColor.warn, style = Type.caption)
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectionsList(route: Route, steps: List<DirectionStep>) {
  Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
    for (step in steps) {
      Column(
        Modifier.fillMaxWidth()
          /* 왼쪽에 이어지는 세로선. 마지막 칸만 고리로 끊는다. */
          .drawBehind {
            val x = 9.dp.toPx()
            drawLine(UColor.gray200, Offset(x, 13.dp.toPx()), Offset(x, size.height), 2.dp.toPx())
            drawCircle(UColor.accent, 3.dp.toPx(), Offset(x, 8.dp.toPx()))
          }
          .padding(start = 26.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(step.text, color = UColor.textPrimary, style = Type.body)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          if (step.shortcut) Tag("지름길", UColor.accent, UColor.accentSoft, bold = true)
          if (step.surface != Surface.PATH && step.surface != Surface.ROAD) Tag(step.surface.label)
          if (step.covered) Tag("비 안 맞음")
          Text("약 ${formatDuration(step.seconds)}", color = UColor.textTertiary, style = Type.caption)
        }
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(12.dp).border(3.dp, UColor.gray900, CircleShape))
      }
      Text(Directions.arrival(route.to), color = UColor.textPrimary, style = Type.body)
    }
  }
}

@Composable
private fun Tag(text: String, ink: Color = UColor.textSecondary, fill: Color = UColor.gray100, bold: Boolean = false) {
  Text(
    text, color = ink,
    style = TextStyle(fontSize = 11.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium),
    modifier = Modifier.background(fill, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
  )
}

/** 다음 수업 한 줄 — 다음에 어디로 가야 하고, 언제 나서야 하는가. */
@Composable
private fun NextClassRow(state: AppState) {
  if (!state.hasTimetable) return
  val upcoming = state.upcoming
  if (upcoming == null) {
    Text(
      "남은 수업이 없습니다", Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
      color = UColor.textTertiary, style = Type.caption,
    )
    return
  }
  val place = state.upcomingPlace
  val route = state.route
  /* 도착지가 그 건물로 잡혀 있을 때만 '언제 나가나' 를 말할 수 있다. */
  val aimed = place != null && route?.to?.id == place.id
  val leave = if (aimed && route != null) Schedule.leaveBy(upcoming.startsAt, route.seconds, upcoming.slot.room) else null
  val late = leave?.isBefore(state.now) == true

  Row(
    Modifier.fillMaxWidth()
      .background(if (late) UColor.warnSoft else Color.Transparent)
      .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
          Schedule.`when`(upcoming.startsAt, state.now), color = Color.White,
          style = Type.caption.copy(fontWeight = FontWeight.Bold),
          modifier = Modifier.background(UColor.accent, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        /* 과목 이름은 안 가져온다. 걸어가는 사람에게 필요한 것은 어느 건물이냐다. */
        Text(place?.name ?: upcoming.slot.room, color = UColor.textPrimary, style = Type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Text(
        "${Schedule.clock(upcoming.startsAt)} · ${upcoming.slot.room}${if (place == null) " · 건물을 못 찾음" else ""}",
        color = UColor.textSecondary, style = Type.caption,
      )
      /* route 는 leave 가 있을 때 반드시 있지만, 스마트 캐스트가 안 돼 한 번 더 묻는다. */
      if (leave != null && route != null) {
        val head = if (late) "${Schedule.clock(leave)} 에 나섰어야 합니다" else "${Schedule.clock(leave)} 출발 — ${Schedule.until(leave, state.now)}"
        Text(
          rich(Bold(head), " 걷기 ${formatDuration(route.seconds)} · 건물 안 ${formatDuration(Schedule.indoorSeconds(upcoming.slot.room))}"),
          color = if (late) UColor.warn else UColor.ok, style = Type.caption,
        )
      }
    }
    if (place != null && !aimed) {
      Pill(
        "길찾기", Color.White, fill = UColor.accent, stroke = null,
        style = Type.caption.copy(fontWeight = FontWeight.Bold), h = 13.dp, v = 7.dp,
      ) { state.goToClass() }
    }
  }
}
