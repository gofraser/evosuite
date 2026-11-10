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
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.Optional;

public class DmonCoordinatorTest {

    @Test
    public void promotionPlanSurvivesSerialization() throws Exception {
        DmonFailureSite site = new DmonFailureSite("example.Target", "run", 12,
                DmonFailureSiteKind.METHOD_INVOCATION);
        DmonPromotionPlan plan = new DmonPromotionPlan(site, Optional.of("this.dep"),
                Optional.empty(), Optional.of("dep"), null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(plan);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            DmonPromotionPlan restored = (DmonPromotionPlan) in.readObject();
            Assert.assertEquals(plan, restored);
            Assert.assertEquals(Optional.of("this.dep"), restored.getNullExpression());
            Assert.assertEquals(Optional.empty(), restored.getInferredMissingTypeName());
        }
    }

    public static class CacheCtorTarget {
        public CacheCtorTarget() {
            throw new NullPointerException("Cannot read field \"dep\" because \"this.dep\" is null");
        }
    }

    public static class CacheMethodTarget {
        public static void trigger() {
            throw new NullPointerException("Cannot read field \"dep\" because \"this.dep\" is null");
        }
    }

    @Test
    public void cacheReturnsSameOptionalInstanceForSameFailureSignature() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldCache = Properties.DMON_CACHE_ANALYSIS;
        boolean oldOnlyTarget = Properties.DMON_ONLY_TARGET_CLASS;
        String oldTarget = Properties.TARGET_CLASS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_CACHE_ANALYSIS = true;
            Properties.DMON_ONLY_TARGET_CLASS = true;
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
            Properties.DMON_ONLY_TARGET_CLASS = oldOnlyTarget;
            Properties.TARGET_CLASS = oldTarget;
        }
    }

    @Test
    public void cacheReturnsSameOptionalInstanceForSameMethodFailureSignature() throws Exception {
        // Analogue of cacheReturnsSameOptionalInstanceForSameFailureSignature() for
        // analyzeMethodFailure(), which did not exist before the method-invocation
        // generalisation of DMoN.
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldCache = Properties.DMON_CACHE_ANALYSIS;
        boolean oldOnlyTarget = Properties.DMON_ONLY_TARGET_CLASS;
        String oldTarget = Properties.TARGET_CLASS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_CACHE_ANALYSIS = true;
            Properties.DMON_ONLY_TARGET_CLASS = true;
            Properties.TARGET_CLASS = CacheMethodTarget.class.getCanonicalName();

            DefaultTestCase test = new DefaultTestCase();
            GenericMethod gm = new GenericMethod(
                    CacheMethodTarget.class.getMethod("trigger"), CacheMethodTarget.class);
            MethodStatement statement = new MethodStatement(test, gm, null, Collections.emptyList());

            NullPointerException npe = new NullPointerException(
                    "Cannot read field \"dep\" because \"this.dep\" is null");
            npe.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement(CacheMethodTarget.class.getName(), "trigger", "CacheMethodTarget.java", 12)
            });

            Optional<DmonPromotionPlan> first = DmonCoordinator.getInstance()
                    .analyzeMethodFailure(statement, npe);
            Optional<DmonPromotionPlan> second = DmonCoordinator.getInstance()
                    .analyzeMethodFailure(statement, npe);

            Assert.assertTrue(first.isPresent());
            Assert.assertEquals(DmonFailureSiteKind.METHOD_INVOCATION, first.get().getFailureSite().getKind());
            Assert.assertSame("Cached lookup should return exact Optional instance", first, second);
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_CACHE_ANALYSIS = oldCache;
            Properties.DMON_ONLY_TARGET_CLASS = oldOnlyTarget;
            Properties.TARGET_CLASS = oldTarget;
        }
    }

    @Test
    public void suppressionsNestAndLeaveTheConfigurationAlone() {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_PROMOTE_IN_PLACE = true;

            try (DmonCoordinator.Suppression outer = DmonCoordinator.suppressDmon()) {
                try (DmonCoordinator.Suppression inner = DmonCoordinator.suppressDmon()) {
                    Assert.assertFalse(DmonCoordinator.isEnabled());
                }
                Assert.assertFalse(DmonCoordinator.isEnabled());
                Assert.assertTrue(DmonCoordinator.isPromotionEnabled());
            }
            Assert.assertTrue(DmonCoordinator.isEnabled());

            try (DmonCoordinator.Suppression promotion = DmonCoordinator.suppressPromotion()) {
                Assert.assertTrue(DmonCoordinator.isEnabled());
                Assert.assertFalse(DmonCoordinator.isPromotionEnabled());
            }
            Assert.assertTrue(DmonCoordinator.isPromotionEnabled());
            Assert.assertTrue(Properties.DMON_ENABLED);
            Assert.assertTrue(Properties.DMON_PROMOTE_IN_PLACE);
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
        }
    }
}
