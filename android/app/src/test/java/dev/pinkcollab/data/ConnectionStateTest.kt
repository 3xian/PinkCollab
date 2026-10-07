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

    @Test fun connectionFailuresDistinguishNetworkAndHostErrors() {
        assertEquals(ConnectionState.Offline("Cannot resolve host address", ConnectionFailure.HostNotFound),
            connectionFailure(java.net.UnknownHostException()))
        assertEquals(ConnectionState.Offline("Connection timed out", ConnectionFailure.TimedOut),
            connectionFailure(java.net.SocketTimeoutException()))
        assertEquals(ConnectionState.Offline("Cannot reach host address or port", ConnectionFailure.Unreachable),
            connectionFailure(java.net.ConnectException()))
        assertEquals(ConnectionState.Offline("Secure connection failed", ConnectionFailure.SecureConnectionFailed),
            connectionFailure(javax.net.ssl.SSLException("TLS")))
        assertEquals(ConnectionState.Offline("Host rejected the connection (HTTP 503)", ConnectionFailure.HostRejected),
            connectionFailure(java.io.IOException("handshake failed"), 503))
        assertEquals(ConnectionState.Offline("Connection interrupted"), connectionFailure(java.io.IOException()))
    }
}
