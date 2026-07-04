import Foundation

/// Unified, gated diagnostic-logging facility. When enabled it lets a Terminal-launched (or
/// Settings-toggled) run narrate what the app is doing: point-in-time events plus a once-a-second
/// heartbeat carrying uptime, resident memory, and a compact snapshot of the current screen and
/// player. Off by default, so shipping builds pay nothing.
///
/// Two ways to turn it on:
///  - Launch with the environment variable VORTX_PROBE=1 (Xcode scheme or a Terminal-launched run).
///  - Flip the "Diagnostic logging" toggle in Settings, which writes UserDefaults key
///    "stremiox.probeLogging" and can enable it at runtime without a relaunch.
///
/// The env flag is read once (it cannot change during the process); the UserDefaults flag is read
/// live on every access so the Settings toggle takes effect immediately.
enum VXProbe {
    /// The env flag is fixed for the process, so cache it; a false read still lets the live
    /// UserDefaults check below flip things on at runtime.
    private static let envEnabled: Bool = ProcessInfo.processInfo.environment["VORTX_PROBE"] == "1"

    /// UserDefaults key the Settings "Diagnostic logging" toggle binds to.
    static let defaultsKey = "stremiox.probeLogging"

    /// True when EITHER the launch env flag is set OR the Settings toggle is on right now. Computed so
    /// the runtime toggle is honored without a relaunch.
    static var enabled: Bool {
        envEnabled || UserDefaults.standard.bool(forKey: defaultsKey)
    }

    /// Log one line under a category, but only when probing is enabled. The message is an autoclosure
    /// so callers pay nothing (no string building) when disabled.
    static func log(_ category: StaticString, _ message: @autoclosure () -> String) {
        guard enabled else { return }
        NSLog("[%@] %@", String(describing: category), message())
    }

    /// Like `log`, but also records the line as the "last event" on the shared state so the next
    /// heartbeat echoes it. Use for discrete moments (a screen change, a source pick, a playback edge).
    static func event(_ category: StaticString, _ message: @autoclosure () -> String) {
        guard enabled else { return }
        let text = message()
        NSLog("[%@] %@", String(describing: category), text)
        VXProbeState.shared.note("\(category): \(text)")
    }
}

/// Thread-safe scratchpad describing what the app is doing right now, sampled by the heartbeat. Held
/// behind a single lock; setters take it only briefly. Written from wherever the relevant state
/// changes (screen router, player) and read once a second by the heartbeat.
final class VXProbeState {
    static let shared = VXProbeState()
    private init() {}

    private let lock = NSLock()

    // All guarded by `lock`.
    private var route = "-"
    private var playerState = "idle"
    private var posSec = 0
    private var durSec = 0
    private var sourceLabel = "-"
    private var engine = "-"
    private var buffering = false
    private var lastEvent = "-"
    private var eventSeq = 0

    /// Current screen / route the user is on.
    func setRoute(_ s: String) {
        lock.lock(); defer { lock.unlock() }
        route = s
    }

    /// Update any subset of the player fields. Nil arguments leave the existing value untouched, so a
    /// caller that only knows the position can pass just `pos:` without clobbering the rest.
    func setPlayer(state: String? = nil, pos: Int? = nil, dur: Int? = nil,
                   source: String? = nil, engine: String? = nil, buffering: Bool? = nil) {
        lock.lock(); defer { lock.unlock() }
        if let state { playerState = state }
        if let pos { posSec = pos }
        if let dur { durSec = dur }
        if let source { sourceLabel = source }
        if let engine { self.engine = engine }
        if let buffering { self.buffering = buffering }
    }

    /// Record the most recent discrete event and bump the monotonically increasing sequence.
    func note(_ s: String) {
        lock.lock(); defer { lock.unlock() }
        lastEvent = s
        eventSeq += 1
    }

    /// Compact one-line summary for the heartbeat.
    func snapshot() -> String {
        lock.lock(); defer { lock.unlock() }
        return "screen=\(route) player=\(playerState) pos=\(posSec)/\(durSec)s src=\(sourceLabel) engine=\(engine) buf=\(buffering ? 1 : 0) last=\(lastEvent)"
    }
}

/// Once-a-second heartbeat: while probing is enabled it logs process uptime, current resident memory,
/// and `VXProbeState.snapshot()`. Runs on a dedicated utility queue so it never touches the main
/// thread. Idempotent to start; keeps a strong reference to the timer so it is not deallocated.
enum VXProbeHeartbeat {
    private static let queue = DispatchQueue(label: "stremiox.vxprobe.heartbeat", qos: .utility)
    private static var timer: DispatchSourceTimer?
    private static let start0 = ProcessInfo.processInfo.systemUptime

    /// Begin the heartbeat. No-op if already running or if probing is disabled.
    static func start() {
        guard VXProbe.enabled else { return }
        queue.sync {
            guard timer == nil else { return }
            let t = DispatchSource.makeTimerSource(queue: queue)
            t.schedule(deadline: .now() + 1.0, repeating: 1.0)
            t.setEventHandler { tick() }
            timer = t
            t.resume()
        }
    }

    private static func tick() {
        let uptime = Int(ProcessInfo.processInfo.systemUptime - start0)
        let mem = residentMemoryMB()
        let memText = mem.map { String(format: "%.0f", $0) } ?? "?"
        VXProbe.log("heartbeat", "up=\(uptime)s mem=\(memText)MB \(VXProbeState.shared.snapshot())")
    }

    /// Current resident memory in MB via mach_task_basic_info. Returns nil if the kernel call fails,
    /// so the heartbeat degrades to "mem=?MB" rather than logging a wrong number.
    private static func residentMemoryMB() -> Double? {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return nil }
        return Double(info.resident_size) / (1024.0 * 1024.0)
    }
}
