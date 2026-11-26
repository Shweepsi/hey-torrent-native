package app.heywatchlist.ui.discover

import java.net.URLEncoder
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

// L'interface est en français, quelle que soit la langue de l'appareil : dates et
// nombres se formatent donc en français.
private val French: Locale = Locale.FRENCH

/** Note sur 10 avec une décimale : « 7,8 ». */
internal fun formatRating(rating: Double): String = String.format(French, "%.1f", rating)

/** Nombre avec séparateur de milliers : « 12 345 ». */
internal fun formatCount(count: Int): String = NumberFormat.getIntegerInstance(French).format(count)

/** « 12 mars 2024 » à partir de `AAAA-MM-JJ` ; `null` si la date est absente ou illisible. */
internal fun formatDate(isoDate: String?): String? {
    if (isoDate.isNullOrBlank()) return null
    return try {
        LocalDate.parse(isoDate).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(French))
    } catch (error: DateTimeParseException) {
        null
    }
}

/** Année de `AAAA-MM-JJ` ; `null` si la date est absente ou illisible. */
internal fun yearOf(isoDate: String?): Int? =
    isoDate?.takeIf { it.length >= YEAR_LENGTH }?.take(YEAR_LENGTH)?.toIntOrNull()

private const val YEAR_LENGTH = 4

/** Adresse de la vidéo YouTube dont TMDB donne la clé. */
internal fun youtubeUrl(key: String): String =
    "https://www.youtube.com/watch?v=" + URLEncoder.encode(key, Charsets.UTF_8.name())
