package cloud.samlo.couch

import cloud.samlo.couch.data.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConditionTest {
    @Test
    fun mapsWmoCodesToConditions() {
        assertEquals(Condition.CLEAR, Condition.of(0))
        assertEquals(Condition.MOSTLY_CLEAR, Condition.of(1))
        assertEquals(Condition.PARTLY_CLOUDY, Condition.of(2))
        assertEquals(Condition.CLOUDY, Condition.of(3))
        assertEquals(Condition.FOG, Condition.of(48))
        assertEquals(Condition.DRIZZLE, Condition.of(55))
        assertEquals(Condition.RAIN, Condition.of(82))
        assertEquals(Condition.SNOW, Condition.of(75))
        assertEquals(Condition.STORM, Condition.of(99))
    }

    @Test
    fun unknownCodesAreBlankAndCloudy() {
        val unknown = Condition.of(1234)
        assertEquals(Condition.UNKNOWN, unknown)
        assertEquals(0, unknown.label)
        assertEquals(R.drawable.wx_cloudy, unknown.icon(true))
    }

    @Test
    fun labelsComeFromStringResources() {
        assertEquals(R.string.weather_clear, Condition.of(0).label)
        assertEquals(R.string.weather_rain, Condition.of(63).label)
        assertEquals(R.string.weather_snow, Condition.of(71).label)
    }

    @Test
    fun skiesWithASunSwitchIconAtNight() {
        assertEquals(R.drawable.wx_clear_day, Condition.of(0).icon(isDay = true))
        assertEquals(R.drawable.wx_clear_night, Condition.of(0).icon(isDay = false))
        assertEquals(R.drawable.wx_partly_cloudy_night, Condition.of(1).icon(isDay = false))
        assertEquals(R.drawable.wx_partly_cloudy_night_rain, Condition.of(63).icon(isDay = false))
        assertEquals(R.drawable.wx_overcast, Condition.of(3).icon(isDay = false))
    }

    @Test
    fun drizzleAndRainHaveTheirOwnIcons() {
        assertEquals(R.drawable.wx_drizzle, Condition.of(51).icon(true))
        assertEquals(R.drawable.wx_partly_cloudy_day_rain, Condition.of(65).icon(true))
        assertNotEquals(Condition.of(51).label, Condition.of(65).label)
    }
}
