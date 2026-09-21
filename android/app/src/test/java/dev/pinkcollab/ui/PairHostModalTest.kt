package dev.pinkcollab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairHostModalTest {
    @Test
    fun pairingCodePopulatesVisibleSheet() {
        assertEquals(
            PairHostSheetState(
                visible = true,
                url = "https://gateway.example.com",
                token = "one-time-token",
            ),
            decodePairingCode(
                """{"version":1,"url":"https://gateway.example.com","token":"one-time-token"}""",
            ),
        )
    }

    @Test
    fun unsupportedPairingCodeVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            decodePairingCode(
                """{"version":2,"url":"https://gateway.example.com","token":"one-time-token"}""",
            )
        }
    }
}
