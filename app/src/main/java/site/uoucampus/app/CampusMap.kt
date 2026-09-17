package site.uoucampus.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** 패널이 지도를 가리는 만큼(px). 캠퍼스를 보이는 자리에 맞출 때 피한다. */
class MapInsets(val top: Int, val left: Int, val bottom: Int)

/**
 * Client/src/components/Map + map/mapStyle.ts. 바탕은 웹과 같은 OpenStreetMap 타일이다 —
 * 캠퍼스 안 보행로와 계단이 그려진 지도는 그쪽뿐이다.
 */
@Composable
fun CampusMap(state: AppState, insets: MapInsets, landscape: Boolean, modifier: Modifier = Modifier) {
  val holder = remember { MapHolder(state) }
  AndroidView(modifier = modifier, factory = { holder.create(it) }, update = { holder.update(it, insets, landscape) })
}

private class MapHolder(private val state: AppState) {
  private var insets = MapInsets(0, 0, 0)
  private var fitKey: String? = null
  private var legKey: String? = null
  /** 마지막으로 맞춘 대상. 시트가 움직이면 같은 것을 다시 맞춘다. */
  private var lastFit: List<LatLng>? = null
  private var fitRequest: Pair<List<LatLng>, Boolean>? = null
  private var following = false
  /** 안내를 켠 뒤 위치가 처음 잡히는 순간에 한 번만 당긴다. 그 뒤로 배율은 손대지 않는다. */
  private var needsGuideZoom = false
  private var lastHere: LatLng? = null
  private var lastAccuracy: Double? = null

  fun create(context: Context): MapView {
    Configuration.getInstance().apply {
      load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
      /* 타일 사용 정책: 어느 앱이 받아 가는지 알아볼 이름을 보낸다. */
      userAgentValue = context.packageName
    }
    return MapView(context).apply {
      setTileSource(TileSourceFactory.MAPNIK)
      setMultiTouchControls(true)
      zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
      isTilesScaledToDpi = true
      minZoomLevel = 13.0
      maxZoomLevel = 19.0
      controller.setZoom(16.0)
      controller.setCenter(GeoPoint(35.5442, 129.2566))
      overlays.add(CampusOverlay(state, resources.displayMetrics.density))
      addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) post { layoutChanged(this) }
      }
    }
  }

  fun update(map: MapView, insets: MapInsets, landscape: Boolean) {
    this.insets = insets
    /* 여기서 읽은 값이 바뀌면 다시 불린다. 그릴 것은 겹판이 그때그때 읽는다. */
    val route = state.route
    val markers = listOf(state.compare, state.progress, state.fromId, state.toId)
    val sheet = state.sheet
    val picking = state.picking != null
    val guiding = state.guiding
    val here = state.locator.here
    val accuracy = state.locator.accuracy
    map.invalidate()

    /* 시트가 움직이면 지도에 남는 자리가 달라진다. 따라가는 중에는 손대지 않는다. */
    val fitNow = "$sheet|$picking|$guiding|$landscape|${insets.left}|${insets.bottom}"
    if (fitNow != fitKey) {
      fitKey = fitNow
      if (!guiding) {
        val all = state.graph.nodeList.map { it.at }
        /* 고르는 중에는 어느 곳이든 누를 수 있게 캠퍼스 전체를. 그 맞춤은 기억하지 않는다. */
        if (picking) fit(map, all, remember = false) else fit(map, lastFit ?: all)
      }
    }

    /* 출발·도착이 바뀌면 경로가 다 보이게. 기준만 바꿀 때는 화면을 튀기지 않는다. */
    val legNow = route?.let { "${it.from.id}→${it.to.id}" } ?: ""
    if (legNow != legKey) {
      legKey = legNow
      if (route != null && route.points.size >= 2) fit(map, route.points)
    }

    val followChanged = guiding != following
    if (followChanged) {
      following = guiding
      needsGuideZoom = guiding
    }
    if (here != lastHere || accuracy != lastAccuracy || followChanged) {
      lastHere = here
      lastAccuracy = accuracy
      if (here != null) follow(map, here)
    }
  }

  private fun layoutChanged(map: MapView) {
    val here = state.locator.here
    /* 폰을 돌렸다. 걷는 중이면 발밑으로, 아니면 보던 것을 다시 맞춘다. */
    if (following && here != null && !state.offCampus) map.controller.setCenter(GeoPoint(here.lat, here.lng))
    else fitRequest?.let { fit(map, it.first, it.second) }
  }

  /** Leaflet 의 fitBounds 와 같은 셈. 배율은 정수로 내린다 — 타일이 흐려지지 않게. */
  private fun fit(map: MapView, points: List<LatLng>, remember: Boolean = true) {
    if (remember) lastFit = points
    fitRequest = points to remember
    val w = map.width.toDouble()
    val h = map.height.toDouble()
    if (w == 0.0 || h == 0.0 || points.isEmpty()) return

    /* 화면 밖으로 내보낸 시트는 아무것도 가리지 않는다. 가려도 절반쯤은 남긴다. */
    val covered = state.sheet != SheetState.HIDDEN
    val pad = 16 * map.resources.displayMetrics.density
    val left = (if (covered) min(insets.left.toDouble(), w * 0.55) else 0.0) + pad
    val bottom = (if (covered) min(insets.bottom.toDouble(), h * 0.55) else 0.0) + pad
    val top = insets.top + pad
    val availW = max(1.0, w - left - pad)
    val availH = max(1.0, h - top - bottom)

    fun x(lng: Double) = (lng + 180) / 360
    fun y(lat: Double) = Math.toRadians(lat).let { (1 - ln(tan(it) + 1 / cos(it)) / PI) / 2 }
    val minX = points.minOf { x(it.lng) }
    val maxX = points.maxOf { x(it.lng) }
    val minY = points.minOf { y(it.lat) }
    val maxY = points.maxOf { y(it.lat) }

    val tile = TileSystem.getTileSize().toDouble()
    val zoom = floor(min(log2(availW / (max(maxX - minX, 1e-9) * tile)), log2(availH / (max(maxY - minY, 1e-9) * tile))))
      .coerceIn(map.minZoomLevel, map.maxZoomLevel)
    val scale = tile * 2.0.pow(zoom)
    /* 보이는 칸의 가운데에 오도록, 지도 한가운데를 그만큼 비켜 둔다. */
    val cx = (minX + maxX) / 2 * scale - (left + availW / 2 - w / 2)
    val cy = (minY + maxY) / 2 * scale - (top + availH / 2 - h / 2)
    map.controller.setZoom(zoom)
    map.controller.setCenter(GeoPoint(Math.toDegrees(atan(sinh(PI * (1 - 2 * cy / scale)))), cx / scale * 360 - 180))
  }

  private fun follow(map: MapView, here: LatLng) {
    /* 캠퍼스 밖의 점을 쫓아가면 캠퍼스가 화면에서 사라진다. */
    if (state.offCampus) return
    val point = GeoPoint(here.lat, here.lng)
    if (following && needsGuideZoom) {
      needsGuideZoom = false
      map.controller.setZoom(max(map.zoomLevelDouble, 18.0))
      map.controller.setCenter(point)
    } else if (following) {
      map.controller.setCenter(point)
    } else {
      /* 안내를 안 켰을 때는 정말로 화면을 벗어났을 때만 옮긴다. */
      val box = map.boundingBox
      val latPad = box.latitudeSpan * 0.15
      val lngPad = box.longitudeSpanWithDateLine * 0.15
      val inside = here.lat in (box.latSouth + latPad)..(box.latNorth - latPad) &&
        here.lng in (box.lonWest + lngPad)..(box.lonEast - lngPad)
      if (!inside) map.controller.setCenter(point)
    }
  }
}

/** 보행망, 경로, 곳 표시, 현위치를 한 겹에 다 그린다. */
private class CampusOverlay(private val state: AppState, private val density: Float) : Overlay() {
  private class Line(val color: Int, val width: Float, val alpha: Float, dash: FloatArray? = null, density: Float) {
    val effect: PathEffect? = dash?.let { d -> DashPathEffect(FloatArray(d.size) { d[it] * density }, 0f) }
    val px = width * density
  }

  private fun line(color: Long, width: Float, alpha: Float, dash: FloatArray? = null) =
    Line(color.toInt(), width, alpha, dash, density)

  /* 지도 위에서 초록은 가야 할 길 하나뿐이다. 배경은 회색 농담으로만 말한다. */
  private val styles = mapOf(
    "connector" to line(0xFFD1D5DB, 1f, 0.5f, floatArrayOf(1f, 4f)),
    "shortcut" to line(0xFF374151, 3.5f, 0.62f),
    "stairs" to line(0xFF6B7280, 3f, 0.55f, floatArrayOf(2f, 4f)),
    "indoor" to line(0xFF6B7280, 3f, 0.5f, floatArrayOf(1f, 6f)),
    "road" to line(0xFF9CA3AF, 1.5f, 0.28f),
    "path" to line(0xFF6B7280, 2.5f, 0.4f),
    "compare" to line(0xFFB45309, 4f, 0.85f, floatArrayOf(7f, 6f)),
    "casing" to line(0xFFFFFFFF, 12f, 0.95f),
    "route" to line(0xFF16A152, 6f, 1f),
    "passed" to line(0xFF9CA3AF, 5f, 0.65f),
    "shortcutOverlay" to line(0xFFFFFFFF, 2f, 0.9f, floatArrayOf(1f, 7f)),
  )

  private val base: List<Pair<Line, List<LatLng>>> = run {
    val groups = HashMap<String, MutableList<List<LatLng>>>()
    for ((id, links) in state.graph.links) {
      /* 양방향이라 같은 간선이 두 번 나온다. 한쪽만 그린다. */
      for (link in links) {
        if (link.edge.from != id) continue
        val e = link.edge
        val key = when {
          e.connector -> "connector"
          e.shortcut -> "shortcut"
          e.surface == Surface.STAIRS -> "stairs"
          e.surface == Surface.INDOOR -> "indoor"
          e.surface == Surface.ROAD -> "road"
          else -> "path"
        }
        groups.getOrPut(key) { mutableListOf() }.add(link.points)
      }
    }
    listOf("road", "connector", "path", "indoor", "stairs", "shortcut")
      .flatMap { key -> groups[key].orEmpty().map { styles.getValue(key) to it } }
  }

  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = Typeface.DEFAULT_BOLD
  }
  private val tagInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 12 * density
    color = 0xFF111111.toInt()
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
  }
  private val approxEffect = DashPathEffect(floatArrayOf(3 * density, 2 * density), 0f)
  private val path = Path()
  private val point = Point()

  private fun project(map: MapView, p: LatLng): Point {
    map.projection.toPixels(GeoPoint(p.lat, p.lng), point)
    return point
  }

  private fun draw(canvas: Canvas, map: MapView, points: List<LatLng>, line: Line) {
    path.reset()
    points.forEachIndexed { i, p ->
      val at = project(map, p)
      if (i == 0) path.moveTo(at.x.toFloat(), at.y.toFloat()) else path.lineTo(at.x.toFloat(), at.y.toFloat())
    }
    stroke.color = line.color
    stroke.alpha = (line.alpha * 255).toInt()
    stroke.strokeWidth = line.px
    stroke.pathEffect = line.effect
    canvas.drawPath(path, stroke)
  }

  override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
    if (shadow) return
    for ((line, points) in base) draw(canvas, map, points, line)

    state.route?.let { route ->
      state.compare?.let { draw(canvas, map, it.points, styles.getValue("compare")) }
      /* 흰 테를 한 겹 깔아 배경과 떼어 놓는다. */
      draw(canvas, map, route.points, styles.getValue("casing"))
      draw(canvas, map, route.points, styles.getValue("route"))
      /* 이미 지나온 만큼은 회색으로 덮는다. 남은 길만 초록으로 남는다. */
      state.progress?.passed?.takeIf { it.size > 1 }?.let { draw(canvas, map, it, styles.getValue("passed")) }
      for (leg in route.legs) if (leg.link.edge.shortcut) draw(canvas, map, leg.link.points, styles.getValue("shortcutOverlay"))
    }

    val here = state.locator.here
    val accuracy = state.locator.accuracy
    /* GPS 는 '여기' 가 아니라 '이 안쪽' 을 알려 준다. 점만큼 작은 반경은 그리지 않는다. */
    if (here != null && accuracy != null && accuracy > 10) {
      val metersPerPixel = 40_075_016.686 * cos(Math.toRadians(here.lat)) / (TileSystem.getTileSize() * 2.0.pow(map.zoomLevelDouble))
      val at = project(map, here)
      val radius = (accuracy / metersPerPixel).toFloat()
      fill.color = 0x122563EB
      canvas.drawCircle(at.x.toFloat(), at.y.toFloat(), radius, fill)
      stroke.color = 0x4D2563EB
      stroke.strokeWidth = density
      stroke.pathEffect = null
      canvas.drawCircle(at.x.toFloat(), at.y.toFloat(), radius, stroke)
    }

    val endpoints = state.graph.places.filter { it.id == state.fromId || it.id == state.toId }
    for (node in state.graph.places) if (node !in endpoints) marker(canvas, map, node)
    if (here != null) {
      val at = project(map, here)
      fill.color = 0x2E2563EB
      canvas.drawCircle(at.x.toFloat(), at.y.toFloat(), 15 * density, fill)
      fill.color = 0xFFFFFFFF.toInt()
      canvas.drawCircle(at.x.toFloat(), at.y.toFloat(), 9 * density, fill)
      fill.color = 0xFF2563EB.toInt()
      canvas.drawCircle(at.x.toFloat(), at.y.toFloat(), 6 * density, fill)
    }
    for (node in endpoints) marker(canvas, map, node)

    /* 이름표는 17단계부터. 캠퍼스 전체가 보이는 배율에서는 글자 무더기가 된다. */
    if (map.zoomLevelDouble >= 17) {
      fill.color = 0xEBFFFFFF.toInt()
      for (node in state.graph.places) {
        val at = project(map, node.at)
        val r = (if (node in endpoints) 14 else 11) * density
        val left = at.x + r + 2 * density
        val width = tagInk.measureText(node.name) + 12 * density
        val top = at.y - (tagInk.textSize / 2 + 3 * density)
        val box = RectF(left, top, left + width, at.y + tagInk.textSize / 2 + 3 * density)
        canvas.drawRoundRect(box, 4 * density, 4 * density, fill)
        canvas.drawText(node.name, left + 6 * density, box.centerY() - (tagInk.descent() + tagInk.ascent()) / 2, tagInk)
      }
    }
  }

  private fun marker(canvas: Canvas, map: MapView, node: CampusNode) {
    val from = node.id == state.fromId
    val to = node.id == state.toId
    val endpoint = from || to
    val r = (if (endpoint) 14 else 11) * density - density
    val at = project(map, node.at)
    val x = at.x.toFloat()
    val y = at.y.toFloat()
    val (fillColor, strokeColor, textColor) = when {
      from -> Triple(0xFF16A152, 0xFFFFFFFF, 0xFFFFFFFF)
      to -> Triple(0xFF111111, 0xFFFFFFFF, 0xFFFFFFFF)
      node.kind == NodeKind.GATE -> Triple(0xFF374151, 0xFFFFFFFF, 0xFFFFFFFF)
      else -> Triple(0xFFFFFFFF, 0xFF9CA3AF, 0xFF6B7280)
    }
    fill.color = fillColor.toInt()
    canvas.drawCircle(x, y, r, fill)
    stroke.color = strokeColor.toInt()
    stroke.strokeWidth = 2 * density
    /* 좌표를 아직 못 믿는 곳은 테를 점선으로 둔다. */
    stroke.pathEffect = if (node.precision == "approx" && !endpoint) approxEffect else null
    canvas.drawCircle(x, y, r, stroke)
    ink.color = textColor.toInt()
    ink.textSize = 10 * density
    val label = if (from) "출발" else if (to) "도착" else node.no?.toString() ?: ""
    canvas.drawText(label, x, y - (ink.descent() + ink.ascent()) / 2, ink)
  }

  /** 점을 정확히 못 맞혀도 된다. 22dp 안의 가장 가까운 곳을 잡고, 없으면 빈 자리를 누른 것으로 친다. */
  override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
    var best: CampusNode? = null
    var bestDistance = 22 * density
    for (node in state.graph.places) {
      val at = project(map, node.at)
      val distance = hypot(at.x - e.x, at.y - e.y)
      if (distance <= bestDistance) {
        best = node
        bestDistance = distance
      }
    }
    if (best != null) {
      state.pickNode(best)
    } else {
      val g = map.projection.fromPixels(e.x.toInt(), e.y.toInt())
      state.tapMap(LatLng(g.latitude, g.longitude))
    }
    return true
  }
}
