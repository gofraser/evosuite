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
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Optional;

public class DmonInjectionDiscoveryTest {

    public static class OwnerWithMultipleSetters {
        static Runnable dep;

        public static void setDep(Runnable r) {
            dep = r;
        }

        public static void registerDep(Runnable r) {
            dep = r;
        }
    }

    public static class TargetCallsRegister {
        public void touch() {
            OwnerWithMultipleSetters.registerDep(null);
        }
    }

    @Test
    public void resolveFieldOwnerClassNameExtractsQualifiedPrefix() {
        DmonPromotionPlan plan = new DmonPromotionPlan(
                null,
                Optional.of("jaw.Main.janelaPrincipal"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Assert.assertEquals("jaw.Main", DmonInjectionDiscovery.resolveFieldOwnerClassName(plan));
    }

    @Test
    public void resolveFieldOwnerClassNameReturnsNullForSimpleName() {
        DmonPromotionPlan plan = new DmonPromotionPlan(
                null,
                Optional.of("janelaPrincipal"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Assert.assertNull(DmonInjectionDiscovery.resolveFieldOwnerClassName(plan));
    }

    @Test
    public void resolveFieldOwnerClassNameReturnsNullForNullPlan() {
        Assert.assertNull(DmonInjectionDiscovery.resolveFieldOwnerClassName(null));
    }

    @Test
    public void resolveFieldOwnerClassNameReturnsNullForNoExpression() {
        DmonPromotionPlan plan = new DmonPromotionPlan(
                null,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Assert.assertNull(DmonInjectionDiscovery.resolveFieldOwnerClassName(plan));
    }

    @Test
    public void resolveFieldOwnerClassNameHandlesUnqualifiedClassName() {
        // e.g., "Main.field" — Main starts with uppercase so it's treated as a class
        DmonPromotionPlan plan = new DmonPromotionPlan(
                null,
                Optional.of("Main.janelaPrincipal"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Assert.assertEquals("Main", DmonInjectionDiscovery.resolveFieldOwnerClassName(plan));
    }

    @Test
    public void usageSignalPrefersSetterInvokedFromTargetClass() {
        String oldTarget = Properties.TARGET_CLASS;
        try {
            Properties.TARGET_CLASS = TargetCallsRegister.class.getName();
            Method selected = DmonInjectionDiscovery.findStaticSetter(
                    OwnerWithMultipleSetters.class, Runnable.class, "dep");

            Assert.assertNotNull(selected);
            Assert.assertEquals("registerDep", selected.getName());
        } finally {
            Properties.TARGET_CLASS = oldTarget;
        }
    }
}
