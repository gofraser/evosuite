package org.evosuite.ga.bloatcontrol;

import org.evosuite.Properties;
import org.evosuite.ga.DummyChromosome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MaxSizeBloatControlTest {

    @Test
    public void testDefaultConstructor() {
        int oldMaxSize = Properties.MAX_SIZE;
        try {
            Properties.MAX_SIZE = 50;
            MaxSizeBloatControl<DummyChromosome> control = new MaxSizeBloatControl<>();
            assertEquals(50, control.getMaxSize());

            DummyChromosome c1 = new DummyChromosome(new int[50]);
            assertFalse(control.isTooLong(c1));

            DummyChromosome c2 = new DummyChromosome(new int[51]);
            assertTrue(control.isTooLong(c2));
        } finally {
            Properties.MAX_SIZE = oldMaxSize;
        }
    }

    @Test
    public void testParameterizedConstructor() {
        MaxSizeBloatControl<DummyChromosome> control = new MaxSizeBloatControl<>(10);
        assertEquals(10, control.getMaxSize());

        DummyChromosome c1 = new DummyChromosome(new int[10]);
        assertFalse(control.isTooLong(c1));

        DummyChromosome c2 = new DummyChromosome(new int[11]);
        assertTrue(control.isTooLong(c2));
    }

    @Test
    public void testCopyConstructor() {
        MaxSizeBloatControl<DummyChromosome> original = new MaxSizeBloatControl<>(20);
        MaxSizeBloatControl<DummyChromosome> copy = new MaxSizeBloatControl<>(original);

        assertEquals(20, copy.getMaxSize());

        DummyChromosome c1 = new DummyChromosome(new int[20]);
        assertFalse(copy.isTooLong(c1));

        DummyChromosome c2 = new DummyChromosome(new int[21]);
        assertTrue(copy.isTooLong(c2));
    }

    @Test
    public void testSetMaxSize() {
        MaxSizeBloatControl<DummyChromosome> control = new MaxSizeBloatControl<>(10);
        control.setMaxSize(5);
        assertEquals(5, control.getMaxSize());

        DummyChromosome c1 = new DummyChromosome(new int[5]);
        assertFalse(control.isTooLong(c1));

        DummyChromosome c2 = new DummyChromosome(new int[6]);
        assertTrue(control.isTooLong(c2));
    }
}
