// AI-generated with Claude Code (Claude Opus 5.5), 2026-10-12, reviewed by Dongkun Moon
package com.swpp.stylemate.ui.wardrobe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.ui.components.SectionTitle

/** Tops and outerwear are measured from the back, so the design is read from a separate front photo. */
fun needsFrontPhoto(measuredCategory: String?): Boolean = measuredCategory == "top" || measuredCategory == "outerwear"

@Composable
fun FrontPhotoCard(preview: ImageBitmap?, loading: Boolean, enabled: Boolean, onCamera: () -> Unit, onGallery: () -> Unit) {
    SectionTitle("앞면 사진", "디자인 분석과 옷장 사진에 쓰여요")
    Text("치수는 뒷면으로 쟀어요. 넥라인과 무늬가 보이도록 옷을 뒤집어 앞면을 한 장 찍어 주세요.",
        Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (preview != null) Image(preview, "앞면 사진", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (loading) CircularProgressIndicator()
            else Text("아직 앞면 사진이 없어요", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onCamera, enabled = enabled, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (preview == null) "앞면 촬영" else "다시 촬영") }
        OutlinedButton(onClick = onGallery, enabled = enabled, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("갤러리에서 선택") }
    }
}
