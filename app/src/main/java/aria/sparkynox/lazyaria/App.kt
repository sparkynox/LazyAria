package aria.sparkynox.lazyaria

import android.app.Application
import android.webkit.WebView
import aria.sparkynox.lazyaria.browser.BlocklistUpdater
import aria.sparkynox.lazyaria.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    // App-scoped IO scope — survives activity rotation, cancelled on process death
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Load cached blocklist immediately, then check for updates in background
        BlocklistUpdater.initAndSchedule(this, appScope)
    }
}
