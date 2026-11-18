package com.swpp.stylemate.ui.profile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.swpp.stylemate.data.LocalUserStore
import com.swpp.stylemate.data.calendar.CalendarConnection
import com.swpp.stylemate.data.calendar.DeviceCalendar

/** 연동: the phone calendar for now. It reads the calendars already on the phone; there is no account sign-in. */
@Composable
fun CalendarConnectionCard(store: LocalUserStore) {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(CalendarConnection.enabled(context, store)) }
    var denied by remember { mutableStateOf(false) }
    // Coming back from the system settings, or after the permission was revoked there.
    LifecycleResumeEffect(Unit) {
        connected = CalendarConnection.enabled(context, store)
        if (connected) denied = false
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) CalendarConnection.set(store, true)
        connected = granted
    }
    fun change(on: Boolean) {
        if (!on) {
            CalendarConnection.set(store, false)
            connected = false
        } else if (DeviceCalendar.hasPermission(context)) {
            CalendarConnection.set(store, true)
            connected = true
        } else permission.launch(Manifest.permission.READ_CALENDAR)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(value = connected, role = Role.Switch, onValueChange = ::change),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {}
                    Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("휴대폰 캘린더", fontWeight = FontWeight.Bold)
                    Text(if (connected) "연결됨 · 출근·데이트·여행 같은 오늘 일정을 홈에 보여줘요"
                         else "구글·삼성 캘린더 등 휴대폰에 있는 일정을 읽어요",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = connected, onCheckedChange = null)
            }
            Text("일정 내용은 이 휴대폰 밖으로 보내지 않아요.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (denied && !connected) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("캘린더 권한이 꺼져 있어요.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                TextButton(contentPadding = PaddingValues(horizontal = 8.dp), onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("설정에서 허용") }
            }
        }
    }
}
