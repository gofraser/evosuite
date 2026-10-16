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
package org.evosuite.setup;

import com.examples.with.different.packagename.PrivateParameterTypeFixture;
import com.sun.evosuitefixture.LibraryValue;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cluster's eligibility rules are written several times and have drifted: a rule added to
 * one copy does not reach the others. Each test pins a case where two copies disagree.
 */
public class MemberEligibilityDriftTest {

    @Test
    public void methodTakingASourcePrivateTypeIsNotCallable() throws Exception {
        Method use = PrivateParameterTypeFixture.class.getMethod(
                "use", PrivateParameterTypeFixture.secretType());
        assertTrue(TestUsageChecker.isNotCallableFromSource(use));
    }

    @Test
    public void publicConstructorTakingASourcePrivateTypeIsNotUsable() throws Exception {
        Constructor<?> constructor = PrivateParameterTypeFixture.class.getConstructor(
                PrivateParameterTypeFixture.secretType());
        assertFalse(TestUsageChecker.canUse(constructor),
                "a test cannot name the private Secret type, so it cannot call this constructor"
                        + " either");
    }

    @Test
    public void classpathLibraryUnderComSunIsUsable() {
        assertTrue(TestUsageChecker.canUse(LibraryValue.class),
                "precondition: canUse admits a com.sun class from the classpath");
    }

    @Test
    public void classpathLibraryUnderComSunIsAddedAsADependency() {
        Set<DependencyPair> dependencies = new LinkedHashSet<>();
        MemberAnalyzer analyzer = new MemberAnalyzer(TestCluster.getInstance(), new HashSet<>(),
                dependencies, new HashSet<>(), new HashSet<>(), new InheritanceTree());

        analyzer.addDependency(GenericClassFactory.get(LibraryValue.class), 1);

        assertTrue(dependencies.contains(new DependencyPair(1, LibraryValue.class)),
                "addDependency must apply the same com.sun library exemption canUse applies,"
                        + " but recorded " + dependencies);
    }
}
