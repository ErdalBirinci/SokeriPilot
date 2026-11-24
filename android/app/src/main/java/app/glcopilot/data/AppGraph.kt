package app.glcopilot.data

/**
 * Small, explicit service locator. Application.onCreate runs before every entry
 * point (CarAppService, BroadcastReceiver, Activity).
 */
object AppGraph {

    @Volatile
    private var containerRef: AppContainer? = null

    val isInitialized: Boolean
        get() = containerRef != null

    val container: AppContainer
        get() = containerRef ?: error("AppContainer is not ready yet")

    fun install(container: AppContainer) {
        containerRef = container
    }
}
