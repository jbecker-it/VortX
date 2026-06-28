import SwiftUI

/// ERDB (Easy Ratings Database, easyratingsdb.com, open-source at github.com/realbestia1/erdb) renders
/// posters, backdrops, LOGOS, and episode thumbnails with rating badges and quality overlays baked in. It
/// is token-based: the user pastes their `Tk-...` token (which carries their saved providers, layout, and
/// rating placement server-side), and the renderer URL is simply `{base}/{token}/{type}/{id}.jpg` with NO
/// query string. The base URL is configurable so VortX can later point at a self-hosted instance (e.g. a
/// Cloudflare Worker) instead of easyratingsdb.com.
///
/// Unlike the per-image XRDB transformer, ERDB is the only one of our art providers that also serves a
/// rating-baked LOGO by id, which is exactly what add-on authors asked for.
enum ERDB {
    static let enabledKey = "stremiox.erdb.enabled"   // absent = OFF (opt-in: it replaces every poster)
    static let tokenKey = "stremiox.erdb.token"
    static let baseKey = "stremiox.erdb.baseURL"
    static let fanartPostersKey = "stremiox.erdb.fanartPosters"   // absent = off
    static let defaultBase = "https://erdb.vortx.tv"

    static var token: String {
        (UserDefaults.standard.string(forKey: tokenKey) ?? "").trimmingCharacters(in: .whitespaces)
    }

    /// ERDB drives artwork whenever the toggle is on. The hosted erdb.vortx.tv is KEYLESS, so no token is
    /// required: a tokenless request ({base}/{type}/{id}.jpg) returns VortX-styled, rating-baked art. A token
    /// is only for a custom self-hosted instance that carries the user's own providers/layout. Opt-in
    /// (default off) because turning it on replaces every poster, backdrop, and logo with a baked render.
    static var isActive: Bool {
        UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? false
    }

    /// Opt-in: request fanart.tv posters from the image service (it appends ?art=fanart, using the user's own
    /// fanart key when set, else the VortX service key). Posters only; backdrops and logos are unaffected.
    static var fanartPosters: Bool {
        UserDefaults.standard.bool(forKey: fanartPostersKey)
    }

    /// The renderer URL for a title, or the `fallback` art when inactive or the id is not renderable. `type`
    /// is "poster", "backdrop", "logo", or "thumbnail". The id keeps its scheme (ERDB accepts the colons in
    /// `tmdb:movie:603` / `tt0944947:1:1` directly in the path), so it is inserted raw.
    static func imageURL(_ type: String, id: String, fallback: String?) -> String? {
        guard isActive, let rid = renderableID(id) else { return fallback }
        let tk = token
        let tokenSeg = tk.isEmpty ? "" : "\(tk)/"   // tokenless self-host form when no token is set
        var u = "\(normalizedBase())/\(tokenSeg)\(type)/\(rid).jpg"
        if type == "poster", fanartPosters {
            u += "?art=fanart"
            if let fk = ApiKeys.fanartKey(), let enc = fk.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) {
                u += "&fk=\(enc)"
            }
        }
        return u
    }

    /// ERDB resolves IMDb, TMDB, TVDB, and the anime id schemes. A custom add-on id it cannot map keeps its
    /// original artwork (return nil so the caller uses the fallback).
    private static func renderableID(_ id: String) -> String? {
        if id.hasPrefix("tt") { return id }
        for scheme in ["tmdb:", "tvdb:", "kitsu:", "anilist:", "mal:", "anidb:", "realimdb:"] where id.hasPrefix(scheme) {
            return id
        }
        return nil
    }

    /// Trimmed base URL, http(s) only, trailing slashes removed; defaults to easyratingsdb.com (or a future
    /// VortX-hosted instance) when the user has not set a custom one.
    private static func normalizedBase() -> String {
        var s = (UserDefaults.standard.string(forKey: baseKey) ?? "").trimmingCharacters(in: .whitespaces)
        if s.isEmpty || !(s.hasPrefix("http://") || s.hasPrefix("https://")) { return defaultBase }
        while s.hasSuffix("/") { s.removeLast() }
        return s.isEmpty ? defaultBase : s
    }
}

/// fanart.tv as a DIRECT art provider, INDEPENDENT of ERDB. Off by default. When on, the async art sites
/// prefer fanart.tv's community clearlogo / clearart / poster / background (resolved via `FanartClient`
/// using the user's fanart.tv key from Metadata keys) over the add-on/metahub art, WITHOUT enabling ERDB's
/// rating-baked renders. Resolution is async (network), so callers await it from already-async art sites
/// (e.g. the hero logo); it never blocks the synchronous `PosterArtwork` URL builders.
enum Fanart {
    static let enabledKey = "stremiox.fanart.enabled"   // absent = OFF (opt-in, independent of ERDB)
    static var isEnabled: Bool { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? false }

    /// The fanart.tv clearlogo URL for a title, or nil when disabled / fanart has none. Async (one cached
    /// fanart.tv lookup per id); fail-soft so the caller keeps its existing logo on any miss.
    static func logo(id: String, type: String) async -> String? {
        guard isEnabled else { return nil }
        return await FanartClient.art(id: id, type: type).logo
    }

    /// The fanart.tv poster URL for a title, or nil when disabled / fanart has none. Async, fail-soft.
    static func poster(id: String, type: String) async -> String? {
        guard isEnabled else { return nil }
        return await FanartClient.art(id: id, type: type).poster
    }

    /// The fanart.tv background (16:9) URL for a title, or nil when disabled / fanart has none. Async, fail-soft.
    static func background(id: String, type: String) async -> String? {
        guard isEnabled else { return nil }
        return await FanartClient.art(id: id, type: type).background
    }
}

/// The single place every poster / backdrop / logo URL is resolved, so the active art provider is chosen
/// once. Precedence: ERDB (when the user set a token) wins, then the VortX / XRDB poster service, then the
/// original add-on artwork. Keeps the three poster call sites and the two logo slots from each re-deciding.
enum PosterArtwork {
    /// True when an art provider bakes ratings onto the image, so the app must NOT also draw its own rating
    /// badge (avoids a double badge).
    static var bakesRatings: Bool { ERDB.isActive || XRDB.isEnabled }

    /// Poster image URL for a title id. ERDB token wins, then VortX / XRDB, then the original poster.
    static func poster(id: String, fallback: String?) -> String? {
        if ERDB.isActive { return ERDB.imageURL("poster", id: id, fallback: fallback) }
        return XRDB.imageURL(id: id, fallback: fallback)
    }

    /// Backdrop image URL. ERDB when active (it bakes ratings/quality on backdrops too), else the original.
    static func backdrop(id: String, fallback: String?) -> String? {
        ERDB.isActive ? ERDB.imageURL("backdrop", id: id, fallback: fallback) : fallback
    }

    /// Title clearart LOGO URL. ERDB serves a rating-baked logo by id when active; otherwise the caller's
    /// existing logo (the add-on `meta.logo` or the metahub clearart) is used unchanged.
    static func logo(id: String?, fallback: String?) -> String? {
        if ERDB.isActive, let id, let url = ERDB.imageURL("logo", id: id, fallback: nil) { return url }
        return fallback
    }
}
