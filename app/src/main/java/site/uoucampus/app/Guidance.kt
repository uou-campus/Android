package site.uoucampus.app

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Client/src/routing/{progress,directions}.ts · utils/{format,korean,place}.ts

// ── 진행 ──────────────────────────────────────────────────────────────────

class RouteProgress(
  val snapped: LatLng,
  val offRoute: Double,
  val along: Double,
  val remainingMeters: Double,
  val remainingSeconds: Double,
  val passed: List<LatLng>,
)

object Progress {
  /** 도착으로 보는 거리. 건물 좌표는 출입구가 아니라 한가운데다. */
  const val ARRIVED_METERS = 15.0

  /** 35m 와 GPS 오차 반경의 1.5배 중 큰 쪽. */
  fun offRouteLimit(accuracy: Double?) = max(35.0, (accuracy ?: 0.0) * 1.5)

  fun track(route: Route, at: LatLng): RouteProgress? {
    val points = route.points
    if (points.size < 2) return null

    var bestIndex = 0
    var bestT = 0.0
    var bestMeters = Double.POSITIVE_INFINITY
    for (i in 1 until points.size) {
      val (px, py) = Geo.toPlane(at, points[i - 1])
      val (qx, qy) = Geo.toPlane(points[i], points[i - 1])
      val lengthSquared = qx * qx + qy * qy
      val t = if (lengthSquared == 0.0) 0.0 else max(0.0, min(1.0, (px * qx + py * qy) / lengthSquared))
      val meters = hypot(px - qx * t, py - qy * t)
      if (i == 1 || meters < bestMeters) {
        bestIndex = i
        bestT = t
        bestMeters = meters
      }
    }

    val a = points[bestIndex - 1]
    val (bx, by) = Geo.toPlane(points[bestIndex], a)
    val snapped = Geo.fromPlane(bx * bestT, by * bestT, a)

    var along = (1 until bestIndex).sumOf { Geo.distance(points[it - 1], points[it]) }
    along += Geo.distance(a, snapped)

    /* 남은 시간은 구간마다 속도가 달라서, 지나온 만큼만 덜어 낸다. */
    var walked = 0.0
    var remainingSeconds = 0.0
    for (leg in route.legs) {
      val legStart = walked
      walked += leg.meters
      if (walked <= along) continue
      val left = min(leg.meters, walked - max(along, legStart))
      remainingSeconds += if (leg.meters > 0) left / leg.meters * leg.seconds else 0.0
    }

    return RouteProgress(
      snapped = snapped,
      offRoute = bestMeters,
      along = along,
      remainingMeters = max(0.0, route.meters - along),
      remainingSeconds = remainingSeconds,
      passed = points.subList(0, bestIndex) + snapped,
    )
  }

  /** 따라온 거리로 몇 번째 안내 줄인지. */
  fun stepAt(stepMeters: List<Double>, along: Double): Int {
    var walked = 0.0
    for ((i, m) in stepMeters.withIndex()) {
      walked += m
      if (along < walked) return i
    }
    return max(0, stepMeters.size - 1)
  }
}

// ── 안내문 ────────────────────────────────────────────────────────────────

enum class Turn(val text: String, val brief: String) {
  STRAIGHT("직진", "직진"),
  SLIGHT_LEFT("왼쪽으로 살짝 꺾어", "왼쪽으로 살짝"),
  SLIGHT_RIGHT("오른쪽으로 살짝 꺾어", "오른쪽으로 살짝"),
  LEFT("왼쪽으로", "왼쪽으로 꺾기"),
  RIGHT("오른쪽으로", "오른쪽으로 꺾기"),
  BACK("왔던 쪽으로", "왔던 쪽으로");

  companion object {
    fun of(angle: Double): Turn {
      val a = abs(angle)
      return when {
        a < 20 -> STRAIGHT
        a > 135 -> BACK
        a < 55 -> if (angle > 0) SLIGHT_RIGHT else SLIGHT_LEFT
        else -> if (angle > 0) RIGHT else LEFT
      }
    }
  }
}

class DirectionStep(
  val turn: Turn?,
  val text: String,
  val meters: Double,
  val seconds: Double,
  val surface: Surface,
  val shortcut: Boolean,
  val covered: Boolean,
) {
  /** 걸으면서 흘깃 보는 배너에는 문장이 아니라 낱말이 맞다. */
  val brief get() = (turn ?: Turn.STRAIGHT).brief
}

object Directions {
  private const val MERGE_ANGLE = 40.0
  private const val MERGE_METERS = 30.0
  private const val LANDMARK_METERS = 45.0

  private fun phrase(s: Surface) = when (s) {
    Surface.STAIRS -> "계단으로"
    Surface.SLOPE -> "비탈을 따라"
    Surface.INDOOR -> "건물 안을 지나"
    Surface.CROSSWALK -> "길을 건너"
    else -> null
  }

  /** 길목에는 이름이 없다. 근처 건물을 표지로 삼는다. */
  fun landmark(graph: CampusGraph, at: LatLng): String {
    var best: Pair<String, Double>? = null
    for (place in graph.places) {
      val meters = Geo.distance(at, place.at)
      if (meters > LANDMARK_METERS) continue
      if (best == null || meters < best.second) best = place.name to meters
    }
    return best?.first ?: ""
  }

  private fun roundMeters(m: Double) =
    if (m < 100) jsRound(m / 5).toInt() * 5 else jsRound(m / 10).toInt() * 10

  private class Group(val legs: MutableList<RouteLeg>, val turn: Turn?)

  /** 크게 꺾는 자리만 남기고, 짧은 토막은 앞 줄에 흡수시킨 뒤, 남은 자리마다 근처 건물 이름을 붙인다. */
  fun steps(graph: CampusGraph, route: Route): List<DirectionStep> {
    val first = route.legs.firstOrNull() ?: return emptyList()
    fun entry(leg: RouteLeg) = Geo.bearing(leg.link.points[0], leg.link.points[1])
    fun exit(leg: RouteLeg) = leg.link.points.let { Geo.bearing(it[it.size - 2], it[it.size - 1]) }
    fun sameKind(a: RouteLeg, b: RouteLeg) = a.link.edge.surface == b.link.edge.surface &&
      a.link.edge.shortcut == b.link.edge.shortcut && a.link.edge.covered == b.link.edge.covered
    fun nameOf(id: String) = graph.nodes[id]?.name ?: ""

    /* 1. 크게 꺾지 않고 성격도 같은 구간끼리 묶는다. */
    val groups = mutableListOf(Group(mutableListOf(first), null))
    for (leg in route.legs.drop(1)) {
      val prev = groups.last().legs.last()
      val angle = Geo.turnAngle(exit(prev), entry(leg))
      if (abs(angle) < MERGE_ANGLE && sameKind(prev, leg)) groups.last().legs.add(leg)
      else groups.add(Group(mutableListOf(leg), Turn.of(angle)))
    }

    /* 2. 너무 짧은 토막은 앞 줄에 붙인다. */
    val merged = mutableListOf<Group>()
    for (group in groups) {
      if (merged.isNotEmpty() && group.legs.sumOf { it.meters } < MERGE_METERS) merged.last().legs.addAll(group.legs)
      else merged.add(group)
    }

    /* 3. 줄마다 표지를 붙여 문장으로 만든다. */
    var lastLandmark = nameOf(first.from)
    return merged.mapIndexed { index, group ->
      val head = group.legs.first()
      val tail = group.legs.last()
      val edge = head.link.edge

      val at = if (index == 0) "" else landmark(graph, head.link.points[0])
      val showLandmark = at.isNotEmpty() && at != lastLandmark
      if (at.isNotEmpty()) lastLandmark = at

      val target = nameOf(tail.link.to)
      val parts = mutableListOf<String>()
      if (index == 0) {
        parts += "${nameOf(head.from).ifEmpty { "출발지" }}에서 출발,"
      } else {
        if (showLandmark) parts += "$at 앞에서"
        parts += (group.turn ?: Turn.STRAIGHT).text
      }
      phrase(edge.surface)?.let { parts += it }
      parts += "${roundMeters(group.legs.sumOf { it.meters })}m"
      if (target.isNotEmpty() && target != at) parts += "— $target"

      DirectionStep(
        group.turn, parts.joinToString(" "), group.legs.sumOf { it.meters }, group.legs.sumOf { it.seconds },
        edge.surface, edge.shortcut, edge.covered,
      )
    }
  }

  fun arrival(to: CampusNode) = "${to.name}${to.no?.let { " (${it}번)" } ?: ""} 도착"
}

// ── 글자 모양 ─────────────────────────────────────────────────────────────

/** 자바스크립트 Math.round 와 같은 반올림(.5 는 늘 위로). */
fun jsRound(x: Double) = floor(x + 0.5)

fun formatMeters(meters: Double) =
  if (meters >= 1000) String.format(Locale.US, "%.2fkm", meters / 1000) else "${jsRound(meters).toInt()}m"

fun formatDuration(seconds: Double): String {
  if (seconds < 60) return "${jsRound(seconds).toInt()}초"
  val minutes = jsRound(seconds / 60).toInt()
  if (minutes < 60) return "${minutes}분"
  return "${minutes / 60}시간 ${minutes % 60}분"
}

fun formatDelta(value: Double, base: Double, unit: (Double) -> String): String {
  val diff = value - base
  if (abs(diff) < 1) return "같음"
  return "${if (diff > 0) "+" else "−"}${unit(abs(diff))}"
}

/** '로 / 으로'. 받침이 없거나 ㄹ 받침이면 '로'. */
fun josaRo(word: String): String {
  val code = word.lastOrNull()?.code ?: return "로"
  if (code < 0xAC00 || code > 0xD7A3) return "로"
  val final = (code - 0xAC00) % 28
  return if (final == 0 || final == 8) "로" else "으로"
}

/** 이름·별칭·건물번호 아무거나 걸리면 후보로 올린다. */
fun matchesPlace(node: CampusNode, query: String): Boolean {
  val q = query.trim().lowercase()
  if (q.isEmpty()) return true
  if (node.no?.toString() == q) return true
  if (node.name.lowercase().contains(q)) return true
  return node.aliases.any { it.lowercase().contains(q) }
}
