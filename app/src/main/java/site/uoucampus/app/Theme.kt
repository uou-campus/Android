package site.uoucampus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Client/src/styles/{theme,font}.ts

object UColor {
  val gray900 = Color(0xFF111111)
  val gray700 = Color(0xFF374151)
  val gray500 = Color(0xFF6B7280)
  val gray400 = Color(0xFF9CA3AF)
  val gray300 = Color(0xFFD1D5DB)
  val gray200 = Color(0xFFE5E7EB)
  val gray100 = Color(0xFFF3F4F6)
  val gray50 = Color(0xFFF8F9FB)
  val surface = Color.White

  /** 울산대 CI 그린. */
  val accent = Color(0xFF16A152)
  val accentSoft = Color(0xFFE8F6EE)
  val accentTint = Color(0xFFD0ECDC)
  val warn = Color(0xFFB45309)
  val warnSoft = Color(0xFFFFFBEB)
  val ok = Color(0xFF15803D)
  val error = Color(0xFFB91C1C)
  val here = Color(0xFF2563EB)
  val hereSoft = Color(0xFFEFF6FF)

  val outline = gray200
  val textPrimary = gray900
  val textSecondary = gray500
  val textTertiary = gray400
}

object Type {
  val appTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)
  val section = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
  val body = TextStyle(fontSize = 14.sp)
  val bodyStrong = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
  val action = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
  val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
  val metric = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
  val metricSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
}

/** 둥근 알약 꼴 단추. */
@Composable
fun Pill(
  text: String,
  ink: Color,
  modifier: Modifier = Modifier,
  fill: Color = Color.Transparent,
  stroke: Color? = UColor.outline,
  style: TextStyle = Type.caption,
  h: Dp = 10.dp,
  v: Dp = 5.dp,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Text(
    text,
    color = ink,
    style = style,
    maxLines = 1,
    modifier = modifier
      .alpha(if (enabled) 1f else 0.45f)
      .then(if (stroke != null) Modifier.border(1.dp, stroke, CircleShape) else Modifier)
      .clip(CircleShape)
      .background(fill)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = h, vertical = v),
  )
}

/** 옅은 바탕을 깐 알림 칸. */
fun Modifier.note(fill: Color) =
  fillMaxWidth().background(fill, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp)

fun Modifier.bottomRule() = drawBehind {
  val y = size.height - 0.5.dp.toPx()
  drawLine(UColor.outline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
}

/** 지도 위에 얹힌 판. 판을 누르고 끄는 손짓이 밑의 지도로 새지 않게 한다. */
fun Modifier.blockTouches() = pointerInput(Unit) {
  awaitPointerEventScope {
    while (true) awaitPointerEvent().changes.forEach { it.consume() }
  }
}

/** 굵게 쓸 토막. */
class Bold(val text: String)

fun rich(vararg parts: Any) = buildAnnotatedString {
  for (part in parts) {
    if (part is Bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part.text) } else append(part.toString())
  }
}
