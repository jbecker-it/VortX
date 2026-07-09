import Foundation

/// Durable cross-device REMOVE tombstones for LIBRARY / Continue-Watching titles, the library analogue of
/// `AddonTombstones` (and of `ProfileStore`'s `deletedProfiles` set). The app OWNS this set (it lives in
/// `doc.vortx.deletedLibrary`, the app's namespace) so a title the user removed on one device can never be
/// resurrected by a peer device's UNION hydrate, a stale pre-removal cloud blob, or the cold-device library
/// recovery path.
///
/// Today owner-library hydration is add-only (UNION): `recoverOwnerLibraryIfEmpty` re-adds every owned title
/// on a cold/empty engine and `vortxSummary` re-unions the engine library into `doc.vortx.library`, so a
/// removal on device A is silently undone on device B (and re-unioned back into the doc). This is the exact
/// bug `AddonTombstones` already closes for add-ons; this set closes it for the library the same way:
///
///  - WRITE on an in-app Remove (`CoreBridge.removeFromLibrary`, the engine-history branch) so the removal syncs.
///  - PUSH the set into `doc.vortx.deletedLibrary` from `vortxSummary`, and SUBTRACT it from the
///    `doc.vortx.library` UNION so a removed title is never re-unioned back in.
///  - FOLD an incoming `doc.vortx.deletedLibrary` into the local set on a SUCCESSFUL `.doc` pull, so a
///    removal on device A propagates to peers instead of union-resurrecting.
///  - EXCLUDE tombstoned ids from `recoverOwnerLibraryIfEmpty` so the cold-device recovery never re-adds them.
///
/// SAFETY: the overlay-profile Remove path is UNTOUCHED (it goes through `ProfileStore`, which is already
/// tombstone-safe); only the OWNER/account engine-history removal writes here. The set only ever grows from
/// EXPLICIT removals, never from an inferred diff, and a legitimate re-ADD of the same id CLEARS the tombstone
/// (`forget`) so a fresh add of a previously-removed title is honored on every device.
enum LibraryTombstones {
    private static let deletedKey = "stremiox.library.deleted"

    /// Normalize a library id for tombstone identity: trim + lowercase. Stremio/Cinemeta ids (`tt…`, `tmdb:…`)
    /// are effectively lowercase/numeric, so the same trim/lowercase is applied on both the write side (when
    /// recording a removal) and the match side (when subtracting from the library union or the recovery loop),
    /// keeping identity stable across any casing drift.
    static func normalize(_ id: String) -> String {
        id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    /// The current durable removal set (normalized library ids). Read fresh from UserDefaults so every surface
    /// (CoreBridge write, vortxSummary push, syncDown fold, recovery skip) sees the same authority.
    static func all() -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: deletedKey) ?? [])
    }

    /// Record a library removal so it sticks across devices. Idempotent. Returns true when newly added.
    /// Callers MUST guard the OWNER (engine-history) branch before calling: an overlay-profile removal is
    /// private history and must never enter this account-scoped set.
    @discardableResult
    static func tombstone(_ id: String) -> Bool {
        let key = normalize(id)
        guard !key.isEmpty else { return false }
        var set = all()
        guard set.insert(key).inserted else { return false }
        UserDefaults.standard.set(Array(set), forKey: deletedKey)
        return true
    }

    /// Forget a removal tombstone, so an EXPLICIT fresh add of the same title later is honored instead of
    /// being suppressed forever by an old removal. Called from every OWNER-branch add path
    /// (`CoreBridge.addDetailToLibrary` / `addToLibrary` / `addRawMetaToLibrary` / `addCatalogItemToAccount`):
    /// an explicit add is intent to have the title, which supersedes a prior removal. Idempotent. Returns true
    /// when a tombstone was actually cleared.
    ///
    /// Why this is safe against a stale-doc re-resurrection: `vortxSummary` rewrites `doc.vortx.library`
    /// from the live engine library (which now includes the re-added title) and `doc.vortx.deletedLibrary`
    /// from THIS (now-cleared) local set on the next push, so the account doc stops carrying the tombstone.
    @discardableResult
    static func forget(_ id: String) -> Bool {
        let key = normalize(id)
        guard !key.isEmpty else { return false }
        var set = all()
        guard set.remove(key) != nil else { return false }
        UserDefaults.standard.set(Array(set), forKey: deletedKey)
        return true
    }

    /// Fold incoming tombstones (from another device's `doc.vortx.deletedLibrary`) into the local set.
    /// Returns true when the set changed. The UNION means a removal propagates everywhere, exactly like
    /// `AddonTombstones.merge` and `ProfileStore.mergeDeletedTombstones`.
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
