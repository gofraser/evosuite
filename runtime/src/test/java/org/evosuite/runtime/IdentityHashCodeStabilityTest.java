package org.evosuite.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The mocked identity hash must behave like the real one for as long as an object lives:
 * a SUT registry keyed by objects without {@code hashCode()} (110_firebird's
 * {@code GDSFactory.typeToPluginMap} keyed by {@code GDSType}) is built once in static
 * state and looked up from every later test, and a key whose hash changed after the
 * {@code put()} is searched for in the wrong bucket.
 */
public class IdentityHashCodeStabilityTest {

    /** Stands in for a SUT type without hashCode()/equals(), whose hashCode is redirected here. */
    private static final class Key {
        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    @Test
    public void hashOfLiveObjectSurvivesRuntimeReset() {
        System.resetRuntime();
        Key key = new Key();
        int before = System.identityHashCode(key);

        Map<Key, String> registry = new HashMap<>();
        registry.put(key, "plugin");

        System.resetRuntime();
        System.resetRuntime();

        assertEquals(before, System.identityHashCode(key));
        assertEquals("plugin", registry.get(key));
    }

    @Test
    public void objectsCreatedAfterResetAreNumberedFromOneAgain() {
        System.resetRuntime();
        Key first = new Key();
        Key second = new Key();
        assertEquals(1, System.identityHashCode(first));
        assertEquals(2, System.identityHashCode(second));

        System.resetRuntime();
        Key third = new Key();
        assertEquals(1, System.identityHashCode(third));
        // the survivors keep their numbers even though a newcomer now shares one
        assertEquals(1, System.identityHashCode(first));
        assertEquals(2, System.identityHashCode(second));
    }

    @Test
    public void deadObjectsDoNotKeepTheirEntries() throws Exception {
        System.resetRuntime();
        for (int i = 0; i < 10_000; i++) {
            System.identityHashCode(new Key());
        }
        for (int i = 0; i < 5 && liveEntries() > 100; i++) {
            java.lang.System.gc();
            Thread.sleep(50);
            System.identityHashCode(new Key()); // triggers the expunge
        }
        assertEquals(true, liveEntries() < 10_000, "dead objects should have been expunged");
    }

    private static int liveEntries() throws Exception {
        java.lang.reflect.Field f = System.class.getDeclaredField("hashKeys");
        f.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) f.get(null);
        assertNotNull(map);
        synchronized (map) {
            int n = 0;
            for (Object list : map.values()) {
                n += ((java.util.List<?>) list).size();
            }
            return n;
        }
    }

    @Test
    public void sameObjectAlwaysGetsSameValue() {
        System.resetRuntime();
        Key key = new Key();
        assertSame(System.identityHashCode(key), System.identityHashCode(key));
    }
}
