#import <AVFoundation/AVFoundation.h>

// macOS 26 (Tahoe) SwiftUI toolbar-crash guard. Swallows the NSException thrown by
// NSToolbar's private -_insertNewItemWithItemIdentifier:... under SwiftUI's
// ToolbarBridge on a hidden, unused window toolbar, which AppKit otherwise turns
// into a fatal SIGTRAP. Implemented in SourcesShared/VortXToolbarCrashGuard.mm;
// a no-op on non-macOS. Call once at launch from the macOS app delegate.
void VortXInstallToolbarCrashGuard(void);

// AVDisplayCriteria's integer initializer is private SPI, but it is what the
// field-proven tvOS players ship for HDR display-mode switching: the public
// initWithRefreshRate:formatDescription: has been observed building criteria
// that tvOS then ignores for synthetic format descriptions. This class
// extension re-declares the private members so Swift can call them; every call
// site guards with instancesRespondToSelector: first, so an OS that removes
// the SPI degrades to the public path instead of crashing.
//
// videoDynamicRange values (reverse engineered, corroborated across projects):
//   0 = SDR, 2 = HDR10/PQ, 3 = HLG, 4 = Dolby Vision
#if __has_include(<AVFoundation/AVDisplayCriteria.h>)
#import <AVFoundation/AVDisplayCriteria.h>

@interface AVDisplayCriteria ()
@property(readonly) int videoDynamicRange;
@property(readonly, nonatomic) float refreshRate;
- (instancetype)initWithRefreshRate:(float)refreshRate videoDynamicRange:(int)videoDynamicRange;
@end
#endif
