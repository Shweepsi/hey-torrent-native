package app.heywatchlist.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountFailureTest {
    @Test
    fun everyFailureHasItsOwnFrenchMessage() {
        val messages = AccountFailure.entries.map { it.messageRes }

        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { assertNotEquals(0, it) }
    }

    @Test
    fun exceptionKeepsItsFailure() {
        assertEquals(AccountFailure.Network, AccountFailureException(AccountFailure.Network).failure)
    }
}
