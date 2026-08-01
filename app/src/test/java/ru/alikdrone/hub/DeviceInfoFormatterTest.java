package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DeviceInfoFormatterTest {
    @Test
    public void formatsStorageUsingReadableBinaryUnits() {
        assertEquals("0 Б", DeviceInfoFormatter.formatBytes(0));
        assertEquals("1,0 КБ", DeviceInfoFormatter.formatBytes(1024));
        assertEquals(
                "2,0 ГБ свободно из 4,0 ГБ",
                DeviceInfoFormatter.formatCapacity(
                        2L * 1024L * 1024L * 1024L,
                        4L * 1024L * 1024L * 1024L
                )
        );
    }

    @Test
    public void formatsUptimeWithoutInventingPrecision() {
        assertEquals("0 мин", DeviceInfoFormatter.formatUptime(30_000L));
        assertEquals(
                "1 д 2 ч 3 мин",
                DeviceInfoFormatter.formatUptime(
                        ((24L + 2L) * 60L + 3L) * 60_000L
                )
        );
    }

    @Test
    public void formatsBatteryTemperatureAndRefreshRate() {
        assertEquals("23,5 °C", DeviceInfoFormatter.formatTemperature(235));
        assertEquals("60 Гц", DeviceInfoFormatter.formatRefreshRate(59.94f));
        assertEquals("Нет данных", DeviceInfoFormatter.formatRefreshRate(0f));
    }
}
