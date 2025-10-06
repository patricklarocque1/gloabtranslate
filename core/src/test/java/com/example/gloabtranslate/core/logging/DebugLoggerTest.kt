package com.example.gloabtranslate.core.logging

import com.example.gloabtranslate.core.data.config.DebugConfig
import com.example.gloabtranslate.core.data.config.DebugConfigProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * Verifies that DebugLogger respects enableDebugMode and logLevel gating.
 * We don't assert Android Log output (framework), but rather internal shouldLog behavior
 * by exposing a tiny spy subclass for test.
 */
class DebugLoggerTest {

    private class FakeProvider : DebugConfigProvider {
        private val _flow = MutableStateFlow(DebugConfig(false,false,"info",false,false))
        override val debugConfig = _flow.asStateFlow()
        fun emit(cfg: DebugConfig) { _flow.value = cfg }
    }


    @Test
    fun `debug disabled suppresses all levels`() = runTest {
        val provider = FakeProvider()
    val logger = DebugLogger(provider)
    logger.setTestConfig(DebugConfig(false,true,"debug",false,false))
    assertFalse(logger.shouldLogForTest(DebugLogger.Level.ERROR))
    assertFalse(logger.shouldLogForTest(DebugLogger.Level.DEBUG))
    }

    @Test
    fun `log level filters lower priorities`() = runTest {
        val provider = FakeProvider()
    val logger = DebugLogger(provider)
    logger.setTestConfig(DebugConfig(true,false,"warn",false,false))
    assertFalse(logger.shouldLogForTest(DebugLogger.Level.INFO))
    assertTrue(logger.shouldLogForTest(DebugLogger.Level.WARN))
    assertTrue(logger.shouldLogForTest(DebugLogger.Level.ERROR))
    }

    @Test
    fun `verbose flag overrides logLevel`() = runTest {
        val provider = FakeProvider()
    val logger = DebugLogger(provider)
    logger.setTestConfig(DebugConfig(true,true,"info",false,false))
    assertTrue(logger.shouldLogForTest(DebugLogger.Level.VERBOSE))
    }
}
