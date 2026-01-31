package org.evosuite.statistics;

import org.junit.Test;
import static org.junit.Assert.*;
import org.evosuite.Properties;
import java.util.List;

public class SequenceOutputVariableFactoryTest {

    @Test
    public void testIntegerInterpolation() {
        Properties.TIMELINE_INTERPOLATION = true;
        Properties.TIMELINE_INTERVAL = 1000;

        SequenceOutputVariableFactory<Integer> factory = new SequenceOutputVariableFactory<Integer>(RuntimeVariable.Coverage) {
            @Override
            protected Integer getValue(org.evosuite.testsuite.TestSuiteChromosome individual) {
                return 0;
            }

            @Override
            protected Integer interpolate(Integer v1, Integer v2, double ratio) {
                 return (int) Math.round(v1 + (v2 - v1) * ratio);
            }

            @Override
            protected Integer getZeroValue() {
                return 0;
            }
        };

        // Populate with some data
        // t=0, val=0
        factory.timeStamps.add(0L);
        factory.values.add(0);

        // t=2000, val=10
        factory.timeStamps.add(2000L);
        factory.values.add(10);

        // We want T1 which is at 1000ms.
        // It should interpolate.
        // val(1000) = 0 + (1000 - 0) * (10 - 0)/(2000 - 0) = 0 + 1000 * 10/2000 = 5.0

        List<OutputVariable<Integer>> vars = factory.getOutputVariables();

        // Find the variable for T1
        OutputVariable<Integer> t1 = null;
        for (OutputVariable<Integer> v : vars) {
             if (v.getName().endsWith("_T1")) {
                 t1 = v;
                 break;
             }
        }
        assertNotNull(t1);

        Integer val = t1.getValue();
        assertEquals(Integer.valueOf(5), val);
    }
}
