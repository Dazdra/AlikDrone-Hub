package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MaintenanceEntryTest {
    @Test
    public void manualRecordAlwaysExposesProvenance() {
        MaintenanceEntry entry = MaintenanceEntry.manual(
                MaintenanceEntry.Asset.PROPELLERS,
                "Осмотр",
                "Без повреждений"
        );

        assertTrue(entry.provenanceDisplay().contains("вручную"));
        assertFalse(entry.provenanceDisplay().isEmpty());
    }

    @Test
    public void controllerBatteryCannotBeConfusedWithFlightBattery() {
        MaintenanceEntry controller = MaintenanceEntry.manual(
                MaintenanceEntry.Asset.CONTROLLER,
                "Проверен заряд",
                ""
        );
        MaintenanceEntry flightBattery = MaintenanceEntry.manual(
                MaintenanceEntry.Asset.FLIGHT_BATTERY,
                "Проверен корпус",
                ""
        );

        assertTrue(controller.assetCaution().contains("пульта"));
        assertTrue(controller.assetCaution().contains("не к полётной"));
        assertTrue(flightBattery.assetCaution().contains("вручную"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void importedRecordRequiresSourceLabel() {
        new MaintenanceEntry(
                "id",
                1L,
                MaintenanceEntry.Asset.AIRCRAFT,
                "Импорт",
                "",
                MaintenanceEntry.Provenance.IMPORTED,
                ""
        );
    }

    @Test
    public void oversizedManualTextIsBounded() {
        StringBuilder longText = new StringBuilder();
        for (int index = 0; index < 2_000; index++) {
            longText.append('x');
        }

        MaintenanceEntry entry = MaintenanceEntry.manual(
                MaintenanceEntry.Asset.OTHER,
                longText.toString(),
                longText.toString()
        );

        assertTrue(entry.action.length() <= 160);
        assertTrue(entry.note.length() <= 1_000);
    }
}
