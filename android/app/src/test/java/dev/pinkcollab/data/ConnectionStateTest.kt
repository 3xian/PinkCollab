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
        assertFalse(HostState(paired, ConnectionState.Reconnecting).connected)
        assertTrue(HostState(paired, ConnectionState.Online(0)).connected)
    }

    @Test fun retryBackoffSlowsPersistentFailuresAndCapsAtFiveMinutes() {
        assertEquals(0, retryDelayMillis(1, jitter = 1.0))
        assertEquals(1_000, retryDelayMillis(2, jitter = 1.0))
        assertEquals(2_000, retryDelayMillis(3, jitter = 1.0))
        assertEquals(15_000, retryDelayMillis(6, jitter = 1.0))
        assertEquals(60_000, retryDelayMillis(7, jitter = 1.0))
        assertEquals(120_000, retryDelayMillis(8, jitter = 1.0))
        assertEquals(240_000, retryDelayMillis(20, jitter = 0.8))
        assertEquals(300_000, retryDelayMillis(20, jitter = 1.2))
    }

    @Test fun persistentFailuresAreOfflineWhileBriefInterruptionsKeepReconnecting() {
        assertEquals(ConnectionState.Reconnecting, retryConnectionState(1, "Connection interrupted"))
        assertEquals(ConnectionState.Reconnecting, retryConnectionState(6, "HTTP 502"))
        assertEquals(ConnectionState.Offline("HTTP 502"), retryConnectionState(7, "HTTP 502"))
        assertEquals(ConnectionState.Offline("HTTP 502"), retryConnectionState(20, "HTTP 502"))
        assertEquals(ConnectionState.Reconnecting, retryConnectionState(1, "Host closed the connection"))
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
