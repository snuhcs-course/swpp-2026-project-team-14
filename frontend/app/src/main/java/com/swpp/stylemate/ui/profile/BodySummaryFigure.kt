package com.swpp.stylemate.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.R
import com.swpp.stylemate.data.BodyProfile
import com.swpp.stylemate.data.MeasurementType
import java.util.Locale

/** The onboarding illustration is a reference pose; only the numbers come from this user's profile. */
@Composable
internal fun BodySummaryFigure(profile: BodyProfile) {
    val measures = profile.measurements.associateBy { it.type }
    val ink = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Image(painterResource(R.drawable.body_capture_guide), "치수 위치를 보여주는 예시 모델", Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                fun mark(type: MeasurementType, from: Offset, to: Offset) {
                    if (measures[type] == null) return
                    val a = Offset(size.width * from.x, size.height * from.y)
                    val b = Offset(size.width * to.x, size.height * to.y)
                    drawLine(ink, a, b, 1.5.dp.toPx())
                    drawCircle(ink, 3.dp.toPx(), a); drawCircle(ink, 3.dp.toPx(), b)
                }
                mark(MeasurementType.SHOULDER_WIDTH, Offset(.18f, .25f), Offset(.43f, .25f))
                mark(MeasurementType.WAIST, Offset(.23f, .44f), Offset(.4f, .44f))
                mark(MeasurementType.INSEAM, Offset(.32f, .57f), Offset(.32f, .94f))
            }
            listOf(MeasurementType.SHOULDER_WIDTH to Triple("어깨", .22f, .18f),
                MeasurementType.WAIST to Triple("허리", .02f, .42f),
                MeasurementType.INSEAM to Triple("다리 안쪽", .43f, .72f)).forEach { (type, place) ->
                measures[type]?.let { value ->
                    Surface(Modifier.offset(x = maxWidth * place.second, y = maxWidth * place.third).widthIn(max = maxWidth * .26f),
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                            Text(place.first, style = MaterialTheme.typography.labelSmall)
                            Text(String.format(Locale.KOREA, "%.1f cm", value.valueCm), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        Text("예시 모델 위에 내 분석 치수를 표시했어요. 이미지의 체형은 내 몸을 재현한 모습이 아니에요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
