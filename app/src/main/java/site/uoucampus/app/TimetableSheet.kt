package site.uoucampus.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 요일이 먼저, 같은 요일이면 이른 시각이 먼저. */
private fun inOrder(list: List<ClassSlot>) = list.sortedWith(compareBy({ it.day }, { it.startMinutes }))

/** Client/src/components/TimetableSheet. 읽은 값을 그대로 쓰지 않는다 — 여기서 보고 고친 뒤에야 시간표가 된다. */
@Composable
fun TimetableSheet(state: AppState) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var draft by remember { mutableStateOf(inOrder(state.slots)) }
  /** 읽는 중이면 진행 문구. */
  var reading by remember { mutableStateOf<String?>(null) }
  var failed by remember { mutableStateOf<String?>(null) }
  var warnings by remember { mutableStateOf(emptyList<String>()) }

  val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    failed = null
    warnings = emptyList()
    reading = "글자 읽을 준비를 하는 중"
    val main = Handler(Looper.getMainLooper())
    scope.launch {
      val result = withContext(Dispatchers.Default) {
        decode(context, uri)?.let { bitmap ->
          TimetableOcr.parse(bitmap, Room.knownBuildings(state.graph)) { note -> main.post { if (reading != null) reading = note } }
        }
      }
      reading = null
      if (result == null) {
        failed = "못 읽었습니다. 다른 그림으로 해 보세요."
        return@launch
      }
      warnings = result.warnings
      draft = inOrder(result.slots)
    }
  }
  val choose = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

  Column(
    Modifier.fillMaxSize()
      .background(UColor.surface)
      .blockTouches()
      .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
      .imePadding(),
  ) {
    Row(
      Modifier.fillMaxWidth().bottomRule().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("시간표", Modifier.weight(1f), color = UColor.textPrimary, style = Type.section)
      Box(Modifier.size(36.dp).clip(CircleShape).clickable { state.timetableOpen = false }, contentAlignment = Alignment.Center) {
        Text("×", fontSize = 20.sp, color = UColor.textSecondary)
      }
    }

    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      /* 표가 없을 때는 이 창에서 할 일이 첨부 하나뿐이라, 화면도 그 하나만 말한다. */
      if (draft.isEmpty()) {
        Column(
          Modifier.fillMaxWidth().padding(vertical = 24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          /* 울산대 마스코트 울리니. 지도를 펴 들고 갈 길을 보고 있다. 크기만 비례 그대로 줄여 쓴다. */
          Image(painterResource(R.drawable.ulrinee_campus_tour), contentDescription = null, modifier = Modifier.height(150.dp))
          Text(reading ?: "시간표 이미지 첨부", color = UColor.textPrimary, style = Type.action)
          Text(
            rich("에브리타임에서 ", Bold("시간표 → 설정 아이콘 → 이미지 저장"), "으로 받은 이미지를 첨부해주세요."),
            color = UColor.textSecondary, style = Type.body, textAlign = TextAlign.Center,
          )
          Pill(
            "이미지 고르기", Color.White, fill = UColor.accent, stroke = null, style = Type.bodyStrong,
            h = 20.dp, v = 10.dp, enabled = reading == null, onClick = choose,
          )
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("${draft.size}칸", Modifier.weight(1f), color = UColor.textPrimary, style = Type.bodyStrong)
          Pill(reading ?: "다시 읽기", UColor.textSecondary, enabled = reading == null, onClick = choose)
          Pill("모두 지우기", UColor.error, stroke = UColor.error) {
            state.clearTimetable()
            draft = emptyList()
            warnings = emptyList()
          }
        }
      }

      val unresolved = draft.count { Room.place(state.graph, it.room) == null }
      val notes = listOfNotNull(failed) + warnings +
        if (unresolved > 0) listOf("${unresolved}칸은 강의실을 캠퍼스 건물과 못 맞췄습니다 — 표시된 칸을 「건물번호-호실」 로 고쳐 주세요.") else emptyList()
      for (text in notes) Text(text, Modifier.note(UColor.warnSoft), color = UColor.warn, style = Type.body)

      for (slot in draft) {
        key(slot.id) {
          SlotRow(
            slot, Room.place(state.graph, slot.room),
            onChange = { changed -> draft = draft.map { if (it.id == changed.id) changed else it } },
            onRemove = { draft = draft.filter { it.id != slot.id } },
          )
        }
      }

      Text(
        "칸 추가", color = UColor.textSecondary, style = Type.bodyStrong, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
          .drawBehind {
            drawRoundRect(
              UColor.outline, cornerRadius = CornerRadius(6.dp.toPx()),
              style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
          }
          .clip(RoundedCornerShape(6.dp))
          .clickable { draft = draft + ClassSlot() }
          .padding(vertical = 10.dp),
      )
    }

    Row(
      Modifier.fillMaxWidth()
        .drawBehind { drawLine(UColor.outline, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
        .padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        "취소", color = UColor.textSecondary, style = Type.bodyStrong, textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f).border(1.dp, UColor.outline, CircleShape).clip(CircleShape)
          .clickable { state.timetableOpen = false }.padding(vertical = 11.dp),
      )
      /* 저장할 것이 없으면 물러선다 — 표가 비었을 때의 다음 걸음은 첨부지 저장이 아니다. */
      val canSave = reading == null && draft.isNotEmpty()
      Text(
        if (draft.isEmpty()) "저장" else "${draft.size}칸 저장", color = Color.White, style = Type.bodyStrong, textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f).clip(CircleShape)
          .background(if (canSave) UColor.accent else UColor.accent.copy(alpha = 0.4f))
          .clickable(enabled = canSave) {
            state.saveTimetable(inOrder(draft.filter { it.room.isNotBlank() }))
            state.timetableOpen = false
          }
          .padding(vertical = 11.dp),
      )
    }
  }
}

@Composable
private fun SlotRow(slot: ClassSlot, place: CampusNode?, onChange: (ClassSlot) -> Unit, onRemove: () -> Unit) {
  Column(
    Modifier.fillMaxWidth()
      .background(if (place == null) UColor.warnSoft else UColor.gray50, RoundedCornerShape(10.dp))
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      Choice(WEEKDAY_LABELS[slot.day], (0..4).toList(), { WEEKDAY_LABELS[it] }) { onChange(slot.copy(day = it)) }
      Choice(Schedule.clock(slot.startMinutes), hours(8..22, slot.startMinutes), { Schedule.clock(it) }) {
        onChange(slot.copy(startMinutes = it, endMinutes = maxOf(slot.endMinutes, it + 60)))
      }
      Text("–", color = UColor.textTertiary)
      Choice(Schedule.clock(slot.endMinutes), hours(9..23, slot.endMinutes, after = slot.startMinutes), { Schedule.clock(it) }) {
        onChange(slot.copy(endMinutes = it))
      }
      Spacer(Modifier.weight(1f))
      Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
        Text("×", fontSize = 20.sp, color = UColor.textTertiary)
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      BasicTextField(
        value = slot.room,
        onValueChange = { onChange(slot.copy(room = Room.normalize(it))) },
        singleLine = true,
        textStyle = Type.body.copy(color = UColor.textPrimary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        decorationBox = { inner ->
          Box(
            Modifier.width(110.dp).height(36.dp)
              .background(UColor.surface, RoundedCornerShape(6.dp))
              .border(1.dp, UColor.outline, RoundedCornerShape(6.dp))
              .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
          ) {
            if (slot.room.isEmpty()) Text("7-615", color = UColor.textTertiary, style = Type.body)
            inner()
          }
        },
      )
      /* 빈 칸과 못 찾은 칸은 할 일이 다르다. 채우라는 말과 고치라는 말을 뭉뚱그리지 않는다. */
      Text(
        place?.name ?: if (slot.room.isBlank()) "강의실을 넣어 주세요" else "건물을 못 찾음",
        color = if (place == null) UColor.warn else UColor.textSecondary, style = Type.caption,
      )
    }
  }
}

@Composable
private fun <T> Choice(label: String, options: List<T>, text: (T) -> String, onSelect: (T) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    Text(
      "$label ▾", color = UColor.textPrimary, style = Type.bodyStrong,
      modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp),
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      for (option in options) {
        DropdownMenuItem(text = { Text(text(option)) }, onClick = {
          open = false
          onSelect(option)
        })
      }
    }
  }
}

/** 읽어 온 시각이 목록 밖이어도 사라지지 않게 지금 값은 늘 넣어 둔다. */
private fun hours(range: IntRange, keep: Int, after: Int = -1) =
  (range.map { it * 60 }.filter { it > after } + keep).toSortedSet().toList()

/** 픽셀을 읽어야 하므로 소프트웨어 비트맵으로 푼다. 28 부터는 사진 방향도 바로잡아 준다. */
private fun decode(context: Context, uri: Uri): Bitmap? = try {
  if (Build.VERSION.SDK_INT >= 28) {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
  } else {
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
  }
} catch (_: Exception) {
  null
}
