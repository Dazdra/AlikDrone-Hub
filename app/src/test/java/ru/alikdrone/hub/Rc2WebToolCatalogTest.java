package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Rc2WebToolCatalogTest {
    @Test
    public void everyPublishedEntryUsesReviewedHttpsHost() {
        for (Rc2WebToolCatalog.Entry entry : Rc2WebToolCatalog.entries()) {
            assertTrue(entry.getUrl(), Rc2WebToolCatalog.isAllowed(entry));
            assertTrue(entry.getUrl().startsWith("https://"));
            assertFalse(entry.getCompatibility().getLabel().trim().isEmpty());
        }
    }

    @Test
    public void rejectsHttpCredentialsAndHostConfusion() {
        assertFalse(Rc2WebToolCatalog.isAllowedUrl("http://app.airdata.com/"));
        assertFalse(Rc2WebToolCatalog.isAllowedUrl(
                "https://user:pass@app.airdata.com/"
        ));
        assertFalse(Rc2WebToolCatalog.isAllowedUrl(
                "https://app.airdata.com.evil.example/"
        ));
        assertFalse(Rc2WebToolCatalog.isAllowedUrl(
                "https://evil.example/?next=https://app.airdata.com/"
        ));
    }

    @Test
    public void desktopToolsAreClearlyNotPresentedAsRc2Apps() {
        int desktopTools = 0;
        for (Rc2WebToolCatalog.Entry entry : Rc2WebToolCatalog.entries()) {
            if (entry.getCompatibility()
                    == Rc2WebToolCatalog.Compatibility.DESKTOP_PROCESSING
                    || entry.getCompatibility()
                    == Rc2WebToolCatalog.Compatibility.DESKTOP_PLANNING) {
                desktopTools++;
                assertFalse(entry.getUrl().toLowerCase().endsWith(".apk"));
                assertTrue(
                        entry.getCompatibility().getLabel().contains(
                                "не приложение RC 2"
                        )
                );
            }
        }
        assertTrue("Expected reviewed desktop tools", desktopTools >= 7);
    }
}
