import Foundation

/// Cross-platform "resolve a trailer to a playable URL" type, compiled into every target
/// (SourcesShared is in all of them). A meta's trailer is either a direct (non-YouTube) stream
/// URL or a YouTube id; this collapses both into one `playableURL` the players can hand to libmpv.
///
/// YouTube trailers are played through the embedded server's `/yt/:id` route (server.js: a 301
/// redirect to a direct media URL resolved by ytdl-core), so they need `StremioServer.canProxy`.
/// On the Lite build (no embedded server) a YouTube-only trailer has no `playableURL`, which is
/// what lets the tvOS Trailer button auto-hide there. `watchURL` is the public youtube.com link
/// for surfaces that can open a browser/external player instead (e.g. iOS/macOS).
struct TrailerRequest: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let youTubeID: String?
    /// A non-YouTube `trailerStreams` url, if the meta carried a direct stream.
    let directURL: URL?
    /// Release year (4 digits) + media type ("movie"/"series"): the key the `/clip` resolver matches a
    /// trailer on (Apple iTunes preview, by title+year), since iTunes has no id we can pass. Defaulted so
    /// callers that lack a year/type (e.g. a home board-row hero) still build a title-only /clip request.
    var year: String? = nil
    var mediaType: String = "movie"

    /// The libmpv-playable URL: a direct (non-YouTube) trailer stream when the meta carried one, else the
    /// public `trailer.vortx.tv/yt/{id}` resolver URL for a YouTube id. The resolver 302-redirects to a
    /// playable MP4 (libmpv follows the redirect); it is FAIL-SOFT: a 404 / timeout / undeployed resolver
    /// surfaces to the player as `endFileError`, and every consumer view falls back to the still backdrop on
    /// that. A direct stream is always preferred over the resolver. This URL is only consumed on tvOS
    /// (`TVInHeroTrailerView` via the detail `heroTrailerLayer` + `HomeHeroTrailerModel`), which has no web
    /// view; iOS/Mac ignore this and play YouTube ids through the WKWebView IFrame (`YouTubeEmbedView`) using
    /// `youTubeID` directly, reading `directURL` (not this) for their own libmpv path.
    var playableURL: URL? {
        if let directURL { return directURL }
        var c = URLComponents(string: "https://trailer.vortx.tv/clip")
        c?.queryItems = [
            URLQueryItem(name: "title", value: title),
            URLQueryItem(name: "year", value: year),
            URLQueryItem(name: "type", value: mediaType),
        ].filter { ($0.value?.isEmpty == false) }
        return c?.url
    }

    /// The public YouTube watch link, for surfaces that open trailers externally.
    var watchURL: URL? {
        youTubeID.flatMap { URL(string: "https://www.youtube.com/watch?v=\($0)") }
    }

    /// Build from a resolved meta: prefer a direct (non-YouTube) trailer stream url, else fall
    /// back to the YouTube id (`trailerStreams` ytId, or a "Trailer" link). Nil when neither exists.
    static func from(meta: CoreMetaItem) -> TrailerRequest? {
        let direct = (meta.trailerStreams ?? [])
            .compactMap { $0.ytId == nil ? $0.url : nil }
            .compactMap { URL(string: $0) }
            .first
        let yt = meta.trailerYouTubeID
        guard direct != nil || yt != nil else { return nil }
        // 4-digit year from releaseInfo ("2024", "2024-2025", "2024-") so /clip can disambiguate the right
        // film/series; nil if not parseable. type is movie/series.
        let yr = (meta.releaseInfo?.prefix(4)).map(String.init)
        let year = (yr?.count == 4 && yr?.allSatisfy(\.isNumber) == true) ? yr : nil
        return TrailerRequest(title: meta.name, youTubeID: yt, directURL: direct,
                              year: year, mediaType: meta.type)
    }
}
