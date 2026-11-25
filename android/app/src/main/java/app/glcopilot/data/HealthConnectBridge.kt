package app.glcopilot.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Optional export of new readings to Android Health Connect. Nothing is read back and
 * nothing leaves the phone; the user grants the write permission explicitly and can
 * revoke it at any time. Every failure is silent: this is a convenience, never the
 * source of truth for the cockpit.
 */
class HealthConnectBridge(
    context: Context,
    private val settings: SettingsRepository
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastWrittenMillis = 0L

    fun isAvailable(): Boolean = try {
        HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
    } catch (_: Exception) {
        false
    }

    fun maybeWrite(reading: GlucoseReading) {
        if (!settings.healthConnectEnabled()) return
        if (reading.timestampMillis <= lastWrittenMillis) return
        lastWrittenMillis = reading.timestampMillis

        scope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(appContext)
                if (WRITE_PERMISSION !in client.permissionController.getGrantedPermissions()) return@launch
                client.insertRecords(listOf(HealthRecordMapper.bloodGlucose(reading)))
            } catch (_: Exception) {
                // Best effort: Health Connect may be updating or access was revoked.
            }
        }
    }

    suspend fun hasPermission(): Boolean = try {
        if (!isAvailable()) {
            false
        } else {
            WRITE_PERMISSION in HealthConnectClient.getOrCreate(appContext)
                .permissionController
                .getGrantedPermissions()
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        /** Health Connect uses the platform permission string for blood glucose writes. */
        const val WRITE_PERMISSION = "android.permission.health.WRITE_BLOOD_GLUCOSE"
    }
}

/** Maps one validated reading to a Health Connect record. Kept separate for testing. */
object HealthRecordMapper {

    fun bloodGlucose(reading: GlucoseReading): BloodGlucoseRecord {
        val time = Instant.ofEpochMilli(reading.timestampMillis)
        return BloodGlucoseRecord(
            time = time,
            zoneOffset = ZoneId.systemDefault().rules.getOffset(time),
            metadata = Metadata.manualEntry(),
            level = BloodGlucose.milligramsPerDeciliter(reading.mgdl.toDouble()),
            specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID,
            mealType = 0,
            relationToMeal = BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN
        )
    }
}
