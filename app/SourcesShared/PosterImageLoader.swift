import SwiftUI
#if canImport(UIKit)
import UIKit
public typealias VXPosterImage = UIImage
#elseif canImport(AppKit)
import AppKit
public typealias VXPosterImage = NSImage
#endif
import ImageIO
import CoreGraphics

/// The single poster / catalog-art byte loader shared by every native Apple surface (iPhone, iPad, Mac,
/// tvOS). It exists because the app was decoding poster bytes ON the main actor and fetching every poster
/// through `URLSession.shared`, whose default `URLCache` is far too small to hold a catalog page. The result
/// was the "half the posters stay blank + the whole app is laggy on open" report: posters thrashed the tiny
/// shared cache (constant re-fetch), competed with trickplay / ratings / sync on the same connection pool,
/// and each decode blocked the main thread while the user scrolled.
///
/// This loader fixes all three:
///   1. A DEDICATED, generously-sized `URLCache` (`configureSharedCache`, called once at launch) so a whole
///      catalog page of poster bytes stays cached across scrolls and relaunches instead of evicting.
///   2. A BOUNDED-concurrency gate (`ConcurrencyGate`) so a grid appearing fires a handful of fetches at a
///      time, never hundreds at once (which is what starved individual posters into coming back empty).
///   3. OFF-MAIN decode + downsample via ImageIO, so the main thread never decodes a poster while scrolling.
///
/// A decoded in-memory `NSCache` sits on top so a poster shown in several rails decodes once. Every miss is
/// retried on the next appear (a scroll-away cancel is not a failure), so a transient miss never latches a
/// permanently blank card. Callers keep their own frame / crop / clip; this only returns the decoded image.
enum PosterImageLoader {

    // MARK: shared URLCache (called once at launch, before any image request)

    /// Install a dedicated, generously-sized shared `URLCache`. The default iOS shared cache is ~4 MB memory /
    /// ~20 MB disk, which cannot hold one catalog page of posters, so `.returnCacheDataElseLoad` re-fetched
    /// nearly every poster on every scroll. Sized here to comfortably hold many pages of poster JPEGs so the
    /// cache actually serves them. Idempotent: safe to call more than once, but intended once from app init.
    static func configureSharedCache() {
        // 64 MB memory / 512 MB disk: posters are small JPEGs, so this holds thousands of them and survives
        // relaunch (disk), which is exactly what turns the poster grid from "re-fetch everything" into
        // "serve from cache". Well within the increased-memory entitlement the native targets already carry.
        let memoryCapacity = 64 * 1024 * 1024
        let diskCapacity = 512 * 1024 * 1024
        let cache = URLCache(memoryCapacity: memoryCapacity, diskCapacity: diskCapacity, diskPath: "vortx-images")
        URLCache.shared = cache
    }

    // MARK: bounded-concurrency image session

    /// A dedicated session for image bytes so poster fetches share a connection pool sized for many small
    /// GETs and do NOT contend with `URLSession.shared` (sync, ratings, trickplay, add-on manifests). Uses the
    /// shared (now large) URLCache for disk persistence; `.returnCacheDataElseLoad` is set per request.
    private static let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.httpMaximumConnectionsPerHost = 6
        cfg.timeoutIntervalForRequest = 20
        cfg.urlCache = URLCache.shared
        cfg.requestCachePolicy = .returnCacheDataElseLoad
        return URLSession(configuration: cfg)
    }()

    /// Caps how many poster fetches run at once. A grid appearing enqueues dozens of loads; without a cap they
    /// all hit the network simultaneously and starve each other (the blank-poster symptom). 6 in flight keeps
    /// the pipe full without the stampede.
    private actor ConcurrencyGate {
        private let limit: Int
        private var active = 0
        private var waiters: [CheckedContinuation<Void, Never>] = []
        init(limit: Int) { self.limit = limit }
        func acquire() async {
            if active < limit { active += 1; return }
            // Wait for a permit HANDED OFF by release(); the permit is already counted in `active`, so we must
            // NOT increment again here (doing so, plus a fresh acquire slipping in during the decrement/resume
            // gap, let `active` exceed `limit` and over-admit loads, the very stampede this gate prevents).
            await withCheckedContinuation { waiters.append($0) }
        }
        func release() {
            // Hand the permit to a waiter if any (active unchanged - the permit just moves); otherwise free it.
            if !waiters.isEmpty { waiters.removeFirst().resume() } else { active -= 1 }
        }
    }
    private static let gate = ConcurrencyGate(limit: 6)

    // MARK: decoded-image memory cache

    /// In-memory decoded cache on top of the URLCache (bytes). Keyed by the resolved URL so a poster shown in
    /// several rails decodes once; evicted under memory pressure by `NSCache`.
    private static let memory: NSCache<NSURL, VXPosterImage> = {
        let c = NSCache<NSURL, VXPosterImage>()
        c.countLimit = 500
        return c
    }()

    /// A synchronous decoded-cache peek so a view can paint instantly on a cache hit without a task hop.
    static func cached(_ url: URL) -> VXPosterImage? { memory.object(forKey: url as NSURL) }

    // MARK: load

    /// Load + decode a poster image for `urlString`, off the main thread, bounded, cached. Returns nil on a
    /// real failure OR on cancellation (the caller treats nil-from-cancel as "retry next appear", so a
    /// scroll-away never latches a blank card). `maxPixel` downsamples very large art to a sane on-card size.
    static func load(_ urlString: String?, maxPixel: CGFloat = 900) async -> VXPosterImage? {
        guard let raw = urlString, !raw.isEmpty, let url = URL(string: raw) else { return nil }
        if let hit = memory.object(forKey: url as NSURL) { return hit }

        await gate.acquire()
        defer { Task { await gate.release() } }
        // A cancel between acquiring the gate and starting the fetch: bail without a network hit.
        if Task.isCancelled { return nil }

        do {
            var req = URLRequest(url: url)
            req.cachePolicy = .returnCacheDataElseLoad   // poster art is immutable; prefer the (now large) disk cache
            let (data, _) = try await session.data(for: req)
            if Task.isCancelled { return nil }
            // Decode + downsample OFF the main thread so scrolling never blocks on a poster decode.
            guard let image = decode(data, maxPixel: maxPixel) else { return nil }
            memory.setObject(image, forKey: url as NSURL)
            return image
        } catch {
            return nil   // includes URLError.cancelled; the caller retries on the next appear
        }
    }

    // MARK: off-main ImageIO decode + downsample

    /// Decode `data` to a downsampled image using ImageIO's thumbnail path, which decodes straight to the
    /// target size (cheaper than full-decode-then-resize) and never touches UIKit/AppKit on the main thread.
    private static func decode(_ data: Data, maxPixel: CGFloat) -> VXPosterImage? {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) else {
            return VXPosterImage(data: data)   // fall back to the platform decoder (e.g. a format ImageIO thumbnails oddly)
        }
        let downsampleOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(maxPixel),
        ] as [CFString: Any] as CFDictionary
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, downsampleOptions) else {
            return VXPosterImage(data: data)
        }
        #if canImport(UIKit)
        return UIImage(cgImage: cg)
        #elseif canImport(AppKit)
        return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
        #endif
    }
}
