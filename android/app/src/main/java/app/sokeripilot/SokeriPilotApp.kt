package app.sokeripilot

import android.app.Application
import app.sokeripilot.data.AppContainer
import app.sokeripilot.data.AppGraph
import app.sokeripilot.data.CgmAppDetector
import app.sokeripilot.monitor.CarConnectionWatcher

class SokeriPilotApp : Application() {

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
