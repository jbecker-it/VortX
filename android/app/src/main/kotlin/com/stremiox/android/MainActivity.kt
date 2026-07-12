package com.stremiox.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.stremiox.android.data.CatalogRepository
import com.stremiox.android.data.PreviewCatalogRepository
import com.stremiox.android.engine.EngineStremioRepository
import com.stremiox.android.ui.StremioXApp

/// Android + Android TV entry point. The five-tab Compose shell in [StremioXApp] matches the iOS and
/// Apple TV structure. It now runs on the shared stremio-core engine (over JNI, the same engine the
/// iOS/tvOS apps use) via [EngineStremioRepository]; the libmpv player drops in behind the same seam.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() must run before super.onCreate(): it installs the AndroidX
        // SplashScreen (Theme.VortX.Splash -- brand gold mark on warm obsidian, see themes.xml) for
        // the cold-start gap before Compose draws its first frame. Framework-owned on API 31+; the
        // compat library paints the same background+icon itself on 26-30, so minSdk 26 gets it
        // uniformly.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Edge-to-edge is enforced app-wide (ANDROID-PLAN.md S01 scope; DESIGN-SYSTEM.md chrome
        // recedes behind content). StremioXTheme forces the dark scheme regardless of the system
        // setting (ui/theme/Theme.kt), so both system bars get light icons unconditionally instead of
        // the OS's light/dark auto-resolution, which would otherwise mismatch a light system theme.
        // The Compose shell consumes the resulting insets via Scaffold's contentPadding (StremioXApp).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        // Reduced motion (Settings.Global.ANIMATOR_DURATION_SCALE == 0, the same system signal the
        // Android-native translation table in ANDROID-PLAN.md §0 calls out): skip the custom
        // fade-out and let the splash view disappear immediately instead of animating it off.
        if (!animationsReduced()) {
            splashScreen.setOnExitAnimationListener { view ->
                ObjectAnimator.ofFloat(view.view, "alpha", 1f, 0f).apply {
                    duration = 220L
                    interpolator = AnticipateInterpolator()
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = view.remove()
                    })
                    start()
                }
            }
        }

        setContent { StremioXApp(repo = engineRepository()) }
    }

    private fun animationsReduced(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /// Build the real engine repository, backed by [com.stremiox.android.engine.StremioXCore] (native
    /// lib load + engine init happen inside its constructor). This is the boundary where the native
    /// world can fail hard: a missing/incompatible `libstremiox_core.so` throws [UnsatisfiedLinkError]
    /// when the class loads, and engine init can throw. We keep it fail-soft so a native-side problem
    /// degrades to the offline preview data (the UI still renders) instead of crashing the whole app.
    private fun engineRepository(): CatalogRepository =
        runCatching { EngineStremioRepository(applicationContext) as CatalogRepository }
            .getOrElse { error ->
                Log.e(TAG, "Engine repository unavailable; falling back to preview data", error)
                PreviewCatalogRepository()
            }

    private companion object {
        const val TAG = "StremioXEngine"
    }
}
