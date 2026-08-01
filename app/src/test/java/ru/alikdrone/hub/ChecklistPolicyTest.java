package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class ChecklistPolicyTest {
    @Test
    public void emptyChecklistNeverClaimsReadiness() {
        ChecklistPolicy.Status status = ChecklistPolicy.evaluate(null);

        assertEquals(0, status.completed);
        assertFalse(status.allMarked());
        assertTrue(status.summary().contains("не является допуском"));
    }

    @Test
    public void fullChecklistStillCarriesSafetyDisclaimer() {
        Set<String> checked = new HashSet<>();
        for (ChecklistPolicy.Item item : ChecklistPolicy.items()) {
            checked.add(item.id);
        }

        ChecklistPolicy.Status status = ChecklistPolicy.evaluate(checked);

        assertTrue(status.allMarked());
        assertTrue(status.summary().contains("не является допуском"));
        assertTrue(ChecklistPolicy.disclaimer().contains("не подтверждает"));
    }

    @Test
    public void controllerAndFlightBatteryAreSeparateItems() {
        String allLabels = "";
        for (ChecklistPolicy.Item item : ChecklistPolicy.items()) {
            allLabels += item.label + " " + item.hint;
        }
        assertTrue(allLabels.contains("Пульт"));
        assertTrue(allLabels.contains("Полётные батареи"));
    }
}
