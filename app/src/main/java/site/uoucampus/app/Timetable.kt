package site.uoucampus.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

// Client/src/types/timetable.ts · data/timetable.ts · timetable/{room,schedule}.ts

data class ClassSlot(
  val id: String = UUID.randomUUID().toString(),
  /** 0=월 … 4=금. */
  val day: Int = 0,
  /** 자정부터 몇 분. 09:00 이면 540. */
  val startMinutes: Int = 540,
  val endMinutes: Int = 600,
  /** 적힌 그대로. 울산대는 `건물번호-호실` 이다 — 예: `7-615`. */
  val room: String = "",
)

val WEEKDAY_LABELS = listOf("월", "화", "수", "목", "금")

/** 시간표는 이 기기에만 남는다. 누가 몇 시에 어디 있는지는 남한테 줄 값이 아니다. */
class TimetableStore(context: Context) {
  private val prefs = context.getSharedPreferences("campus-route", Context.MODE_PRIVATE)

  fun load(): List<ClassSlot> = try {
    val slots = JSONObject(prefs.getString(KEY, null) ?: return emptyList()).getJSONArray("slots")
    (0 until slots.length()).map { i ->
      val o = slots.getJSONObject(i)
      ClassSlot(o.getString("id"), o.getInt("day"), o.getInt("startMinutes"), o.getInt("endMinutes"), o.getString("room"))
    }
  } catch (_: Exception) {
    /* 저장값이 깨졌다. 없는 셈 친다. */
    emptyList()
  }

  fun save(slots: List<ClassSlot>) {
    val array = JSONArray()
    for (s in slots) {
      array.put(
        JSONObject().put("id", s.id).put("day", s.day).put("startMinutes", s.startMinutes)
          .put("endMinutes", s.endMinutes).put("room", s.room),
      )
    }
    prefs.edit().putString(KEY, JSONObject().put("slots", array).put("savedAt", Instant.now().toString()).toString()).apply()
  }

  fun clear() = prefs.edit().remove(KEY).apply()

  private companion object {
    const val KEY = "campus-route:timetable"
  }
}

// ── 강의실 ────────────────────────────────────────────────────────────────

object Room {
  /** 글자를 읽다 흔히 헷갈리는 것들을 숫자로 되돌린다. */
  private val UNCONFUSE = mapOf(
    'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'l' to '1', 'I' to '1', 'i' to '1', '|' to '1', '!' to '1',
    'Z' to '2', 'z' to '2', 'S' to '5', 's' to '5', 'b' to '6', 'G' to '6', 'T' to '7', 'B' to '8', 'g' to '9', 'q' to '9',
  )
  private val DASHES = Regex("[-–—−ー－]")
  private val ROOM_LIKE = Regex("^[0-9]{1,2}-?[A-Z]?[0-9]{2,4}$")

  fun normalize(raw: String) = raw.trim().replace(DASHES, "-").replace(Regex("\\s+"), "")
    .map { UNCONFUSE[it] ?: it }.joinToString("").uppercase()

  fun looksLikeCode(raw: String) = ROOM_LIKE.matches(normalize(raw))

  /** `7-615` → 7. */
  fun buildingNo(room: String): Int? =
    Regex("^([0-9]{1,2})-").find(normalize(room))?.groupValues?.get(1)?.toInt()?.takeIf { it > 0 }

  /** `7-615` → 6층. 세 자리가 안 되거나 지하면 1층으로 본다. */
  fun floor(room: String): Int {
    val m = Regex("^[0-9]{1,2}-([A-Z]?)([0-9]+)").find(normalize(room)) ?: return 1
    val digits = m.groupValues[2]
    if (m.groupValues[1].isNotEmpty() || digits.length < 3) return 1
    return digits.dropLast(2).toIntOrNull()?.takeIf { it > 0 } ?: 1
  }

  private fun looksLikeRoom(digits: String): Boolean {
    if (digits.length !in 3..4 || digits.startsWith("0")) return false
    return digits.dropLast(2).toInt() in 1..25
  }

  /** 하이픈이 빠져 붙어 버린 코드를 되살린다. 후보가 하나로 좁혀질 때만 고친다. */
  fun repair(raw: String, known: Set<Int>): String {
    val room = normalize(raw)
    if (room.contains('-') || !Regex("^[0-9]{3,6}$").matches(room)) return room
    val candidates = listOf(1, 2).mapNotNull { cut ->
      val head = room.take(cut).toInt()
      val rest = room.drop(cut)
      if (head in known && looksLikeRoom(rest)) "$head-$rest" else null
    }
    return candidates.singleOrNull() ?: room
  }

  fun knownBuildings(graph: CampusGraph) = graph.places.mapNotNull { it.no }.toSet()

  fun place(graph: CampusGraph, room: String): CampusNode? {
    val no = buildingNo(room) ?: return null
    return graph.places.firstOrNull { it.no == no }
  }
}

// ── 다음 수업 ─────────────────────────────────────────────────────────────

data class Upcoming(val slot: ClassSlot, val startsAt: LocalDateTime)

object Schedule {
  /** 월=0 … 금=4. 주말이면 null. */
  fun weekday(date: LocalDateTime): Int? = (date.dayOfWeek.value - 1).takeIf { it in 0..4 }

  /** 아직 시작하지 않은 수업 중 가장 이른 것. 이레까지 내다본다. */
  fun next(slots: List<ClassSlot>, now: LocalDateTime): Upcoming? {
    var best: Upcoming? = null
    for (ahead in 0L..7L) {
      val date = now.plusDays(ahead)
      val day = weekday(date) ?: continue
      val midnight = date.toLocalDate().atStartOfDay()
      for (slot in slots) {
        if (slot.day != day) continue
        val startsAt = midnight.plusMinutes(slot.startMinutes.toLong())
        if (!startsAt.isAfter(now)) continue
        if (best == null || startsAt.isBefore(best.startsAt)) best = Upcoming(slot, startsAt)
      }
      if (best != null) break
    }
    return best
  }

  /** 건물 문 앞에서 강의실까지(초). 문 찾아 들어가는 데 1분, 한 층에 25초. */
  fun indoorSeconds(room: String) = 60.0 + (Room.floor(room) - 1) * 25

  fun leaveBy(startsAt: LocalDateTime, travelSeconds: Double, room: String): LocalDateTime =
    startsAt.minusSeconds((travelSeconds + indoorSeconds(room)).toLong())

  fun clock(minutes: Int) = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
  fun clock(date: LocalDateTime) = clock(date.hour * 60 + date.minute)

  fun until(target: LocalDateTime, now: LocalDateTime): String {
    val minutes = jsRound(Duration.between(now, target).toMillis() / 60000.0).toInt()
    if (minutes < 0) return "${-minutes}분 지남"
    if (minutes == 0) return "지금"
    if (minutes < 60) return "${minutes}분 뒤"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}시간 뒤" else "${h}시간 ${m}분 뒤"
  }

  /** 오늘이면 '몇 분 뒤', 내일이면 '내일 09:00', 그 뒤는 요일과 시각. */
  fun `when`(target: LocalDateTime, now: LocalDateTime): String {
    if (target.toLocalDate() == now.toLocalDate()) return until(target, now)
    if (target.toLocalDate() == now.toLocalDate().plusDays(1)) return "내일 ${clock(target)}"
    return weekday(target)?.let { "${WEEKDAY_LABELS[it]} ${clock(target)}" } ?: clock(target)
  }
}
