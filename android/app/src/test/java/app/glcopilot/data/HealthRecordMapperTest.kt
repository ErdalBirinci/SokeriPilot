package app.glcopilot.data

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.units.BloodGlucose
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class HealthRecordMapperTest {

    @Test
    fun `a reading maps to an interstitial blood glucose record`() {
        val at = 1_700_000_000_000L
        val record = HealthRecordMapper.bloodGlucose(
            GlucoseReading(mgdl = 128, timestampMillis = at, trend = Trend.FLAT, source = "test")
        )

        assertEquals(Instant.ofEpochMilli(at), record.time)
        assertEquals(BloodGlucose.milligramsPerDeciliter(128.0), record.level)
        assertEquals(
            BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID,
            record.specimenSource
        )
    }
}
