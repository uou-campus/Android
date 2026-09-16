package site.uoucampus.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Client/src/hooks/useGeolocation.ts. 버튼을 눌러야 켜진다 — 화면 열자마자 권한을 묻지 않는다. */
class Locator(private val context: Context) : LocationListener {
  enum class Status {
    IDLE, LOCATING,
    /** 좌표는 왔지만 아직 어림하다. 점은 찍되 출발지로 쓰진 않는다. */
    COARSE,
    READY, DENIED, UNSUPPORTED, FAILED,
  }

  var here by mutableStateOf<LatLng?>(null)
    private set
  /** 오차 반경(m). 이탈 판정을 여기에 맞춘다. */
  var accuracy by mutableStateOf<Double?>(null)
    private set
  var status by mutableStateOf(Status.IDLE)
    private set
  val active get() = status != Status.IDLE

  /** 쓸 만한 첫 좌표가 잡혔을 때 한 번. */
  var onFirstFix: ((LatLng) -> Unit)? = null
  /** 권한을 묻는 손잡이. Activity 가 채운다. */
  var askPermission: (() -> Unit)? = null

  private val manager = context.getSystemService(LocationManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private var pending = false
  private var gotFix = false
  private var lastFixAt = 0L

  /* 건물 안에서는 첫 좌표가 10초를 넘긴다. 30초 넘게 못 받으면 알리되, 기다림은 계속한다. */
  private val timeout = Runnable { if (!gotFix && status == Status.LOCATING) status = Status.FAILED }

  fun start() {
    status = Status.LOCATING
    pending = true
    gotFix = false
    if (granted()) begin() else askPermission?.invoke()
  }

  fun permissionResult(allowed: Boolean) {
    if (status == Status.IDLE) return
    if (allowed) begin() else {
      pending = false
      status = Status.DENIED
    }
  }

  fun stop() {
    manager?.removeUpdates(this)
    handler.removeCallbacks(timeout)
    pending = false
    gotFix = false
    status = Status.IDLE
    here = null
    accuracy = null
  }

  private fun granted() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

  @SuppressLint("MissingPermission")
  private fun begin() {
    manager?.removeUpdates(this)
    var listening = false
    for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
      try {
        manager?.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper())
        listening = true
      } catch (_: Exception) {
        /* 이 기기에 없는 공급자다. 다른 쪽으로 받는다. */
      }
    }
    if (!listening) {
      pending = false
      status = Status.UNSUPPORTED
      return
    }
    handler.removeCallbacks(timeout)
    handler.postDelayed(timeout, 30_000)
  }

  override fun onLocationChanged(location: Location) {
    if (status == Status.IDLE) return
    val meters = if (location.hasAccuracy()) location.accuracy.toDouble() else 999.0
    /* 위성과 기지국 좌표가 번갈아 온다. 방금 좋은 좌표를 받았으면 더 어림한 것으로 덮지 않는다. */
    val now = SystemClock.elapsedRealtime()
    if (gotFix && meters > (accuracy ?: 0.0) && now - lastFixAt < 10_000) return
    lastFixAt = now

    val at = LatLng(location.latitude, location.longitude)
    val usable = meters <= 50
    gotFix = true
    here = at
    accuracy = meters
    status = if (usable) Status.READY else Status.COARSE
    if (pending && usable) {
      pending = false
      onFirstFix?.invoke(at)
    }
  }

  /* API 29 이하는 기본 구현이 없다. 비워 두지 않으면 호출될 때 죽는다. */
  override fun onProviderEnabled(provider: String) = Unit
  override fun onProviderDisabled(provider: String) = Unit
  @Deprecated("API 29 이하 호환")
  override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}
