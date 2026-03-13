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
package org.evosuite.setup;

import org.evosuite.Properties;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import java.util.Collections;

public class TestClusterHeadlessModeTest {

    private static final boolean DEFAULT_HEADLESS_MODE = Properties.HEADLESS_MODE;
    private static final boolean DEFAULT_HEADLESS_FILTER_CUT_CALLS = Properties.HEADLESS_FILTER_CUT_CALLS;

    @AfterEach
    public void tearDown() {
        Properties.HEADLESS_MODE = DEFAULT_HEADLESS_MODE;
        Properties.HEADLESS_FILTER_CUT_CALLS = DEFAULT_HEADLESS_FILTER_CUT_CALLS;
        TestCluster.reset();
    }

    @Test
    public void testWindowConstructorFilteredInHeadlessMode() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        GenericClass<?> frameType = GenericClassFactory.get(JFrame.class);
        GenericConstructor frameConstructor = new GenericConstructor(JFrame.class.getConstructor(), JFrame.class);
        cluster.addGenerator(frameType, frameConstructor);

        Properties.HEADLESS_MODE = false;
        GenericAccessibleObject<?> generatorWithoutHeadless = cluster.getRandomGenerator(
                frameType, Collections.emptySet(), null, 0, null, 0);
        Assertions.assertNotNull(generatorWithoutHeadless);

        Properties.HEADLESS_MODE = true;
        GenericAccessibleObject<?> generatorWithHeadless = cluster.getRandomGenerator(
                frameType, Collections.emptySet(), null, 0, null, 0);
        Assertions.assertNull(generatorWithHeadless);
    }

    @Test
    public void testWindowConstructorAddedAsTestCallInHeadlessModeByDefault() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        GenericConstructor frameConstructor = new GenericConstructor(JFrame.class.getConstructor(), JFrame.class);

        Properties.HEADLESS_MODE = true;
        Properties.HEADLESS_FILTER_CUT_CALLS = false;
        cluster.addTestCall(frameConstructor);
        Assertions.assertEquals(1, cluster.getNumTestCalls());
        Assertions.assertEquals(1, cluster.getTestCalls().size());
    }

    @Test
    public void testWindowConstructorFilteredAsTestCallWhenConfigured() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        GenericConstructor frameConstructor = new GenericConstructor(JFrame.class.getConstructor(), JFrame.class);

        Properties.HEADLESS_MODE = true;
        Properties.HEADLESS_FILTER_CUT_CALLS = true;
        cluster.addTestCall(frameConstructor);
        Assertions.assertEquals(0, cluster.getNumTestCalls());
        Assertions.assertTrue(cluster.getTestCalls().isEmpty());
    }
}
