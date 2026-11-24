package app.glcopilot.data

/** How a known CGM app integrates with GlucoPilot. */
enum class CgmIntegration {
    /** Sends an xDrip-compatible `BgEstimate` broadcast; the best source when it is enabled. */
    XDRIP_BROADCAST,

    /**
     * The app's own notification carries the value. Read automatically through the
     * notification listener; no setting inside the CGM app is required.
     */
    NOTIFICATION
}

data class CgmApp(
    val packageName: String,
    val label: String,
    val integration: CgmIntegration
)

/**
 * Pure Kotlin catalog (no Android dependencies): unit tests can pass a set of
 * installed packages to verify detection. Package names cover Abbott's region-specific LibreLink/Libre 3
 * apps and the xDrip ecosystem.
 */
object CgmAppCatalog {

    val KNOWN: List<CgmApp> = listOf(
        CgmApp("com.eveningoutpost.dexdrip", "xDrip+", CgmIntegration.XDRIP_BROADCAST),
        CgmApp("tk.glucodata", "Juggluco", CgmIntegration.XDRIP_BROADCAST),
        CgmApp("com.bubblesoft.android.diabox", "Diabox", CgmIntegration.NOTIFICATION),

        // FreeStyle LibreLink — separate packages per country.
        CgmApp("com.freestylelibre.app.tr", "FreeStyle LibreLink (TR)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.gb", "FreeStyle LibreLink (GB)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.de", "FreeStyle LibreLink (DE)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.fr", "FreeStyle LibreLink (FR)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.it", "FreeStyle LibreLink (IT)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.es", "FreeStyle LibreLink (ES)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.us", "FreeStyle LibreLink (US)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.in", "FreeStyle LibreLink (IN)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.br", "FreeStyle LibreLink (BR)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.ch", "FreeStyle LibreLink (CH)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.be", "FreeStyle LibreLink (BE)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.sa", "FreeStyle LibreLink (SA)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.nl", "FreeStyle LibreLink (NL)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.ca", "FreeStyle LibreLink (CA)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.au", "FreeStyle LibreLink (AU)", CgmIntegration.NOTIFICATION),
        CgmApp("com.freestylelibre.app.nz", "FreeStyle LibreLink (NZ)", CgmIntegration.NOTIFICATION),

        // Libre 3 may be packaged separately.
        CgmApp("com.freestylelibre3.app.us", "FreeStyle Libre 3 (US)", CgmIntegration.NOTIFICATION),

        CgmApp("com.dexcom.g6", "Dexcom G6", CgmIntegration.NOTIFICATION),
        CgmApp("com.dexcom.g7", "Dexcom G7", CgmIntegration.NOTIFICATION)
    )

    /** Returns known CGM apps from a set of installed packages, in catalog order. */
    fun detect(installedPackages: Set<String>): List<CgmApp> =
        KNOWN.filter { it.packageName in installedPackages }

    /** Display label for a known package, or null when it is not in the catalog. */
    fun labelFor(packageName: String): String? =
        KNOWN.firstOrNull { it.packageName == packageName }?.label

    /** xDrip-compatible broadcast sources that can be listened to automatically. */
    fun broadcastSources(installedPackages: Set<String>): List<CgmApp> =
        detect(installedPackages).filter { it.integration == CgmIntegration.XDRIP_BROADCAST }
}
