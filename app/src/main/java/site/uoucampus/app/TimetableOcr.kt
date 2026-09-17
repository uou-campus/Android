package site.uoucampus.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Client/src/timetable/parseImage.ts. 격자를 읽는 방법은 그대로고, 글자 인식만 tesseract 대신 ML Kit 이 한다.
 *
 * 글자를 다 읽지 않는다. 요일은 칸의 가로 자리, 시각은 세로 자리에 적혀 있어서
 * 읽어야 하는 건 강의실 코드 하나다. 읽은 값은 확인 화면에서 사람이 고친 뒤에야 시간표가 된다.
 */
object TimetableOcr {
  class Result(val slots: List<ClassSlot>, val warnings: List<String>)

  class HourMark(val hour: Double, val y: Double)

  class TimeAxis(val originMinutes: Double, val minutesPerPixel: Double, val originY: Double) {
    fun minutes(y: Double) = originMinutes + (y - originY) * minutesPerPixel
  }

  private class Run(val top: Int, val bottom: Int)

  private class Word(val text: String, val x: Double, val y: Double, val right: Double, val bottom: Double)

  private const val HOUR = 60.0
  private const val CROP_WIDTH = 360.0
  private const val MARGIN = 16
  private const val INSET = 2
  private val DAY_HEADS = listOf('월', '화', '수', '목', '금')
  private val HOUR_LIKE = Regex("^([0-9]{1,2})\\s*시?$")

  private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

  /** 느리다. 메인 스레드 밖에서 부른다. */
  fun parse(bitmap: Bitmap, known: Set<Int>, progress: (String) -> Unit): Result = try {
    run(bitmap, known, progress)
  } catch (e: Exception) {
    Result(emptyList(), listOf("못 읽었습니다 — ${e.message ?: "다른 그림으로 해 보세요."}"))
  }

  // ── 격자 (웹과 같은 셈) ────────────────────────────────────────────────

  private fun snap(minutes: Double) = (jsRound(minutes / HOUR) * HOUR).toInt()
  private fun median(values: List<Double>) = values.sorted()[values.size / 2]
  private fun median(values: IntArray, n: Int) = values.copyOf(n).also { it.sort() }[n / 2]
  private fun differs(a: IntArray, b: IntArray) = abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2]) > 24

  /** 가장 멀리 떨어진 두 눈금으로 자를 세운다. 라벨은 칸 가운데 놓이므로 기준을 반 칸 올린다. */
  fun fitTimeAxis(marks: List<HourMark>): TimeAxis? {
    if (marks.size < 2) return null
    val sorted = marks.sortedBy { it.y }
    val first = sorted.first()
    val last = sorted.last()
    if (last.y == first.y || last.hour == first.hour) return null
    val minutesPerPixel = (last.hour - first.hour) * 60 / (last.y - first.y)
    val pitch = 60 / minutesPerPixel
    if (pitch < 20 || pitch > 200) return null
    return TimeAxis(first.hour * 60, minutesPerPixel, first.y - pitch / 2)
  }

  /** 가운뎃값 기울기로 자를 세우고, 거기서 30분 넘게 벗어난 눈금을 뺀다. */
  private fun onTheRuler(marks: List<HourMark>): List<HourMark> {
    val slopes = mutableListOf<Double>()
    for (i in marks.indices) for (j in i + 1 until marks.size) {
      val dy = marks[j].y - marks[i].y
      if (dy != 0.0) slopes += (marks[j].hour - marks[i].hour) * HOUR / dy
    }
    if (slopes.isEmpty()) return emptyList()
    val minutesPerPixel = median(slopes)
    if (!minutesPerPixel.isFinite() || minutesPerPixel <= 0) return emptyList()
    val base = median(marks.map { it.hour * HOUR - it.y * minutesPerPixel })
    return marks.filter { abs(it.hour * HOUR - (base + it.y * minutesPerPixel)) <= 30 }
  }

  /** 12시간제로 적힌 눈금(`9 10 11 12 1 2`)을 편다. 정오가 넘어가는 자리를 하나씩 옮겨 보며 자에 가장 많이 얹히는 것을 고른다. */
  fun readHourMarks(marks: List<HourMark>): List<HourMark> {
    val sorted = marks.sortedBy { it.y }
    var best = emptyList<HourMark>()
    for (noon in sorted.size downTo 0) {
      val guess = sorted.mapIndexed { i, m -> if (i >= noon) HourMark(m.hour + 12, m.y) else m }
      if (guess.any { it.hour > 23 }) continue
      val kept = onTheRuler(guess)
      if (kept.size > best.size) best = kept
    }
    return best
  }

  /** 라벨을 칸 위에 붙이는 테마도 있다. 수업 칸 위쪽 경계가 죄다 30분에 떨어지면 자를 반 칸 옮긴다. */
  fun alignToBlocks(axis: TimeAxis, tops: List<Int>): TimeAxis {
    if (tops.size < 3) return axis
    val off = tops.map { y -> (axis.minutes(y.toDouble()) % HOUR).let { if (it < 0) it + HOUR else it } }
    fun agree(shift: Double) = off.count { val gap = abs(it - shift); min(gap, HOUR - gap) <= 8 }
    val stay = agree(0.0)
    val move = agree(HOUR / 2)
    if (move <= stay || move < ceil(tops.size * 0.6)) return axis
    return TimeAxis(axis.originMinutes - HOUR / 2, axis.minutesPerPixel, axis.originY)
  }

  // ── 픽셀 ─────────────────────────────────────────────────────────────────

  /** 흰 바탕에 다시 그린 그림. 투명한 자리는 바탕으로 읽힌다. */
  private class Pixels(source: Bitmap) {
    val width = source.width
    val height = source.height
    val image: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
      Canvas(it).apply {
        drawColor(Color.WHITE)
        drawBitmap(source, 0f, 0f, null)
      }
    }
    val argb = IntArray(width * height).also { image.getPixels(it, 0, width, 0, 0, width, height) }

    fun rgb(x: Int, y: Int) = argb[y * width + x].let { intArrayOf(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) }
  }

  /** 한 요일 칸을 위에서 아래로 훑어 색이 칠해진 구간을 찾는다. 흰색만 바탕으로 보고, 줄 전체를 훑어 다수결로 묻는다. */
  private fun runsInColumn(px: Pixels, left: Double, right: Double, minHeight: Int): List<Run> {
    val from = max(0, jsRound(left).toInt())
    val to = min(px.width, jsRound(right).toInt())
    val looked = ceil((to - from) / 2.0).toInt()
    if (looked <= 0) return emptyList()
    val enough = max(2, jsRound(looked * 0.08).toInt())

    val runs = mutableListOf<Run>()
    var start = -1
    var colour: IntArray? = null
    /* 색이 바뀐 자리와 그 색, 그 색이 몇 줄째 이어지는지. */
    var turnY = -1
    var turnColour: IntArray? = null
    var turnRows = 0
    fun close(end: Int) {
      if (start >= 0 && end - start >= minHeight) runs += Run(start, end)
      start = -1
      colour = null
      turnY = -1
    }
    /* 색이 바뀌어도 이만큼 이어져야 다른 수업으로 본다. 과목명이 칸 너비를 거의 채우면 흰 획이 지나는
       한두 줄의 가운뎃값이 확 밝아져, 그 줄을 경계로 읽고 9시 수업을 10시로 들였다. 글자 획은 몇 줄뿐이고
       맞붙은 수업은 적어도 반 교시라 그 사이에 문턱을 둔다. */
    val hold = max(3, minHeight / 4)

    val r = IntArray(looked + 1)
    val g = IntArray(looked + 1)
    val b = IntArray(looked + 1)
    for (y in 0 until px.height) {
      var n = 0
      var x = from
      while (x < to) {
        val c = px.argb[y * px.width + x]
        val cr = c shr 16 and 0xFF
        val cg = c shr 8 and 0xFF
        val cb = c and 0xFF
        if (!(cr > 249 && cg > 249 && cb > 249)) {
          r[n] = cr
          g[n] = cg
          b[n] = cb
          n++
        }
        x += 2
      }
      if (n < enough) {
        /* 틈 바로 앞에서 색이 바뀌던 줄은 칸 가장자리다. 칸에 넣지 않는다. */
        close(if (turnY >= 0) turnY else y)
        continue
      }
      val here = intArrayOf(median(r, n), median(g, n), median(b, n))
      if (start < 0) {
        start = y
        colour = here
        continue
      }
      /* 색이 확 바뀌어 이어지면 다른 수업이 맞붙은 것이다. 사이에 흰 틈이 없을 수 있다. */
      val current = colour
      if (current == null || !differs(current, here)) {
        turnY = -1
        continue
      }
      val pending = turnColour
      if (turnY >= 0 && pending != null && !differs(pending, here)) turnRows++
      else {
        turnY = y
        turnColour = here
        turnRows = 1
      }
      if (turnRows >= hold) {
        val (at, next) = turnY to turnColour
        close(at)
        start = at
        colour = next
      }
    }
    close(if (turnY >= 0) turnY else px.height)
    return runs
  }

  // ── 글자 읽기 ────────────────────────────────────────────────────────────

  private fun read(bitmap: Bitmap): List<Word> {
    val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
    val words = mutableListOf<Word>()
    for (block in text.textBlocks) for (line in block.lines) for (element in line.elements) {
      val box = element.boundingBox ?: continue
      val t = element.text.trim()
      /* 머리글이 「월화수목금」 한 덩이로 올 수 있다. 요일 글자만으로 된 낱말은 폭을 나눠 한 자씩 가른다. */
      if (t.length > 1 && t.all { it in DAY_HEADS }) {
        val each = box.width().toDouble() / t.length
        t.forEachIndexed { i, c ->
          words += Word(c.toString(), box.left + each * i, box.top.toDouble(), box.left + each * (i + 1), box.bottom.toDouble())
        }
      } else {
        words += Word(t, box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble())
      }
    }
    return words
  }

  /** 흰 여백을 두르고 키워서 그린다. 글자가 조각 가장자리에 닿으면 인식기가 통째로 흘린다. */
  private fun draw(px: Pixels, x: Int, y: Int, w: Int, h: Int, scale: Double): Bitmap {
    val cw = jsRound(w * scale).toInt() + MARGIN * 2
    val ch = jsRound(h * scale).toInt() + MARGIN * 2
    return Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888).also {
      Canvas(it).apply {
        drawColor(Color.WHITE)
        drawBitmap(
          px.image, Rect(x, y, x + w, y + h),
          RectF(MARGIN.toFloat(), MARGIN.toFloat(), (cw - MARGIN).toFloat(), (ch - MARGIN).toFloat()),
          Paint(Paint.FILTER_BITMAP_FLAG),
        )
      }
    }
  }

  private fun cropBlock(px: Pixels, left: Double, top: Double, right: Double, bottom: Double): Bitmap? {
    val x = max(0, jsRound(left).toInt() + INSET)
    val y = max(0, jsRound(top).toInt())
    val w = min(px.width, jsRound(right).toInt() - INSET) - x
    val h = min(px.height, jsRound(bottom).toInt()) - y
    if (w < 1 || h < 1) return null
    return draw(px, x, y, w, h, min(4.0, max(1.0, CROP_WIDTH / w)))
  }

  /** 칸 안에서 글자가 실제로 놓인 세로 구간. 빈 바닥까지 넘기면 인식기가 글자 섬을 못 본다. */
  private fun contentRows(px: Pixels, left: Int, right: Int, top: Int, bottom: Int): Pair<Int, Int>? {
    val sample = mutableListOf<IntArray>()
    for (y in top until bottom step max(1, (bottom - top) / 12)) {
      for (x in left until right step max(1, (right - left) / 6)) sample += px.rgb(x, y)
    }
    if (sample.isEmpty()) return null
    val paper = IntArray(3) { c -> sample.map { it[c] }.sorted()[sample.size / 2] }

    var first = -1
    var last = -1
    for (y in top until bottom) {
      var seen = 0
      for (x in left until right step 2) {
        if (!differs(paper, px.rgb(x, y))) continue
        seen++
        if (seen >= 2) break
      }
      if (seen < 2) continue
      if (first < 0) first = y
      last = y
    }
    return if (first < 0) null else first to last
  }

  /** 캠퍼스에 실재하는 건물 번호로 풀리는 낱말을 고른다. 여럿이면 아래쪽 — 강의실은 칸의 마지막 줄에 적힌다. */
  private fun roomInBlock(words: List<Word>, known: Set<Int>): String {
    val candidates = words.filter { Room.looksLikeCode(it.text) }
    val good = candidates.filter { Room.buildingNo(Room.repair(it.text, known))?.let { no -> no in known } == true }
    var pick: Word? = null
    for (word in good.ifEmpty { candidates }) if (pick == null || word.bottom > pick.bottom) pick = word
    return pick?.let { Room.repair(it.text, known) } ?: ""
  }

  private fun readColumns(height: Int, words: List<Word>): Pair<List<Double>, Double>? {
    val heads = DAY_HEADS.map { label ->
      words.firstOrNull { it.text == label.toString() && it.y < height * 0.15 }?.let { (it.x + it.right) / 2 }
    }
    if (heads.count { it != null } < 2) return null
    val firstIndex = heads.indexOfFirst { it != null }
    val lastIndex = heads.indexOfLast { it != null }
    val pitch = (heads[lastIndex]!! - heads[firstIndex]!!) / (lastIndex - firstIndex)
    return heads.mapIndexed { i, x -> x ?: (heads[firstIndex]!! + (i - firstIndex) * pitch) } to pitch
  }

  // ponytail: 확신값으로 거르지 않는다. ML Kit 이 모델마다 확신을 주는지가 달라, 잘못 읽은 눈금은 자가 걸러 내는 데 맡긴다.
  private fun hourMarks(words: List<Word>, scale: Double, top: Double) = words.mapNotNull { w ->
    val hour = HOUR_LIKE.find(w.text)?.groupValues?.get(1)?.toDouble() ?: return@mapNotNull null
    if (hour > 23) null else HourMark(hour, top + (w.y + w.bottom) / 2 / scale)
  }

  // ── 전체 ─────────────────────────────────────────────────────────────────

  private fun run(bitmap: Bitmap, known: Set<Int>, progress: (String) -> Unit): Result {
    val px = Pixels(bitmap)
    progress("글자 읽는 중 0%")

    /* 처음 한 번은 그림 전체를 읽는다. 여기서 얻는 건 격자뿐이다 — 요일 머리글과 왼쪽 시각 눈금. */
    val page = read(px.image)
    val (columns, pitch) = readColumns(px.height, page)
      ?: return Result(emptyList(), listOf("요일 줄을 못 찾았습니다. 시간표 전체가 나온 그림인지 확인해 주세요."))

    /* 시각 눈금은 왼쪽 띠만 떼어 키워 읽는다. 통째로 읽으면 형편없이 읽힌다. */
    val leftEdge = columns[0] - pitch / 2
    val gutterWidth = jsRound(leftEdge).toInt()
    val fromStrip = if (gutterWidth >= 8) {
      val scale = min(3.0, max(1.0, 150.0 / gutterWidth))
      hourMarks(read(draw(px, 0, 0, gutterWidth, px.height, scale)), scale, -MARGIN / scale)
    } else {
      emptyList()
    }
    val marks = if (fromStrip.size >= 2) fromStrip else hourMarks(page.filter { it.right <= leftEdge }, 1.0, 0.0)

    val ruler = fitTimeAxis(readHourMarks(marks))
      ?: return Result(emptyList(), listOf("왼쪽 시각 눈금을 못 읽었습니다. 시간이 함께 나온 그림이어야 합니다."))

    /* 한 교시의 삼분의 일도 안 되는 높이는 수업 칸일 수 없다. */
    val minHeight = max(4, jsRound(HOUR / ruler.minutesPerPixel / 3).toInt())
    val found = mutableListOf<Triple<Int, Double, Run>>()
    for (day in 0 until 5) {
      val centre = jsRound(columns[day])
      for (run in runsInColumn(px, centre - pitch / 2, centre + pitch / 2, minHeight)) {
        if (ruler.minutes(run.bottom.toDouble()) - ruler.minutes(run.top.toDouble()) >= HOUR / 2) found += Triple(day, centre, run)
      }
    }

    val axis = alignToBlocks(ruler, found.map { it.third.top })

    /* 강의실은 칸을 하나씩 떼어 읽는다. 자리를 이미 아는데 인식기에게 다시 찾으라고 시킬 이유가 없다. */
    val slots = found.mapIndexed { i, (day, centre, run) ->
      progress("글자 읽는 중 ${jsRound((i + 1).toDouble() / (found.size + 1) * 100).toInt()}%")
      val start = snap(axis.minutes(run.top.toDouble()))
      val left = max(0, jsRound(centre - pitch / 2).toInt())
      val right = min(px.width, jsRound(centre + pitch / 2).toInt())
      val ink = contentRows(px, left, right, run.top, run.bottom)
      val crop = cropBlock(
        px, centre - pitch / 2,
        (ink?.let { max(run.top, it.first - 6) } ?: run.top).toDouble(),
        centre + pitch / 2,
        (ink?.let { min(run.bottom, it.second + 6) } ?: run.bottom).toDouble(),
      )
      ClassSlot(
        day = day,
        startMinutes = start,
        /* 정각으로 맞추다 보면 한 교시짜리가 0분으로 눌린다. 최소 한 시간은 준다. */
        endMinutes = max(snap(axis.minutes(run.bottom.toDouble())), start + HOUR.toInt()),
        // ponytail: 한 번만 읽는다. 웹은 한국어로 못 풀면 영어 모델로 다시 읽는데, ML Kit 한국어 모델은 라틴 숫자도 같이 읽는다.
        room = crop?.let { roomInBlock(read(it), known) } ?: "",
      )
    }
    progress("글자 읽는 중 100%")

    return Result(
      slots,
      if (slots.isEmpty()) listOf("수업 칸을 하나도 못 찾았습니다. 잘리지 않은 시간표 그림인지 확인해 주세요.") else emptyList(),
    )
  }
}
