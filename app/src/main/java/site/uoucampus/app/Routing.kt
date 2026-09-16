package site.uoucampus.app

import org.json.JSONObject
import java.text.Collator
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Client/src/types/campus.ts · routing/{geo,graph,cost,dijkstra,route}.ts 를 그대로 옮겼다.
// 숫자 하나라도 바꾸면 웹과 길이 달라진다 — 고칠 때는 양쪽을 같이 고친다.

data class LatLng(val lat: Double, val lng: Double)

enum class NodeKind { BUILDING, PLACE, GATE, JUNCTION }

enum class Surface(val label: String) {
  ROAD("차도 옆"), PATH("보행로"), STAIRS("계단"), SLOPE("비탈"), INDOOR("건물 안"), CROSSWALK("횡단"),
}

data class CampusNode(
  val id: String,
  val kind: NodeKind,
  val name: String,
  val no: Int?,
  val aliases: List<String>,
  val lat: Double,
  val lng: Double,
  /** `approx` 면 아직 걸어 보고 확인하지 않은 좌표. */
  val precision: String,
) {
  val at get() = LatLng(lat, lng)
}

data class CampusEdge(
  val id: String,
  val from: String,
  val to: String,
  val surface: Surface,
  val shortcut: Boolean,
  val covered: Boolean,
  val connector: Boolean,
  val via: List<LatLng>,
)

// ── 기하 ──────────────────────────────────────────────────────────────────

object Geo {
  private const val EARTH_RADIUS = 6_371_008.8
  private const val M_PER_DEG_LAT = 111_320.0
  private fun rad(deg: Double) = Math.toRadians(deg)

  /** 두 점 사이 대권 거리(m). */
  fun distance(a: LatLng, b: LatLng): Double {
    val dLat = rad(b.lat - a.lat)
    val dLng = rad(b.lng - a.lng)
    val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLng / 2).let { it * it }
    return 2 * EARTH_RADIUS * asin(min(1.0, sqrt(h)))
  }

  fun length(points: List<LatLng>): Double = (1 until points.size).sumOf { distance(points[it - 1], points[it]) }

  /** a 에서 b 를 볼 때의 방위각(0~360, 북쪽이 0). */
  fun bearing(a: LatLng, b: LatLng): Double {
    val lat1 = rad(a.lat)
    val lat2 = rad(b.lat)
    val dLng = rad(b.lng - a.lng)
    val y = sin(dLng) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
  }

  /** -180~180. 양수면 오른쪽으로 꺾인 것. */
  fun turnAngle(from: Double, to: Double) = (to - from + 540) % 360 - 180

  fun toPlane(p: LatLng, origin: LatLng) = Pair(
    (p.lng - origin.lng) * M_PER_DEG_LAT * cos(rad(origin.lat)),
    (p.lat - origin.lat) * M_PER_DEG_LAT,
  )

  fun fromPlane(x: Double, y: Double, origin: LatLng) =
    LatLng(origin.lat + y / M_PER_DEG_LAT, origin.lng + x / (M_PER_DEG_LAT * cos(rad(origin.lat))))
}

// ── 그래프 ────────────────────────────────────────────────────────────────

class Link(val edge: CampusEdge, val to: String, val meters: Double, val points: List<LatLng>)

class CampusGraph(nodeList: List<CampusNode>, edgeList: List<CampusEdge>) {
  /** 문서 순서 그대로. 가까운 노드를 찾을 때 웹과 같은 순서로 훑는다. */
  val nodeList = nodeList
  val nodes = nodeList.associateBy { it.id }
  val edges = edgeList.filter { nodes.containsKey(it.from) && nodes.containsKey(it.to) }
  val links = HashMap<String, MutableList<Link>>()

  /** 이름으로 찾을 수 있는 곳들 — 길목은 뺀다. */
  val places: List<CampusNode>

  init {
    for (edge in edges) {
      val forward = listOf(nodes.getValue(edge.from).at) + edge.via + nodes.getValue(edge.to).at
      val meters = Geo.length(forward)
      links.getOrPut(edge.from) { mutableListOf() }.add(Link(edge, edge.to, meters, forward))
      links.getOrPut(edge.to) { mutableListOf() }.add(Link(edge, edge.from, meters, forward.reversed()))
    }
    /* 웹(ICU 'ko')은 한글 이름을 라틴 이름보다 앞에 세운다. java.text.Collator 는 반대라 그 한 가지만 맞춘다. */
    val korean = Collator.getInstance(Locale.KOREAN)
    fun latinLast(name: String) = if ((name.firstOrNull()?.code ?: 0) in 0xAC00..0xD7A3) 0 else 1
    places = nodeList.filter { it.kind != NodeKind.JUNCTION }
      .sortedWith(
        compareBy<CampusNode>({ it.no ?: 999 }, { latinLast(it.name) }).thenComparator { a, b -> korean.compare(a.name, b.name) },
      )
  }

  fun nearest(point: LatLng, filter: (CampusNode) -> Boolean = { true }): Pair<CampusNode, Double>? {
    var best: Pair<CampusNode, Double>? = null
    for (node in nodeList) {
      if (!filter(node)) continue
      val meters = Geo.distance(point, node.at)
      if (best == null || meters < best.second) best = node to meters
    }
    return best
  }

  companion object {
    /** Client/src/data/campus.json 을 읽는다. 앱은 빌드할 때 이 파일을 그대로 싣는다. */
    fun parse(json: String): CampusGraph {
      val doc = JSONObject(json)
      fun latLng(o: JSONObject) = LatLng(o.getDouble("lat"), o.getDouble("lng"))
      val nodes = doc.getJSONArray("nodes").let { array ->
        (0 until array.length()).map { i ->
          val o = array.getJSONObject(i)
          CampusNode(
            id = o.getString("id"),
            kind = NodeKind.valueOf(o.getString("kind").uppercase()),
            name = o.getString("name"),
            no = if (o.has("no") && !o.isNull("no")) o.getInt("no") else null,
            aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            lat = o.getDouble("lat"),
            lng = o.getDouble("lng"),
            precision = o.getString("precision"),
          )
        }
      }
      val edges = doc.getJSONArray("edges").let { array ->
        (0 until array.length()).map { i ->
          val o = array.getJSONObject(i)
          CampusEdge(
            id = o.getString("id"),
            from = o.getString("from"),
            to = o.getString("to"),
            surface = Surface.valueOf(o.getString("surface").uppercase()),
            shortcut = o.getBoolean("shortcut"),
            covered = o.getBoolean("covered"),
            connector = o.getBoolean("connector"),
            via = o.optJSONArray("via")?.let { a -> (0 until a.length()).map { latLng(a.getJSONObject(it)) } } ?: emptyList(),
          )
        }
      }
      return CampusGraph(nodes, edges)
    }
  }
}

// ── 비용 ──────────────────────────────────────────────────────────────────

enum class Profile(val label: String) {
  DISTANCE("최단거리"), TIME("최소시간"), SHORTCUT("지름길 우선");

  /** 결과를 나란히 견줄 상대. */
  val other get() = if (this == TIME) DISTANCE else TIME
}

data class RouteOptions(
  val profile: Profile = Profile.TIME,
  val allowIndoor: Boolean = true,
  /** 차도만 따라 도는 기준선. 화면에 내놓지 않는다. */
  val roadsOnly: Boolean = false,
)

object Cost {
  const val WALK_SPEED = 1.3
  private const val METER_DISLIKE = 0.12
  private const val SHORTCUT_DISCOUNT = 0.45

  private fun slowdown(s: Surface) = when (s) {
    Surface.ROAD, Surface.PATH, Surface.CROSSWALK -> 1.0
    Surface.SLOPE -> 1.35
    Surface.STAIRS -> 2.2
    Surface.INDOOR -> 1.15
  }

  private fun entryPenalty(s: Surface) = when (s) {
    Surface.CROSSWALK -> 10.0
    Surface.STAIRS -> 4.0
    Surface.INDOOR -> 12.0
    else -> 0.0
  }

  fun seconds(edge: CampusEdge, meters: Double) = meters * slowdown(edge.surface) / WALK_SPEED + entryPenalty(edge.surface)

  fun cost(edge: CampusEdge, meters: Double, options: RouteOptions): Double {
    if (!options.allowIndoor && edge.surface == Surface.INDOOR) return Double.POSITIVE_INFINITY
    if (options.roadsOnly && !edge.connector && edge.surface != Surface.ROAD) return Double.POSITIVE_INFINITY
    val base = if (options.profile == Profile.DISTANCE) meters else seconds(edge, meters) + METER_DISLIKE * meters
    return if (options.profile == Profile.SHORTCUT && edge.shortcut) base * SHORTCUT_DISCOUNT else base
  }
}

// ── 다익스트라 ────────────────────────────────────────────────────────────

/** 웹과 같은 힙. 비용이 같은 길목을 꺼내는 순서까지 맞춰야 같은 길이 나온다. */
private class MinHeap {
  private val ids = ArrayList<String>()
  private val costs = ArrayList<Double>()
  val isEmpty get() = ids.isEmpty()

  private fun swap(a: Int, b: Int) {
    ids[a] = ids[b].also { ids[b] = ids[a] }
    costs[a] = costs[b].also { costs[b] = costs[a] }
  }

  fun push(id: String, cost: Double) {
    ids.add(id)
    costs.add(cost)
    var i = ids.size - 1
    while (i > 0) {
      val parent = (i - 1) shr 1
      if (costs[parent] <= costs[i]) break
      swap(parent, i)
      i = parent
    }
  }

  fun pop(): Pair<String, Double> {
    val top = ids[0] to costs[0]
    val lastId = ids.removeAt(ids.size - 1)
    val lastCost = costs.removeAt(costs.size - 1)
    if (ids.isNotEmpty()) {
      ids[0] = lastId
      costs[0] = lastCost
      var i = 0
      while (true) {
        val l = i * 2 + 1
        val r = l + 1
        var smallest = i
        if (l < ids.size && costs[l] < costs[smallest]) smallest = l
        if (r < ids.size && costs[r] < costs[smallest]) smallest = r
        if (smallest == i) break
        swap(smallest, i)
        i = smallest
      }
    }
    return top
  }
}

private class Step(val link: Link, val from: String)

/** 건물은 목적지지 통로가 아니다. 접속선 두 가닥을 이어 붙이면 건물을 뚫고 가는 길이 된다. */
private fun canPassThrough(kind: NodeKind) = kind != NodeKind.BUILDING && kind != NodeKind.PLACE

private fun shortestPath(graph: CampusGraph, from: String, to: String, options: RouteOptions): List<Step>? {
  if (from == to) return emptyList()
  if (from !in graph.nodes || to !in graph.nodes) return null

  val best = hashMapOf(from to 0.0)
  val came = HashMap<String, Step>()
  val settled = HashSet<String>()
  val queue = MinHeap()
  queue.push(from, 0.0)

  while (!queue.isEmpty) {
    val (id, cost) = queue.pop()
    if (!settled.add(id)) continue
    if (id == to) break
    val kind = graph.nodes[id]?.kind
    if (id != from && kind != null && !canPassThrough(kind)) continue

    for (link in graph.links[id].orEmpty()) {
      if (link.to in settled) continue
      val weight = Cost.cost(link.edge, link.meters, options)
      if (!weight.isFinite()) continue
      val next = cost + weight
      if (next < (best[link.to] ?: Double.POSITIVE_INFINITY)) {
        best[link.to] = next
        came[link.to] = Step(link, id)
        queue.push(link.to, next)
      }
    }
  }

  if (to !in came) return null
  val steps = ArrayList<Step>()
  var at = to
  while (at != from) {
    val step = came.getValue(at)
    steps.add(step)
    at = step.from
  }
  return steps.reversed()
}

// ── 경로 ──────────────────────────────────────────────────────────────────

class RouteLeg(val link: Link, val from: String, val meters: Double, val seconds: Double)

class Route(
  val from: CampusNode,
  val to: CampusNode,
  val options: RouteOptions,
  val legs: List<RouteLeg>,
  val points: List<LatLng>,
  val meters: Double,
  val seconds: Double,
  val shortcutMeters: Double,
  val stairsMeters: Double,
  val indoorMeters: Double,
  /** 차도를 벗어나 보행로·계단으로 지나는 구간의 길이. */
  val footMeters: Double,
)

fun findRoute(graph: CampusGraph, fromId: String, toId: String, options: RouteOptions): Route? {
  val from = graph.nodes[fromId] ?: return null
  val to = graph.nodes[toId] ?: return null
  val steps = shortestPath(graph, fromId, toId, options) ?: return null

  val legs = steps.map { RouteLeg(it.link, it.from, it.link.meters, Cost.seconds(it.link.edge, it.link.meters)) }
  val points = ArrayList<LatLng>()
  for (leg in legs) points += if (points.isEmpty()) leg.link.points else leg.link.points.drop(1)
  fun sum(pick: (RouteLeg) -> Double) = legs.sumOf(pick)

  return Route(
    from, to, options, legs,
    points = if (legs.isEmpty()) listOf(from.at) else points,
    meters = sum { it.meters },
    seconds = sum { it.seconds },
    shortcutMeters = sum { if (it.link.edge.shortcut) it.meters else 0.0 },
    stairsMeters = sum { if (it.link.edge.surface == Surface.STAIRS) it.meters else 0.0 },
    indoorMeters = sum { if (it.link.edge.surface == Surface.INDOOR) it.meters else 0.0 },
    footMeters = sum { if (!it.link.edge.connector && it.link.edge.surface != Surface.ROAD) it.meters else 0.0 },
  )
}

fun sameRoute(a: Route?, b: Route?): Boolean {
  if (a == null || b == null) return a == null && b == null
  return a.legs.size == b.legs.size && a.legs.zip(b.legs).all { (x, y) -> x.link.edge.id == y.link.edge.id }
}
