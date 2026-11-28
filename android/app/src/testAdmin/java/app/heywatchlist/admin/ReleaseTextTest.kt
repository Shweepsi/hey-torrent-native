package app.heywatchlist.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseTextTest {
    @Test
    fun `le nom brut se coupe après les points, tirets et soulignements`() {
        assertEquals("Titre.​2008.​x265-​GROUPE_​v2", softBreaks("Titre.2008.x265-GROUPE_v2"))
    }

    @Test
    fun `un nom sans séparateur ne change pas`() {
        assertEquals("Titre 2008", softBreaks("Titre 2008"))
    }
}
