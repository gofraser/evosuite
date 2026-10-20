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
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
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

    @SuppressWarnings("serial")
    static class FakeJPanelSubclass extends JPanel {
        public FakeJPanelSubclass() throws HeadlessException {
            super();
        }
    }

    private static final boolean DEFAULT_HEADLESS_MODE = Properties.HEADLESS_MODE;
    private static final boolean DEFAULT_HEADLESS_FILTER_CUT_CALLS = Properties.HEADLESS_FILTER_CUT_CALLS;
    private static final boolean DEFAULT_MOCK_GUI = RuntimeSettings.mockGUI;
    private static final boolean DEFAULT_FILTER_JDK_GUI = Properties.HEADLESS_FILTER_JDK_GUI_GENERATORS;
    private static final boolean DEFAULT_FILTER_JDK_GUI_CAST = Properties.HEADLESS_FILTER_JDK_GUI_CAST_GENERATORS;
    private static final boolean DEFAULT_ALLOW_JDK_GUI_WRAPPERS =
            Properties.HEADLESS_ALLOW_JDK_GUI_WRAPPING_CONSTRUCTORS;

    @AfterEach
    public void tearDown() {
        Properties.HEADLESS_MODE = DEFAULT_HEADLESS_MODE;
        Properties.HEADLESS_FILTER_CUT_CALLS = DEFAULT_HEADLESS_FILTER_CUT_CALLS;
        RuntimeSettings.mockGUI = DEFAULT_MOCK_GUI;
        Properties.HEADLESS_FILTER_JDK_GUI_GENERATORS = DEFAULT_FILTER_JDK_GUI;
        Properties.HEADLESS_FILTER_JDK_GUI_CAST_GENERATORS = DEFAULT_FILTER_JDK_GUI_CAST;
        Properties.HEADLESS_ALLOW_JDK_GUI_WRAPPING_CONSTRUCTORS = DEFAULT_ALLOW_JDK_GUI_WRAPPERS;
        TestCluster.reset();
    }

    @Test
    public void testOperatingSystemDependentDefaultsComeFromOneDecision() {
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        Assertions.assertEquals(mac, DEFAULT_FILTER_JDK_GUI_CAST);
        Assertions.assertEquals(!mac, DEFAULT_ALLOW_JDK_GUI_WRAPPERS);
    }

    @Test
    public void testSwingGeneratorFilteredForParametersByDefault() throws Exception {
        Assertions.assertTrue(DEFAULT_FILTER_JDK_GUI,
                "a JTable parameter must be served by a mock, not by a default real JTable");
    }

    @Test
    public void testSwingGeneratorForCastClassFilteredUnlessOptedIn() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        GenericClass<?> comboType = GenericClassFactory.get(javax.swing.JComboBox.class);
        GenericConstructor comboConstructor = new GenericConstructor(
                javax.swing.JComboBox.class.getConstructor(), javax.swing.JComboBox.class);
        cluster.addGenerator(comboType, comboConstructor);
        Properties.HEADLESS_MODE = true;
        Properties.HEADLESS_FILTER_JDK_GUI_GENERATORS = true;

        Properties.HEADLESS_FILTER_JDK_GUI_CAST_GENERATORS = true;
        Assertions.assertThrows(org.evosuite.ga.ConstructionFailedException.class,
                () -> cluster.getRandomGenerator(comboType));

        Properties.HEADLESS_FILTER_JDK_GUI_CAST_GENERATORS = false;
        Assertions.assertNotNull(cluster.getRandomGenerator(comboType));
    }

    @Test
    public void testLightweightSwingGeneratorFilteredUnlessOptedIn() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        GenericClass<?> comboType = GenericClassFactory.get(javax.swing.JComboBox.class);
        GenericConstructor comboConstructor = new GenericConstructor(
                javax.swing.JComboBox.class.getConstructor(), javax.swing.JComboBox.class);
        cluster.addGenerator(comboType, comboConstructor);
        Properties.HEADLESS_MODE = true;

        Properties.HEADLESS_FILTER_JDK_GUI_GENERATORS = true;
        Assertions.assertNull(cluster.getRandomGenerator(
                comboType, Collections.emptySet(), null, 0, null, 0));

        Properties.HEADLESS_FILTER_JDK_GUI_GENERATORS = false;
        Assertions.assertNotNull(cluster.getRandomGenerator(
                comboType, Collections.emptySet(), null, 0, null, 0));
    }

    @Test
    public void testSwingConstructorWrappingAComponentKeptUnlessOptedOut() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        GenericClass<?> componentType = GenericClassFactory.get(java.awt.Component.class);
        cluster.addGenerator(componentType, new GenericConstructor(
                javax.swing.JScrollPane.class.getConstructor(java.awt.Component.class),
                javax.swing.JScrollPane.class));
        Properties.HEADLESS_MODE = true;
        Properties.HEADLESS_FILTER_JDK_GUI_GENERATORS = true;

        Properties.HEADLESS_ALLOW_JDK_GUI_WRAPPING_CONSTRUCTORS = true;
        Assertions.assertNotNull(cluster.getRandomGenerator(
                componentType, Collections.emptySet(), null, 0, null, 0));

        Properties.HEADLESS_ALLOW_JDK_GUI_WRAPPING_CONSTRUCTORS = false;
        Assertions.assertNull(cluster.getRandomGenerator(
                componentType, Collections.emptySet(), null, 0, null, 0));
    }

    @Test
    public void testSwingConstructorWithoutAComponentStaysFiltered() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        GenericClass<?> tableType = GenericClassFactory.get(javax.swing.JTable.class);
        cluster.addGenerator(tableType, new GenericConstructor(
                javax.swing.JTable.class.getConstructor(int.class, int.class), javax.swing.JTable.class));
        Properties.HEADLESS_MODE = true;
        Properties.HEADLESS_FILTER_JDK_GUI_GENERATORS = true;
        Properties.HEADLESS_ALLOW_JDK_GUI_WRAPPING_CONSTRUCTORS = true;

        Assertions.assertNull(cluster.getRandomGenerator(
                tableType, Collections.emptySet(), null, 0, null, 0));
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

    @Test
    public void testNonJdkCutDeclaringHeadlessExceptionIsNotAutoFiltered() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();

        RuntimeSettings.mockGUI = false;
        Properties.HEADLESS_MODE = true;
        Properties.HEADLESS_FILTER_CUT_CALLS = true;

        GenericConstructor cutConstructor = new GenericConstructor(
                FakeJPanelSubclass.class.getConstructor(), FakeJPanelSubclass.class);
        cluster.addTestCall(cutConstructor);
        Assertions.assertEquals(1, cluster.getNumTestCalls(),
                "Non-JDK CUT constructor should not be filtered only due to declared HeadlessException");
    }

    @Test
    public void testFallsBackToBaselineCallsWhenActiveCallsExhausted() throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        Properties.HEADLESS_MODE = false;

        GenericConstructor objectConstructor =
                new GenericConstructor(Object.class.getConstructor(), Object.class);
        cluster.addTestCall(objectConstructor);
        cluster.removeTestCall(objectConstructor);

        Assertions.assertEquals(0, cluster.getNumTestCalls(),
                "Active test calls should be empty after explicit removal");

        GenericAccessibleObject<?> recovered = cluster.getRandomTestCall(new DefaultTestCase());
        Assertions.assertNotNull(recovered,
                "Cluster should recover from baseline discovered calls instead of returning null");
    }
}
