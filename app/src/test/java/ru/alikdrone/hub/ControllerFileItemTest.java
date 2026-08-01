package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ControllerFileItemTest {
    @Test
    public void preservesUnknownProviderSize() {
        ControllerFileItem item = new ControllerFileItem(
                null,
                "record.txt",
                "text/plain",
                -1L,
                0L,
                false,
                false
        );

        assertEquals(-1L, item.sizeBytes);
    }
}
