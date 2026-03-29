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
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.Optional;

public class DmonCoordinatorTest {

    public static class CacheCtorTarget {
        public CacheCtorTarget() {
            throw new NullPointerException("Cannot read field \"dep\" because \"this.dep\" is null");
        }
    }

    @Test
    public void cacheReturnsSameOptionalInstanceForSameFailureSignature() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldCache = Properties.DMON_CACHE_ANALYSIS;
        boolean oldOnlyTarget = Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR;
        String oldTarget = Properties.TARGET_CLASS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_CACHE_ANALYSIS = true;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = true;
            Properties.TARGET_CLASS = CacheCtorTarget.class.getCanonicalName();

            DefaultTestCase test = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    CacheCtorTarget.class.getDeclaredConstructor(), CacheCtorTarget.class);
            ConstructorStatement statement = new ConstructorStatement(test, gc, Collections.emptyList());

            NullPointerException npe = new NullPointerException(
                    "Cannot read field \"dep\" because \"this.dep\" is null");
            npe.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement(CacheCtorTarget.class.getName(), "<init>", "CacheCtorTarget.java", 12)
            });

            Optional<DmonPromotionPlan> first = DmonCoordinator.getInstance()
                    .analyzeConstructorFailure(statement, npe);
            Optional<DmonPromotionPlan> second = DmonCoordinator.getInstance()
                    .analyzeConstructorFailure(statement, npe);

            Assert.assertTrue(first.isPresent());
            Assert.assertSame("Cached lookup should return exact Optional instance", first, second);
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_CACHE_ANALYSIS = oldCache;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = oldOnlyTarget;
            Properties.TARGET_CLASS = oldTarget;
        }
    }
}
