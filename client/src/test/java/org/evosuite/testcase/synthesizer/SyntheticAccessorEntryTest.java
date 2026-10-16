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

import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.SyntheticAccessorFixture;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class SyntheticAccessorEntryTest {

    @Test
    public void publicSyntheticAccessorIsNotADirectEntry() throws Exception {
        Method stub = SyntheticAccessorFixture.define();
        assertFalse(TargetContext.isDirectlyAccessibleEntry(stub, stub.getDeclaringClass()));
    }

    @Test
    public void clusterRefusesSyntheticAccessor() throws Exception {
        Method stub = SyntheticAccessorFixture.define();
        GenericMethod call = new GenericMethod(stub, stub.getDeclaringClass());
        TestCluster cluster = TestCluster.getInstance();
        cluster.addTestCall(call);
        cluster.addModifier(GenericClassFactory.get(stub.getDeclaringClass()), call);
        cluster.addGenerator(GenericClassFactory.get(int.class), call);
        assertFalse(cluster.getTestCalls().contains(call));
        assertFalse(cluster.getModifiers().contains(call));
        assertFalse(cluster.getGenerators().contains(call));
    }
}
