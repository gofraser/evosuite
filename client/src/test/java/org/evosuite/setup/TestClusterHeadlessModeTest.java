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

import org.evosuite.Properties;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import java.awt.HeadlessException;
import java.util.Collections;

public class TestClusterHeadlessModeTest {

    /** A fake CUT that extends JFrame — used to test that CUT constructors
     *  are NOT filtered when the JFrame mock is available. */
    @SuppressWarnings("serial")
    static class FakeJFrameSubclass extends JFrame {
        public FakeJFrameSubclass() throws HeadlessException {
            super();
        }
    }

    private static final boolean DEFAULT_HEADLESS_MODE = Properties.HEADLESS_MODE;
    private static final boolean DEFAULT_HEADLESS_FILTER_CUT_CALLS = Properties.HEADLESS_FILTER_CUT_CALLS;
    private static final boolean DEFAULT_MOCK_GUI = RuntimeSettings.mockGUI;

    @AfterEach
    public void tearDown() {
        Properties.HEADLESS_MODE = DEFAULT_HEADLESS_MODE;
        Properties.HEADLESS_FILTER_CUT_CALLS = DEFAULT_HEADLESS_FILTER_CUT_CALLS;
        RuntimeSettings.mockGUI = DEFAULT_MOCK_GUI;
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
    public void testWindowConstructorAutoFilteredAsTestCallInHeadlessMode() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        GenericConstructor frameConstructor = new GenericConstructor(JFrame.class.getConstructor(), JFrame.class);

        // Headless-incompatible constructors are auto-filtered regardless of
        // HEADLESS_FILTER_CUT_CALLS, because they always throw HeadlessException.
        Properties.HEADLESS_MODE = true;
        Properties.HEADLESS_FILTER_CUT_CALLS = false;
        cluster.addTestCall(frameConstructor);
        Assertions.assertEquals(0, cluster.getNumTestCalls());
        Assertions.assertTrue(cluster.getTestCalls().isEmpty());
    }

    @Test
    public void testWindowConstructorAllowedWhenNotHeadless() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        GenericConstructor frameConstructor = new GenericConstructor(JFrame.class.getConstructor(), JFrame.class);

        Properties.HEADLESS_MODE = false;
        cluster.addTestCall(frameConstructor);
        Assertions.assertEquals(1, cluster.getNumTestCalls());
        Assertions.assertEquals(1, cluster.getTestCalls().size());
    }

    @Test
    public void testCutExtendingJFrameNotFilteredWhenMockAvailable() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();

        // Enable GUI mocks so MockJFrame is registered
        RuntimeSettings.mockGUI = true;
        Properties.HEADLESS_MODE = true;

        // The CUT's constructor declares HeadlessException but extends JFrame
        // which has a mock — so it should NOT be filtered.
        GenericConstructor cutConstructor = new GenericConstructor(
                FakeJFrameSubclass.class.getConstructor(), FakeJFrameSubclass.class);
        cluster.addTestCall(cutConstructor);
        Assertions.assertEquals(1, cluster.getNumTestCalls(),
                "CUT constructor extending mocked JFrame should not be filtered");
    }

    @Test
    public void testCutExtendingJFrameFilteredWhenMockNotAvailable() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();

        // Disable GUI mocks — no MockJFrame available
        RuntimeSettings.mockGUI = false;
        Properties.HEADLESS_MODE = true;

        GenericConstructor cutConstructor = new GenericConstructor(
                FakeJFrameSubclass.class.getConstructor(), FakeJFrameSubclass.class);
        cluster.addTestCall(cutConstructor);
        Assertions.assertEquals(0, cluster.getNumTestCalls(),
                "CUT constructor should be filtered when no mock available");
    }
}
