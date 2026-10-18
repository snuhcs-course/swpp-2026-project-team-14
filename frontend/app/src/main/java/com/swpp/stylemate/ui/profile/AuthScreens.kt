// AI-generated with Codex, 2026-10-16
package com.swpp.stylemate.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.swpp.stylemate.ui.components.ScreenScaffold

/** Authentication is the app's entry point; there is no guest dismiss action. */
@Composable
fun LoginScreen(state: AuthUiState, onRegister: () -> Unit, onSubmit: (String, String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    ScreenScaffold("StyleMate", if (state.busy) "로그인 중…" else "로그인", !state.busy && state.retrySeconds == 0,
        { onSubmit(username, password) }, modifier = Modifier.imePadding()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("나에게 맞는 스타일의 시작", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("로그인하고 내 옷장과 맞춤 코디를 만나보세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            UsernameField(username, { username = it }, !state.busy)
            PasswordField("비밀번호", password, { password = it }, !state.busy)
            AuthError(state)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("계정이 없으신가요?", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRegister, enabled = !state.busy) { Text("회원가입", fontWeight = FontWeight.SemiBold) }
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
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("StyleMate") }
                append(" 시작하기")
            }, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("아이디와 비밀번호만 입력하면 돼요.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    UsernameField(username, { username = it }, !state.busy)
                    Text("영문 소문자·숫자·밑줄 4~20자", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PasswordField("비밀번호", password, { password = it }, !state.busy)
                    Text("비밀번호 8~64자", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next))
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValueChange = { if (it.length <= 256) onChange(it) }, label = { Text(label) }, enabled = enabled,
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { visible = !visible }, enabled = enabled) { Text(if (visible) "숨기기" else "표시") } })
}

@Composable
private fun AuthError(state: AuthUiState) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    if (state.retrySeconds > 0) Text("${state.retrySeconds}초 후 다시 시도할 수 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
