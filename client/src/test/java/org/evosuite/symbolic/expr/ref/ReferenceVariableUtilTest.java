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
package org.evosuite.symbolic.expr.ref;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ReferenceVariableUtilTest {

    @Test
    public void getReferenceVariableNameGeneratesValidNames() {
        String testName = ReferenceVariableUtil.getReferenceVariableName("test");
        assertTrue(ReferenceVariableUtil.isReferenceVariableName(testName));
    }

    @Test
    public void isReferenceVariableName() {
        // Null should not be valid
        assertFalse(ReferenceVariableUtil.isReferenceVariableName(null));

        // non prefixes should not be valid
        assertFalse(ReferenceVariableUtil.isReferenceVariableName("test"));
        assertFalse(ReferenceVariableUtil.isReferenceVariableName("test_test"));

        // Prefix but not separator should not be valid
        assertFalse(ReferenceVariableUtil.isReferenceVariableName("referencetest"));
        assertFalse(ReferenceVariableUtil.isReferenceVariableName("referencetest_name"));
    }
}