package app.heywatchlist.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogFailureTest {
    @Test
    fun everyFailureHasItsOwnFrenchMessage() {
        val messages = CatalogFailure.entries.map { it.messageRes }

        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { assertNotEquals(0, it) }
    }

    @Test
    fun exceptionKeepsItsFailure() {
        assertEquals(CatalogFailure.Server, CatalogFailureException(CatalogFailure.Server).failure)
    }

    @Test
    fun attemptReturnsTheValue() = runTest {
        assertEquals(CatalogResult.Success(42), attempt { 42 })
    }

    @Test
    fun attemptKeepsTheFailureOfTheGateway() = runTest {
        val result = attempt { failing(CatalogFailure.NotFound) }

        assertEquals(CatalogResult.Failure(CatalogFailure.NotFound), result)
    }

    @Test
    fun attemptTurnsAnUnexpectedErrorIntoUnknown() = runTest {
        val result = attempt { error("défaut imprévu") }

        assertEquals(CatalogResult.Failure(CatalogFailure.Unknown), result)
    }

    @Test
    fun attemptLetsCancellationThrough() {
        assertThrows(CancellationException::class.java) {
            runBlocking { attempt { throw CancellationException("annulé") } }
        }
    }

    @Test
    fun titleKeysDistinguishFilmsFromSeriesWithTheSameNumber() {
        assertNotEquals(titleOf(7, MediaKind.Movie).key, titleOf(7, MediaKind.Tv).key)
    }

    @Test
    fun trailerIsPreferredToTeaser() {
        val detail = detailOf(titleOf(1)).copy(
            videos = listOf(Video("teaser", VideoKind.Teaser), Video("bande", VideoKind.Trailer)),
        )

        assertEquals("bande", detail.trailer?.youtubeKey)
        assertEquals("teaser", detail.copy(videos = detail.videos.take(1)).trailer?.youtubeKey)
        assertEquals(null, detail.copy(videos = listOf(Video("extrait", VideoKind.Clip))).trailer)
    }
}
