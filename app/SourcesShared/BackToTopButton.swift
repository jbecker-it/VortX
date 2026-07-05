import SwiftUI

/// A floating "back to top" button for long browse screens (Home, Discover). It fades in once you have
/// scrolled the top of the screen out of view and, when tapped, bumps the tab's scroll-to-top signal —
/// reusing the exact path a re-tap of the active tab already drives (see [[TabScrollToTop]]). So it shares
/// the same anchor and animation as re-tap-to-top; it is just a second, always-reachable way to get there.
///
/// Visibility is driven by a MARKER placed near the top of the scroll content, whose `onAppear` /
/// `onDisappear` the `LazyVStack` fires as it loads / recycles that child on scroll. This is far more
/// reliable than a `.background(GeometryReader)` scroll-offset (a background's preference does not update
/// during a ScrollView's layer-transform scroll) and needs no macOS 15 / iOS 18 scroll APIs (the CI
/// runners lag the newest SDK). The marker and the button coordinate through a tiny shared object keyed by
/// the tab, mirroring how `TabScrollToTop` keys its scroll signal.

@MainActor
final class BackToTopVisibility: ObservableObject {
    static let shared = BackToTopVisibility()
    private init() {}

    /// Per-tab "is the top out of view" flag. The marker sets it; the button observes it.
    @Published private(set) var shown: [String: Bool] = [:]

    func isShown(_ key: String) -> Bool { shown[key] ?? false }
    func set(_ key: String, _ value: Bool) {
        if shown[key] != value { shown[key] = value }   // avoid redundant publishes
    }
}

extension View {
    /// Place near the TOP of a scroll column (e.g. right after the hero). While it is on screen the
    /// back-to-top button stays hidden; once the `LazyVStack` recycles it (you have scrolled the top away)
    /// the button appears. Give it real (non-zero) height so appear/disappear fire cleanly.
    func backToTopMarker(key: String) -> some View {
        Color.clear
            .frame(height: 1)
            .onAppear { BackToTopVisibility.shared.set(key, false) }
            .onDisappear { BackToTopVisibility.shared.set(key, true) }
            .accessibilityHidden(true)
    }
}

private struct BackToTopButton: ViewModifier {
    let key: String     // one of TabScrollKeys.*
    let bottomInset: CGFloat   // clearance so the button floats ABOVE the bottom tab bar

    @ObservedObject private var visibility = BackToTopVisibility.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let shown = visibility.isShown(key)
        content
            .overlay(alignment: .bottomTrailing) {
                if shown {
                    Button { TabScrollToTop.shared.bump(key) } label: {
                        Image(systemName: "chevron.up")
                            .font(.system(size: 17, weight: .heavy))
                            .foregroundStyle(Theme.Palette.canvas)
                            .frame(width: 46, height: 46)
                            .background(Theme.Palette.accent, in: Circle())
                            .overlay(Circle().strokeBorder(.white.opacity(0.12), lineWidth: 1))
                            .shadow(color: .black.opacity(0.38), radius: 9, y: 3)
                    }
                    .buttonStyle(.plain)
                    .padding(.trailing, Theme.Space.lg)
                    .padding(.bottom, bottomInset)
                    .transition(reduceMotion ? .opacity
                                             : .opacity.combined(with: .scale(scale: 0.8, anchor: .bottom)))
                    .accessibilityLabel(Text("Scroll to top"))
                }
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: shown)
    }
}

extension View {
    /// Add a floating back-to-top button to a scroll screen. Apply to the `ScrollView` (or a wrapper); the
    /// screen must place `.backToTopMarker(key:)` near the top of its content and already use
    /// `.scrollToTopOnBump(key)` (the button reuses that path). `bottomInset` lifts it clear of the tab bar.
    func backToTopButton(key: String, bottomInset: CGFloat = 96) -> some View {
        modifier(BackToTopButton(key: key, bottomInset: bottomInset))
    }
}
