// AI-generated with Claude Code (Dongkun Moon) and Codex (Hyeon U Jeong), 2026-09-24, reviewed by Dongkun Moon and Hyeon U Jeong
package com.swpp.stylemate.ui.profile

import com.swpp.stylemate.ui.components.ScreenScaffold

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.ui.theme.StyleMateTheme

private val CAPTURE_TIPS = listOf(
    "속옷이나 몸에 딱 붙는 옷을 입어주세요. 헐렁한 옷을 입으면 자동으로 알려드려요.",
    "팔을 몸에서 살짝 떼고(A자 자세) 정면을 바라보고 서주세요.",
    "머리부터 발끝까지 전신이 화면에 모두 나오게 찍어주세요.",
    "휴대폰은 허리 높이에 두고, 밝고 단순한 배경에서 찍어주세요.",
    "정면과 측면 사진 두 장이 모두 필요해요. 측면 사진으로 몸의 두께를 재요.",
)

@Composable
fun CaptureGuideScreen(onStart: () -> Unit, onSkip: () -> Unit, returnToInput: Boolean = false) {
    ScreenScaffold(
        title = "체형 촬영 가이드",
        actionLabel = if (returnToInput) null else "건너뛰기",
        onBack = if (returnToInput) onSkip else null,
        onAction = onSkip,
        primaryLabel = if (returnToInput) "사진 입력으로 돌아가기" else "사진 찍으러 가기",
        primaryEnabled = true,
        onPrimary = onStart,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                "정확한 치수를 위해 이렇게 찍어주세요",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.swpp.stylemate.R.drawable.body_capture_guide),
                "촬영 자세 예시: 정면은 팔을 몸에서 살짝 떼고, 측면은 똑바로 서서 전신을 촬영해 주세요.",
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                Text("정면", style = MaterialTheme.typography.labelLarge)
                Text("측면", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(20.dp))
            CAPTURE_TIPS.forEach { tip ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(tip, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            PrivacyNote()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun PrivacyNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            "사진은 치수 분석에만 사용되고 분석이 끝나면 바로 삭제돼요. 저장되는 건 치수 숫자뿐이에요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CaptureGuidePreview() {
    StyleMateTheme { CaptureGuideScreen(onStart = {}, onSkip = {}) }
}
