package dev.pinkcollab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {
    private val paired = PairedHost(
        host = Host("host-1", "Host", "Windows", "1", "1"),
        url = "https://example.test",
        credential = "credential",
        clientId = "client-1",
    )

    @Test fun onlySynchronizedConnectionIsOnline() {
        assertFalse(HostState(paired, ConnectionState.Connecting).connected)
        assertFalse(HostState(paired, ConnectionState.Synchronizing).connected)
        assertFalse(HostState(paired, ConnectionState.Reconnecting(1, 0)).connected)
        assertTrue(HostState(paired, ConnectionState.Online(0)).connected)
    }

    @Test fun retryBackoffStartsImmediatelyAndCapsAtThirtySeconds() {
        assertEquals(0, retryDelayMillis(1, jitter = 1.0))
        assertEquals(1_000, retryDelayMillis(2, jitter = 1.0))
        assertEquals(2_000, retryDelayMillis(3, jitter = 1.0))
        assertEquals(15_000, retryDelayMillis(6, jitter = 1.0))
        assertEquals(30_000, retryDelayMillis(20, jitter = 1.2))
    }
}
