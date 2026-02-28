/**
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.statistics.DirectSequenceOutputVariableFactory;
import org.evosuite.statistics.RuntimeVariable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that DiversityTimeline values are compatible with the
 * {@link DirectSequenceOutputVariableFactory} registered in SearchStatistics.
 *
 * <p>Regression test for Phase 8: MOSA/DynaMOSA must emit per-generation Double
 * values (not semicolon-separated Strings) for DiversityTimeline.
 */
class MOSATimelineEmissionTest {

    /**
     * DirectSequenceOutputVariableFactory<Double> must accept Double values.
     * This is the type emitted per-generation by the fixed MOSA/DynaMOSA code.
     */
    @Test
    void diversityTimelineAcceptsDoubleValues() {
        DirectSequenceOutputVariableFactory<Double> factory =
                DirectSequenceOutputVariableFactory.getDouble(RuntimeVariable.DiversityTimeline);
        // Simulate per-generation Double emission (the fixed behavior)
        assertDoesNotThrow(() -> factory.setValue((Object) 0.75));
        assertEquals(0.75, factory.getValue(null), 1e-9);
    }

    /**
     * DirectSequenceOutputVariableFactory<Double> must reject String values.
     * This catches the old bug where MOSA/DynaMOSA emitted a semicolon-separated String.
     */
    @Test
    void diversityTimelineRejectsStringValues() {
        DirectSequenceOutputVariableFactory<Double> factory =
                DirectSequenceOutputVariableFactory.getDouble(RuntimeVariable.DiversityTimeline);
        // The old buggy emission: semicolon-separated string
        assertThrows(IllegalArgumentException.class,
                () -> factory.setValue((Object) "0.5000;0.6000;0.7000"),
                "String values must be rejected by DirectSequenceOutputVariableFactory<Double>");
    }

    /**
     * Verify that multiple per-generation Double updates work correctly.
     */
    @Test
    void diversityTimelineAcceptsMultipleDoubleUpdates() {
        DirectSequenceOutputVariableFactory<Double> factory =
                DirectSequenceOutputVariableFactory.getDouble(RuntimeVariable.DiversityTimeline);
        double[] generations = {0.1, 0.35, 0.72, 0.5};
        for (double v : generations) {
            factory.setValue((Object) v);
            assertEquals(v, factory.getValue(null), 1e-9);
        }
    }

    /**
     * Fronts/Goals timeline variables are now proper sequence factories.
     * They must accept Integer values.
     */
    @Test
    void frontsCountTimelineAcceptsIntegerValues() {
        DirectSequenceOutputVariableFactory<Integer> factory =
                DirectSequenceOutputVariableFactory.getInteger(RuntimeVariable.Fronts_Count_Timeline);
        assertDoesNotThrow(() -> factory.setValue((Object) 5));
        assertEquals(5, factory.getValue(null));
    }

    @Test
    void remainingGoalsTimelineAcceptsIntegerValues() {
        DirectSequenceOutputVariableFactory<Integer> factory =
                DirectSequenceOutputVariableFactory.getInteger(RuntimeVariable.Remaining_Goals_Timeline);
        assertDoesNotThrow(() -> factory.setValue((Object) 42));
        assertEquals(42, factory.getValue(null));
    }

    @Test
    void coveredGoalsTimelineAcceptsIntegerValues() {
        DirectSequenceOutputVariableFactory<Integer> factory =
                DirectSequenceOutputVariableFactory.getInteger(RuntimeVariable.Covered_Goals_Timeline);
        assertDoesNotThrow(() -> factory.setValue((Object) 10));
        assertEquals(10, factory.getValue(null));
    }

    /**
     * Fronts/Goals timeline factories must reject String values (the old bug pattern).
     */
    @Test
    void frontsCountTimelineRejectsStringValues() {
        DirectSequenceOutputVariableFactory<Integer> factory =
                DirectSequenceOutputVariableFactory.getInteger(RuntimeVariable.Fronts_Count_Timeline);
        assertThrows(IllegalArgumentException.class,
                () -> factory.setValue((Object) "[3, 5, 2, 4]"),
                "String values must be rejected by DirectSequenceOutputVariableFactory<Integer>");
    }
}
