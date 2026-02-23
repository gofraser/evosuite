package org.evosuite.seeding;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;

public class FrequencyBasedPoolTest {

    @Test
    public void testRemoveConstantDecrementsNumConstants() {
        FrequencyBasedPool<String> pool = new FrequencyBasedPool<>();
        pool.addConstant("A");
        pool.addConstant("A");
        pool.addConstant("B");

        // Frequencies: A=2, B=1
        // Total constants = 3

        pool.removeConstant("A");
        // Frequencies: A=1, B=1
        // Total constants = 2

        Map<String, Integer> results = new HashMap<>();
        results.put("A", 0);
        results.put("B", 0);

        int iterations = 10000;
        for (int i = 0; i < iterations; i++) {
            String selected = pool.getRandomConstant();
            results.put(selected, results.get(selected) + 1);
        }

        // Both should be roughly 50% since we removed one "A"
        double ratioA = (double) results.get("A") / iterations;
        double ratioB = (double) results.get("B") / iterations;

        assertTrue("Ratio of A should be around 0.5, but was " + ratioA, ratioA > 0.4 && ratioA < 0.6);
        assertTrue("Ratio of B should be around 0.5, but was " + ratioB, ratioB > 0.4 && ratioB < 0.6);
    }
    
    @Test
    public void testEmptyPoolThrowsException() {
        FrequencyBasedPool<String> pool = new FrequencyBasedPool<>();
        try {
            pool.getRandomConstant();
            fail("Expected exception not thrown");
        } catch (IllegalArgumentException e) {
            assertEquals("Cannot select from empty pool", e.getMessage());
        }
    }
}
