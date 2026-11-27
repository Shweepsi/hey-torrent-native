package app.heywatchlist.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.heywatchlist_core.CatalogException
import uniffi.heywatchlist_core.MediaType
import uniffi.heywatchlist_core.TitlePage as CoreTitlePage
import uniffi.heywatchlist_core.TitleSummary as CoreTitleSummary

/** Traduction des types UniFFI : nombres non signés, genres, erreurs. */
class CatalogMappingTest {
    private val summary = CoreTitleSummary(
        id = 4_000_000_000uL,
        mediaType = MediaType.TV,
        title = "Une série",
        year = 2021u,
        releaseDate = "2021-05-04",
        overview = "Résumé",
        posterUrl = "https://exemple.test/affiche.jpg",
        backdropUrl = null,
        rating = 8.25,
        voteCount = 1234u,
        genreIds = listOf(18u, 35u),
    )

    @Test
    fun titleKeepsEveryField() {
        val title = summary.toTitle()

        assertEquals(4_000_000_000L, title.id)
        assertEquals(MediaKind.Tv, title.kind)
        assertEquals(2021, title.year)
        assertEquals("2021-05-04", title.releaseDate)
        assertEquals("https://exemple.test/affiche.jpg", title.posterUrl)
        assertNull(title.backdropUrl)
        assertEquals(8.25, title.rating, 0.0)
        assertEquals(1234, title.voteCount)
        assertEquals(listOf(18, 35), title.genreIds)
    }

    @Test
    fun pageKeepsItsPositionAndStaleness() {
        val page = CoreTitlePage(items = listOf(summary), page = 2u, totalPages = 5u, stale = true).toPage()

        assertEquals(2, page.page)
        assertEquals(5, page.totalPages)
        assertEquals(true, page.stale)
        assertEquals(1, page.items.size)
    }

    @Test
    fun everyCatalogErrorHasAFailure() {
        assertEquals(CatalogFailure.Network, CatalogException.Network().toFailure())
        assertEquals(CatalogFailure.SignedOut, CatalogException.SignedOut().toFailure())
        assertEquals(CatalogFailure.NotFound, CatalogException.NotFound().toFailure())
        assertEquals(CatalogFailure.Unavailable, CatalogException.Unavailable().toFailure())
        assertEquals(CatalogFailure.Server, CatalogException.Server().toFailure())
        assertEquals(CatalogFailure.Unknown, CatalogException.Unexpected().toFailure())
        assertEquals(CatalogFailure.Unknown, CatalogException.Internal().toFailure())
    }

    @Test
    fun accountFailuresAreTranslated() {
        assertEquals(CatalogFailure.SignedOut, AccountFailure.SignedOut.toCatalogFailure())
        assertEquals(CatalogFailure.Unknown, AccountFailure.InvalidIdentity.toCatalogFailure())
        assertEquals(CatalogFailure.NotConfigured, AccountFailure.NotConfigured.toCatalogFailure())
        assertEquals(CatalogFailure.CoreUnavailable, AccountFailure.Unavailable.toCatalogFailure())
    }
}
