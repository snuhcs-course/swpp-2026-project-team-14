package com.stylemate.localdev

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@androidx.compose.runtime.Composable
fun ProbeScreen() {
    val model: ProbeViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    var baseUrl by rememberSaveable { mutableStateOf("http://10.0.2.2:8001") }
    var name by rememberSaveable { mutableStateOf("테스트 셔츠") }
    Scaffold { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("StyleMate 로컬 테스트", style = MaterialTheme.typography.headlineSmall)
                Text("FE → Django → MySQL 연결 확인용 화면입니다.")
            }
            item {
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("로컬 API 주소") },
                    singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            }
            item {
                Button(onClick = { model.refresh(baseUrl) }, enabled = !state.busy) {
                    Text("연결 확인 · 목록 새로고침")
                }
            }
            item { Text(state.message) }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                OutlinedTextField(name, { name = it }, label = { Text("테스트 데이터 이름") },
                    singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            }
            item {
                Button(onClick = { model.save(baseUrl, name) },
                    enabled = !state.busy && name.trim().length in 1..50) { Text("DB에 저장") }
            }
            item { Text("저장된 테스트 데이터", style = MaterialTheme.typography.titleMedium) }
            if (state.items.isEmpty()) item { Text("조회된 데이터가 없습니다.") }
            items(state.items, key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(row.name)
                        Text("#${row.id}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
