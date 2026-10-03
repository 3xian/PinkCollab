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
        assertFalse(HostState(paired, ConnectionState.Offline()).connected)
        assertTrue(HostState(paired, ConnectionState.Online(0)).connected)
    }

    @Test fun retryBackoffSlowsPersistentFailuresAndCapsAtFifteenSeconds() {
        assertEquals(1_000, retryDelayMillis(1, jitter = 1.0))
        assertEquals(5_000, retryDelayMillis(2, jitter = 1.0))
        assertEquals(10_000, retryDelayMillis(3, jitter = 1.0))
        assertEquals(15_000, retryDelayMillis(4, jitter = 1.0))
        assertEquals(12_000, retryDelayMillis(20, jitter = 0.8))
        assertEquals(15_000, retryDelayMillis(20, jitter = 1.2))
    }

    @Test fun connectionFailuresDistinguishNetworkAndHostErrors() {
        assertEquals("Cannot resolve host address", connectionFailureReason(java.net.UnknownHostException()))
        assertEquals("Connection timed out", connectionFailureReason(java.net.SocketTimeoutException()))
        assertEquals("Cannot reach host address or port", connectionFailureReason(java.net.ConnectException()))
        assertEquals("Secure connection failed", connectionFailureReason(javax.net.ssl.SSLException("TLS")))
        assertEquals("Host rejected the connection (HTTP 503)",
            connectionFailureReason(java.io.IOException("handshake failed"), 503))
    }
}
