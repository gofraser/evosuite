/*
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
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testcase.synthesizer;

import org.evosuite.Properties;
import org.evosuite.setup.TestUsageChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.AbstractList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A protected method declared in another package than the generated test's cannot be called
 * from the test (JLS 6.6.2), so it must not be accepted as a direct synthesis entry. The cluster's
 * own rule, {@link TestUsageChecker#canUse(Method, Class)}, already rejects it.
 */
public class TargetContextProtectedEntryTest {

    private String classPrefix;

    @BeforeEach
    public void setUp() {
        classPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = "org.example.subject";
    }

    @AfterEach
    public void tearDown() {
        Properties.CLASS_PREFIX = classPrefix;
    }

    @Disabled("D6: isDirectlyAccessibleEntry accepts protected methods of any package"
            + " - see CLEANUP-PLAN.md")
    @Test
    public void protectedMethodOfAnotherPackageIsNotADirectEntry() throws Exception {
        Method removeRange = AbstractList.class.getDeclaredMethod("removeRange", int.class, int.class);
        assertFalse(TestUsageChecker.canUse(removeRange, AbstractList.class),
                "precondition: the cluster rejects the protected method of another package");

        assertEquals(TestUsageChecker.canUse(removeRange, AbstractList.class),
                TargetContext.isDirectlyAccessibleEntry(removeRange, AbstractList.class),
                "the entry rule must agree with the cluster for a protected method of another package");
    }
}
