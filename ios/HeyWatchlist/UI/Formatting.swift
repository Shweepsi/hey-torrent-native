import Foundation

/// Mise en forme des nombres, durées et dates en français, quelle que soit la
/// région de l'appareil (l'app n'existe qu'en français).
enum Formatting {
    static let locale = Locale(identifier: "fr_FR")

    /// Note sur 10 : « 7,4 ».
    static func rating(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(1)).locale(locale))
    }

    /// Durée : « 45 min », « 1 h 52 ».
    static func runtime(minutes: Int) -> String {
        guard minutes >= 60 else {
            return String(localized: "detail.runtime.minutes \(minutes)")
        }
        let rest = String(format: "%02d", minutes % 60)
        return String(localized: "detail.runtime.hoursMinutes \(minutes / 60) \(rest)")
    }

    /// Date longue : « 12 mars 2025 », à partir de `AAAA-MM-JJ`. `nil` si le
    /// texte n'est pas une date lisible.
    static func date(_ iso: String) -> String? {
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        // Le jour du calendrier ne dépend pas du fuseau : tout se fait en UTC.
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        let components = DateComponents(year: parts[0], month: parts[1], day: parts[2])
        guard let date = calendar.date(from: components) else { return nil }
        return date.formatted(Date.FormatStyle(
            date: .long, time: .omitted, locale: locale, calendar: calendar, timeZone: .gmt
        ))
    }
}
