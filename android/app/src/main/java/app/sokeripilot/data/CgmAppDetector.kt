package app.sokeripilot.data

import android.content.Context
import android.content.pm.PackageManager

/**
 * Queries only the catalog packages declared in the manifest `<queries>`; it does
 * not scan all apps (QUERY_ALL_PACKAGES is not used).
 *
 * Detection is used for messaging only. Values themselves arrive through the
 * notification listener (any CGM app, no setup inside it) or the xDrip-compatible
 * broadcast when the source app publishes it.
 */
object CgmAppDetector {

    @Suppress("DEPRECATION")
    fun installedPackages(context: Context): Set<String> =
        CgmAppCatalog.KNOWN
            .map { it.packageName }
            .filter { packageName ->
                try {
                    context.packageManager.getPackageInfo(packageName, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
            .toSet()

    fun detect(context: Context): List<CgmApp> =
        CgmAppCatalog.detect(installedPackages(context))

    /** Verified xDrip broadcast sources only (xDrip+, Juggluco). */
    fun broadcastCapable(context: Context): List<CgmApp> =
        CgmAppCatalog.broadcastSources(installedPackages(context))

    /** We cannot know whether the broadcast is enabled in the source app; we only know it is installed. */
    fun hasInstalledSource(context: Context): Boolean = detect(context).isNotEmpty()
}
