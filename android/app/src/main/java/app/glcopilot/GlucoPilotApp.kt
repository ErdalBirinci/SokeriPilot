package app.glcopilot

import android.app.Application
import app.glcopilot.data.AppContainer
import app.glcopilot.data.AppGraph
import app.glcopilot.data.CgmAppDetector
import app.glcopilot.monitor.CarConnectionWatcher

class GlucoPilotApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AppGraph.install(container)

        // If an xDrip-compatible CGM app is installed, enable local broadcast
        // listening automatically without overriding the user's choice.
        if (CgmAppDetector.broadcastCapable(this).isNotEmpty() &&
            !container.settings.xDripUserChose()
        ) {
            container.settings.autoEnableXDrip(true)
        }

        container.start()

        // Watches the Android Auto connection; when the phone is projected into a
        // head unit, driving monitoring starts and fresh data is pulled immediately.
        CarConnectionWatcher.start(this)
    }
}
