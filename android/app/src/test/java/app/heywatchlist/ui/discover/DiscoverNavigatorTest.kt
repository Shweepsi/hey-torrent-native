package app.heywatchlist.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heywatchlist.core.Genre
import app.heywatchlist.core.titleOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverNavigatorTest {
    private class Probe : ViewModel() {
        var cleared = false

        public override fun onCleared() {
            cleared = true
        }
    }

    private val probeFactory: ViewModelProvider.Factory = viewModelFactory { initializer { Probe() } }

    private fun probeOf(entry: DiscoverEntry): Probe =
        ViewModelProvider(entry.viewModelStore, probeFactory)[Probe::class.java]

    @Test
    fun theStackStartsEmptyAndGrowsInOrder() {
        val navigator = DiscoverNavigator()
        assertTrue(navigator.stack.value.isEmpty())

        navigator.open(DiscoverDestination.Search)
        navigator.open(DiscoverDestination.Detail(titleOf(1)))

        assertEquals(
            listOf(DiscoverDestination.Search, DiscoverDestination.Detail(titleOf(1))),
            navigator.stack.value.map { it.destination },
        )
    }

    @Test
    fun openingTheDestinationAlreadyOnTopIsIgnored() {
        val navigator = DiscoverNavigator()
        val genre = DiscoverDestination.GenreTitles(Genre(28, "Action"))

        navigator.open(genre)
        val first = navigator.stack.value.single()
        // Un double toucher sur la même pastille ou la même affiche.
        navigator.open(genre)
        navigator.open(DiscoverDestination.GenreTitles(Genre(28, "Action")))

        assertEquals(listOf(first), navigator.stack.value)
    }

    @Test
    fun theSameDestinationCanBeOpenedAgainFromAnotherScreen() {
        val navigator = DiscoverNavigator()
        val detail = DiscoverDestination.Detail(titleOf(1))

        navigator.open(detail)
        navigator.open(DiscoverDestination.Detail(titleOf(2)))
        navigator.open(detail)

        val ids = navigator.stack.value.map { it.id }
        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun aDestinationClosedThenOpenedAgainGetsANewId() {
        val navigator = DiscoverNavigator()
        navigator.open(DiscoverDestination.Search)
        val closed = navigator.stack.value.single().id
        navigator.back()

        navigator.open(DiscoverDestination.Search)

        assertNotEquals(closed, navigator.stack.value.single().id)
    }

    @Test
    fun idsDoNotRepeatWhenTheProcessIsRecreated() {
        // Après la mort du processus, un nouveau navigateur repart d'une pile vide, alors
        // que l'état sauvegardé des anciens écrans, rangé sous leur numéro, est restauré.
        val before = DiscoverNavigator().also { it.open(DiscoverDestination.Search) }.stack.value.single().id
        val after = DiscoverNavigator().also { it.open(DiscoverDestination.Search) }.stack.value.single().id

        assertNotEquals(before, after)
    }

    @Test
    fun backClosesTheTopAndClearsItsViewModels() {
        val navigator = DiscoverNavigator()
        navigator.open(DiscoverDestination.Search)
        navigator.open(DiscoverDestination.Detail(titleOf(1)))
        val (below, top) = navigator.stack.value
        val belowProbe = probeOf(below)
        val topProbe = probeOf(top)

        navigator.back()

        assertEquals(listOf(below), navigator.stack.value)
        assertTrue(topProbe.cleared)
        assertFalse(belowProbe.cleared)
    }

    @Test
    fun backOnAnEmptyStackDoesNothing() {
        val navigator = DiscoverNavigator()

        navigator.back()

        assertTrue(navigator.stack.value.isEmpty())
    }

    @Test
    fun clearingTheNavigatorClearsEveryEntry() {
        val store = ViewModelStore()
        val navigator = ViewModelProvider(store, DiscoverNavigator.Factory)[DiscoverNavigator::class.java]
        navigator.open(DiscoverDestination.Search)
        navigator.open(DiscoverDestination.Detail(titleOf(2)))
        val probes = navigator.stack.value.map { probeOf(it) }

        store.clear()

        assertTrue(probes.all { it.cleared })
    }
}
