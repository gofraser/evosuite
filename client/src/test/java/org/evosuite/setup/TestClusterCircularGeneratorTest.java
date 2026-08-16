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

import com.examples.with.different.packagename.CircularGeneratorFixture.Holder;
import com.examples.with.different.packagename.CircularGeneratorFixture.Managed;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

/**
 * A type whose only "generators" are a getter and a field on a class that in turn can only be built
 * from that very type is not reachable by generation at all. The cluster has to report that as "no
 * generator", otherwise callers never reach their mocking / variable-reuse fallbacks.
 *
 * <p>This is the shape of {@code org.firebirdsql.jdbc.AbstractConnection}: its only concrete
 * subclass takes an {@code FBManagedConnection}, whose own constructor is not public, so the only
 * two registered generators for {@code FBManagedConnection} are the field and the getter on
 * {@code AbstractConnection} itself.
 */
public class TestClusterCircularGeneratorTest {

    @AfterEach
    public void tearDown() {
        TestCluster.reset();
    }

    private static TestCluster clusterWithCircularGeneratorsOnly() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();

        GenericClass<?> managedType = GenericClassFactory.get(Managed.class);
        GenericClass<?> holderType = GenericClassFactory.get(Holder.class);

        // The only ways to "obtain" a Managed both require a Holder ...
        cluster.addGenerator(managedType,
                new GenericMethod(Holder.class.getMethod("getManaged"), Holder.class));
        cluster.addGenerator(managedType,
                new GenericField(Holder.class.getField("managed"), Holder.class));
        // ... and the only way to obtain a Holder requires a Managed.
        cluster.addGenerator(holderType,
                new GenericConstructor(Holder.class.getConstructor(Managed.class), Holder.class));

        // Guard against the test passing vacuously because nothing was registered at all.
        Assertions.assertTrue(cluster.hasGenerator(managedType), "fixture not registered");
        Assertions.assertTrue(cluster.hasGenerator(holderType), "fixture not registered");
        return cluster;
    }

    @Test
    public void purelyCircularGeneratorsAreReportedAsNoGenerator() throws Exception {
        TestCluster cluster = clusterWithCircularGeneratorsOnly();

        GenericAccessibleObject<?> generator = cluster.getRandomGenerator(
                GenericClassFactory.get(Managed.class), Collections.emptySet(), null, 0, null, 0);

        Assertions.assertNull(generator,
                "A generator set in which every entry needs the target type must be reported as empty, "
                        + "so that mocking / variable reuse can take over");
    }

    @Test
    public void aStaticFactoryBreaksTheCycle() throws Exception {
        TestCluster cluster = clusterWithCircularGeneratorsOnly();
        cluster.addGenerator(GenericClassFactory.get(Managed.class),
                new GenericMethod(Managed.class.getMethod("create"), Managed.class));

        GenericAccessibleObject<?> generator = cluster.getRandomGenerator(
                GenericClassFactory.get(Managed.class), Collections.emptySet(), null, 0, null, 0);

        Assertions.assertNotNull(generator,
                "As soon as one non-circular generator exists, generation must proceed as before");
    }

    @Test
    public void ordinaryGeneratorsAreUnaffected() throws Exception {
        TestCluster cluster = clusterWithCircularGeneratorsOnly();
        cluster.addGenerator(GenericClassFactory.get(Managed.class),
                new GenericMethod(Managed.class.getMethod("create"), Managed.class));

        GenericAccessibleObject<?> generator = cluster.getRandomGenerator(
                GenericClassFactory.get(Holder.class), Collections.emptySet(), null, 0, null, 0);

        Assertions.assertNotNull(generator,
                "A constructor whose parameter is separately obtainable is not circular");
    }
}
