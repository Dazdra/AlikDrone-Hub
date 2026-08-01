package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class FloatingSwitcherViewParentTest {
    @Test
    public void reusableHandleCanMoveFromExpandedRailToCollapsedRoot() {
        Context context = RuntimeEnvironment.getApplication();
        LinearLayout root = new LinearLayout(context);
        FrameLayout expandedRail = new FrameLayout(context);
        View handle = new View(context);

        expandedRail.addView(handle);
        root.addView(expandedRail);

        FloatingSwitcherService.detachFromParent(handle);
        root.removeAllViews();
        root.addView(handle);

        assertSame(root, handle.getParent());
        assertEquals(0, expandedRail.getChildCount());
    }

    @Test
    public void detachIsSafeForAnAlreadyDetachedHandle() {
        Context context = RuntimeEnvironment.getApplication();
        View handle = new View(context);

        FloatingSwitcherService.detachFromParent(handle);

        assertNull(handle.getParent());
    }
}
