package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class SplitInstallListenerLifecycleTest {
    @Test
    public void listenerRetainsApplicationContextInsteadOfActivity()
            throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        ActivityController<Activity> controller =
                Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();

        MainActivity.SplitInstallToastListener listener =
                new MainActivity.SplitInstallToastListener(activity);

        assertTrue(Modifier.isStatic(listener.getClass().getModifiers()));
        for (Field field : listener.getClass().getDeclaredFields()) {
            assertFalse(MainActivity.class.isAssignableFrom(field.getType()));
            assertFalse(field.getName().startsWith("this$"));
        }
        Field contextField = listener.getClass().getDeclaredField(
                "applicationContext"
        );
        contextField.setAccessible(true);
        assertSame(application, (Context) contextField.get(listener));

        controller.pause().stop().destroy();
    }
}
