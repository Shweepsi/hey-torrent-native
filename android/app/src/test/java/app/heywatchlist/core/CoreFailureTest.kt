package app.heywatchlist.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoreFailureTest {
    @Test
    fun everyFailureHasItsOwnFrenchMessage() {
        val messages = CoreFailure.entries.map { it.messageRes }

        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { assertNotEquals(0, it) }
    }

    @Test
    fun exceptionKeepsItsFailure() {
        assertEquals(CoreFailure.Unavailable, CoreFailureException(CoreFailure.Unavailable).failure)
    }
}
