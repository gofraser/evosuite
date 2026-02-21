/*
 * Copyright (C) 2010-2024 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class ExecutionTraceProxyTest {

    @Test
    public void testGetFalseDistancesSum() {
        ExecutionTraceImpl impl = new ExecutionTraceImpl();
        impl.getTrueDistancesSum().put(1, 10.0);
        impl.getFalseDistancesSum().put(1, 20.0);

        ExecutionTraceProxy proxy = new ExecutionTraceProxy(impl);

        Map<Integer, Double> falseDistances = proxy.getFalseDistancesSum();
        Map<Integer, Double> trueDistances = proxy.getTrueDistancesSum();

        Assertions.assertEquals(20.0, falseDistances.get(1), 0.001, "Should return false distances");
        Assertions.assertEquals(10.0, trueDistances.get(1), 0.001, "Should return true distances");
    }
}
