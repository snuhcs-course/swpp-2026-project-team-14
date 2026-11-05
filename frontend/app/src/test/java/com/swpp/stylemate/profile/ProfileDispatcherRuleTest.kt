package com.swpp.stylemate.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileDispatcherRuleTest {
    @Test fun unconfinedMainWaitsForCancelledIoBeforeResetting() =
        waitsForCancelledIo(UnconfinedTestDispatcher())

    @Test fun standardMainWaitsForCancelledIoBeforeResetting() =
        waitsForCancelledIo(StandardTestDispatcher())

    private fun waitsForCancelledIo(dispatcher: TestDispatcher) {
        val rule = ProfileDispatcherRule(dispatcher)
        val started = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var scope: Job
        lateinit var worker: Job
        val statement = rule.apply(object : Statement() {
            override fun evaluate() {
                val model = rule.own(object : ViewModel() {
                    override fun onCleared() { cleared.countDown() }
                })
                scope = model.viewModelScope.coroutineContext[Job]!!
                worker = model.viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "IO cleanup was not released" }
                    }
                }
                dispatcher.scheduler.runCurrent()
                assertTrue(started.await(5, TimeUnit.SECONDS))
            }
        }, Description.createTestDescription(javaClass, "pending IO cleanup"))
        val executor = Executors.newSingleThreadExecutor()
        val execution = executor.submit { statement.evaluate() }
        try {
            assertTrue(cleared.await(5, TimeUnit.SECONDS))
            assertTrue(scope.isCancelled)
            assertThrows(TimeoutException::class.java) { execution.get(100, TimeUnit.MILLISECONDS) }
        } finally {
            release.countDown()
            try { execution.get(5, TimeUnit.SECONDS) }
            finally { executor.shutdownNow() }
        }
        assertTrue(scope.isCompleted)
        assertTrue(worker.isCompleted)
    }
}
