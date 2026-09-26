package com.stylemate.localdev

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionSmokeTest {
    @Test
    fun androidCanWriteAndReadMysqlThroughDjango() = runBlocking {
        val repository = ProbeRepository()
        val url = "http://10.0.2.2:8001"
        val name = "Android 연결검증 ${System.currentTimeMillis()}"
        assertTrue(repository.health(url).isNotEmpty())
        repository.create(url, name)
        assertTrue(repository.list(url).any { it.name == name })
    }
}
