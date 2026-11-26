package app.heywatchlist.ui.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test
    fun ratingHasOneDecimalAndAFrenchComma() {
        assertEquals("7,8", formatRating(7.84))
        assertEquals("8,0", formatRating(8.0))
        assertEquals("0,0", formatRating(0.0))
    }

    @Test
    fun countGroupsThousands() {
        val text = formatCount(12_345)

        // Le séparateur français est une espace insécable (fine ou non, selon le JDK).
        assertEquals("12345", text.filter { it.isDigit() })
        assertEquals(6, text.length)
        assertEquals("999", formatCount(999))
    }

    @Test
    fun dateReadsInFrench() {
        assertEquals("12 mars 2024", formatDate("2024-03-12"))
        assertEquals("1 janvier 2000", formatDate("2000-01-01"))
    }

    @Test
    fun unreadableDatesGiveNothing() {
        assertNull(formatDate(null))
        assertNull(formatDate(""))
        assertNull(formatDate("bientôt"))
        assertNull(formatDate("2024-13-45"))
    }

    @Test
    fun yearComesFromTheFirstFourCharacters() {
        assertEquals(2024, yearOf("2024-03-12"))
        assertEquals(1999, yearOf("1999"))
        assertNull(yearOf(null))
        assertNull(yearOf("24"))
        assertNull(yearOf("abcd-01-01"))
    }

    @Test
    fun youtubeLinkEncodesTheKey() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", youtubeUrl("dQw4w9WgXcQ"))
        assertEquals("https://www.youtube.com/watch?v=a%26b%3Dc", youtubeUrl("a&b=c"))
    }
}
