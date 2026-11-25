package app.sokeripilot.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import app.sokeripilot.data.AppGraph

class SokeriPilotCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds only: allow local test hosts to connect.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Release: the library's Google-signed host allowlist (Android Auto + AAOS).
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = SokeriPilotSession()
}

class SokeriPilotSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val container = AppGraph.container
        return GlucoseScreen(
            carContext = carContext,
            repository = container.repository,
            settings = container.settings,
            onRefresh = container::refreshNow
        )
    }
}
