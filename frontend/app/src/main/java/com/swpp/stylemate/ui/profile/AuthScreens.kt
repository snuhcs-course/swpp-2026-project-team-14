// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.swpp.stylemate.ui.components.ScreenScaffold

@Composable
fun LoginDialog(state: AuthUiState, onDismiss: () -> Unit, onRegister: () -> Unit, onSubmit: (String, String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { if (!state.busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy, usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(24.dp).widthIn(max = 440.dp).fillMaxWidth().imePadding(),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    Text("로그인", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
                }
                Text("StyleMate", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                UsernameField(username, { username = it }, !state.busy)
                PasswordField("비밀번호", password, { password = it }, !state.busy)
                AuthError(state)
                Button(onClick = { onSubmit(username, password) }, enabled = !state.busy && state.retrySeconds == 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(if (state.busy) "로그인 중…" else "로그인", fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("계정이 없으신가요?", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRegister, enabled = !state.busy) { Text("회원가입") }
                }
            }
        }
    }
}

@Composable
fun RegistrationScreen(state: AuthUiState, onBack: () -> Unit, onSubmit: (String, String, String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    BackHandler { if (!state.busy) onBack() }
    ScreenScaffold("회원가입", if (state.busy) "가입 중…" else "회원가입", !state.busy && state.retrySeconds == 0,
        { onSubmit(username, password, confirmation) }, onBack = onBack, navigationEnabled = !state.busy,
        modifier = Modifier.imePadding()) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("StyleMate 시작하기", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("아이디와 비밀번호만 입력하면 돼요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    UsernameField(username, { username = it }, !state.busy)
                    Text("영문 소문자·숫자·밑줄 4~20자", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PasswordField("비밀번호", password, { password = it }, !state.busy)
                    Text("비밀번호 8~64자", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PasswordField("비밀번호 확인", confirmation, { confirmation = it }, !state.busy)
                    Text("비밀번호를 다시 입력해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AuthError(state)
        }
    }
}

@Composable
private fun UsernameField(value: String, onChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(value, onValueChange = { if (it.length <= 64) onChange(it) }, label = { Text("아이디") },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next))
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValueChange = { if (it.length <= 256) onChange(it) }, label = { Text(label) }, enabled = enabled,
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { visible = !visible }, enabled = enabled) { Text(if (visible) "숨기기" else "표시") } })
}

@Composable
private fun AuthError(state: AuthUiState) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    if (state.retrySeconds > 0) Text("${state.retrySeconds}초 후 다시 시도할 수 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
