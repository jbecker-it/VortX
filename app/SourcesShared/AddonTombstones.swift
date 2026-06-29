import Foundation

/// Durable cross-device REMOVE tombstones for add-ons, the add-on analogue of
/// `ProfileStore`'s `deletedProfiles` set. The app OWNS this set (it lives in
/// `doc.vortx.deletedAddons`, the app's namespace) so an add-on the user removed on one device can
/// never be resurrected by a peer device's UNION hydrate or a stale pre-removal cloud blob.
///
/// Today add-on hydration is install-only (UNION): `hydrateEngineFromOwnedAddons` reinstalls every
/// owned descriptor and `vortxSummary` re-unions the engine set into `doc.vortx.addons`, so a removal
/// on device A is silently undone on device B (and re-unioned back into the doc). This tombstone set
/// closes that gap exactly the way `deletedProfiles` closes it for profiles:
///
///  - WRITE on an in-app Remove (`CoreBridge.uninstallAddon`) so the removal syncs.
///  - PUSH the set into `doc.vortx.deletedAddons` from `vortxSummary`, and SUBTRACT it from the
///    `doc.vortx.addons` UNION so a removed add-on is never re-unioned back in.
///  - FOLD an incoming `doc.vortx.deletedAddons` (and a future web-authored `doc.webAddonRemovals`)
///    into the local set on a SUCCESSFUL `.doc` pull, then UNINSTALL any still-installed tombstoned
///    add-on from the engine.
///  - EXCLUDE tombstoned URLs from `ownedAddons(from:)` so the hydrate path never reinstalls them.
///
/// SAFETY: official/protected stubs are NEVER tombstoned (a logout resets the engine to exactly those,
/// so tombstoning one would wrongly suppress a default forever). The set only ever grows from EXPLICIT
/// removals, never from an inferred diff, and the apply step is gated behind a SUCCESSFUL account pull.
enum AddonTombstones {
    private static let deletedKey = "stremiox.addons.deleted"

    /// Normalize a transportUrl for tombstone identity: trim + lowercase. The engine keys add-ons by the
    /// exact transportUrl, so the same trim/lowercase is applied on both the write side (when recording a
    /// removal) and the apply side (when matching an installed add-on against the set), keeping identity
    /// stable across the descriptor's casing.
    static func normalize(_ url: String) -> String {
        url.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    /// The current durable removal set (normalized transportUrls). Read fresh from UserDefaults so every
    /// surface (CoreBridge write, vortxSummary push, syncDown fold/apply) sees the same authority.
    static func all() -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: deletedKey) ?? [])
    }

    /// Record an add-on removal so it sticks across devices. Idempotent. Returns true when newly added.
    /// Callers MUST guard official/protected before calling (a default stub is never a real removal).
    @discardableResult
    static func tombstone(_ transportUrl: String) -> Bool {
        let key = normalize(transportUrl)
        guard !key.isEmpty else { return false }
        var set = all()
        guard set.insert(key).inserted else { return false }
        UserDefaults.standard.set(Array(set), forKey: deletedKey)
        return true
    }

    /// Fold incoming tombstones (from another device's `doc.vortx.deletedAddons`, or a web-authored
    /// `doc.webAddonRemovals`) into the local set. Returns true when the set changed (so the caller can
    /// uninstall the now-tombstoned add-ons from the engine). The UNION means a tombstone propagates
    /// everywhere, exactly like `ProfileStore.mergeDeletedTombstones`.
    @discardableResult
    static func merge(_ incoming: [String]) -> Bool {
        let normalized = incoming.map(normalize).filter { !$0.isEmpty }
        var set = all()
        let before = set.count
        set.formUnion(normalized)
        guard set.count != before else { return false }
        UserDefaults.standard.set(Array(set), forKey: deletedKey)
        return true
    }
}
