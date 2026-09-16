package site.uoucampus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Client/src/components/PlacePicker. 손바닥만 한 지도에서 22px 짜리 점을 찍어 맞히라는 건 무리라 화면을 통째로 덮는다. */
@Composable
fun PlacePicker(state: AppState, field: Field, landscape: Boolean) {
  var query by remember(field) { mutableStateOf("") }

  Column(
    Modifier.fillMaxSize()
      .background(UColor.surface)
      .blockTouches()
      .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
      .imePadding(),
  ) {
    Row(
      Modifier.fillMaxWidth().bottomRule()
        .padding(start = 16.dp, end = 10.dp, top = if (landscape) 4.dp else 10.dp, bottom = if (landscape) 4.dp else 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("${field.label} 선택", Modifier.weight(1f), color = UColor.textPrimary, style = Type.section)
      Box(Modifier.size(36.dp).clip(CircleShape).clickable { state.picker = null }, contentAlignment = Alignment.Center) {
        Text("×", fontSize = 20.sp, color = UColor.textSecondary)
      }
    }

    /* 열자마자 키보드가 올라오면 목록이 반으로 줄어든다. 눌러야 뜨게 둔다. */
    BasicTextField(
      value = query,
      onValueChange = { query = it },
      singleLine = true,
      textStyle = Type.body.copy(color = UColor.textPrimary),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      modifier = Modifier.padding(horizontal = 16.dp, vertical = if (landscape) 8.dp else 12.dp).fillMaxWidth(),
      decorationBox = { inner ->
        Box(
          Modifier.fillMaxWidth().height(44.dp)
            .background(UColor.gray50, RoundedCornerShape(6.dp))
            .border(1.dp, UColor.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 16.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          if (query.isEmpty()) Text("건물 이름이나 번호", color = UColor.textTertiary, style = Type.body)
          inner()
        }
      },
    )

    /* 한 번 켜면 다시 누를 일이 없다. 그래도 왜 못 누르는지는 적어 둔다. */
    val status = state.locator.status
    val geoNote = when (status) {
      Locator.Status.IDLE -> "위치 권한 필요"
      Locator.Status.LOCATING -> "찾는 중"
      Locator.Status.COARSE -> "자리를 다듬는 중"
      Locator.Status.READY -> "켜져 있음"
      Locator.Status.DENIED -> "권한이 막혀 있습니다"
      Locator.Status.UNSUPPORTED -> "이 기기는 못 씁니다"
      Locator.Status.FAILED -> "못 찾았습니다 — 다시"
    }
    val geoEnabled = status == Locator.Status.IDLE || status == Locator.Status.FAILED
    if (landscape) {
      Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (field == Field.FROM) QuickRow(Modifier.weight(1f), Icons.Filled.LocationOn, "현위치에서 출발", geoNote, geoEnabled, true) { state.useHereAsOrigin() }
        QuickRow(Modifier.weight(1f), Icons.Filled.Place, "지도에서 고르기", "지도만 크게 보기", true, true) { state.startMapPick(field) }
      }
    } else {
      Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (field == Field.FROM) QuickRow(Modifier, Icons.Filled.LocationOn, "현위치에서 출발", geoNote, geoEnabled, false) { state.useHereAsOrigin() }
        QuickRow(Modifier, Icons.Filled.Place, "지도에서 고르기", "지도만 크게 보기", true, false) { state.startMapPick(field) }
      }
    }

    val hits = state.graph.places.filter { matchesPlace(it, query) }
    LazyVerticalGrid(
      GridCells.Fixed(if (landscape) 2 else 1),
      Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      if (hits.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          Text("찾는 이름이 없습니다.", Modifier.padding(vertical = 16.dp), color = UColor.textTertiary, style = Type.body)
        }
      }
      /* 검색 중에는 묶지 않고 걸린 순서대로 쭉 보여 준다. 건물이 먼저, 문은 맨 뒤. */
      if (query.isBlank()) {
        for ((kind, title) in listOf(NodeKind.BUILDING to "건물", NodeKind.PLACE to "시설", NodeKind.GATE to "출입문")) {
          val group = hits.filter { it.kind == kind }
          if (group.isEmpty()) continue
          item(span = { GridItemSpan(maxLineSpan) }) {
            Text(title, Modifier.padding(top = 12.dp, bottom = 4.dp), color = UColor.textTertiary, style = Type.caption)
          }
          items(group, key = { it.id }) { PlaceRow(state, field, it) }
        }
      } else {
        items(hits, key = { it.id }) { PlaceRow(state, field, it) }
      }
    }
  }
}

@Composable
private fun QuickRow(
  modifier: Modifier,
  icon: ImageVector,
  title: String,
  note: String,
  enabled: Boolean,
  landscape: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier.fillMaxWidth()
      .alpha(if (enabled) 1f else 0.45f)
      .border(1.dp, UColor.outline, RoundedCornerShape(6.dp))
      .clip(RoundedCornerShape(6.dp))
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = if (landscape) 9.dp else 11.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(Modifier.size(26.dp).background(UColor.accentTint, CircleShape), contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, tint = UColor.accent, modifier = Modifier.size(15.dp))
    }
    Text(title, Modifier.weight(1f), color = UColor.textPrimary, style = Type.bodyStrong, maxLines = 1)
    Text(note, color = UColor.textTertiary, style = Type.caption, maxLines = 1)
  }
}

@Composable
private fun PlaceRow(state: AppState, field: Field, node: CampusNode) {
  val value = if (field == Field.FROM) state.fromId else state.toId
  /* 반대편 칸에 이미 들어가 있는 곳. 같은 곳끼리는 경로가 없다. */
  val taken = (if (field == Field.FROM) state.toId else state.fromId) == node.id
  /* 이름을 그대로 옮겨 둔 별칭이 있다. 같은 말을 두 줄로 적을 이유는 없다. */
  val aliases = node.aliases.filter { it != node.name }

  Row(
    Modifier.fillMaxWidth()
      .alpha(if (taken) 0.4f else 1f)
      .clip(RoundedCornerShape(6.dp))
      .background(if (value == node.id) UColor.accentSoft else Color.Transparent)
      .clickable(enabled = !taken) { state.pickFromList(node) }
      .padding(horizontal = 10.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(Modifier.size(26.dp).background(UColor.gray100, CircleShape), contentAlignment = Alignment.Center) {
      Text(
        node.no?.toString() ?: "·",
        color = if (node.no == null) UColor.textTertiary else UColor.textSecondary,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
      )
    }
    Column(Modifier.weight(1f)) {
      Text(node.name, color = UColor.textPrimary, style = Type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (aliases.isNotEmpty()) {
        Text(aliases.joinToString(" · "), color = UColor.textTertiary, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    if (node.precision == "approx") {
      Text(
        "근사", color = UColor.warn, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier.background(UColor.warnSoft, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
      )
    }
    state.locator.here?.let { Text(formatMeters(Geo.distance(it, node.at)), color = UColor.textTertiary, style = Type.caption) }
  }
}
