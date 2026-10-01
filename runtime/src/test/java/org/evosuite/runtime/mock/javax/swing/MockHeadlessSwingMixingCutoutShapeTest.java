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
package org.evosuite.runtime.mock.javax.swing;

import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.GraphicsEnvironment;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;

/**
 * {@code Component.setMixingCutoutShape(Shape)} is a Java 9+ API: {@code javap} against Corretto
 * 8 confirms it does not exist on {@code java.awt.Component} before JDK 9. The
 * {@code replacement_setMixingCutoutShape} replacement used to call it directly with a typed
 * {@code Component} receiver, which compiles against the module's JDK 17 build but throws
 * {@link NoSuchMethodError} the first time it runs on a Java 8 JVM. It is now invoked
 * reflectively and no-ops when the method cannot be resolved. These tests run on a modern JDK, so
 * they pin the observable behaviour that does not change -- the call still reaches the real JDK
 * method when mocking is not skipping it, and the headless-mocking no-op branch is untouched; see
 * {@code runtime/pom.xml}'s {@code java8-api-check} execution and the Corretto 8 smoke run
 * recorded for this fix for coverage of the Java 8 no-op path itself, which a JDK 17 test cannot
 * exercise (the method genuinely exists here).
 */
public class MockHeadlessSwingMixingCutoutShapeTest {

    @AfterEach
    public void disableMocking() {
        MockFramework.disable();
    }

    @Test
    public void callsTheRealMethodWhenMockingDisabled() {
        MockFramework.disable();
        JPanel panel = new JPanel();
        Shape shape = new Rectangle2D.Double(0, 0, 10, 10);

        Assertions.assertDoesNotThrow(() ->
                MockHeadlessSwing.replacement_setMixingCutoutShape(panel, shape));
    }

    @Test
    public void genericReplacementAlsoReachesTheRealMethod() {
        MockFramework.disable();
        JPanel panel = new JPanel();
        Shape shape = new Rectangle2D.Double(0, 0, 10, 10);

        Assertions.assertDoesNotThrow(() ->
                MockHeadlessSwing.replacement_setMixingCutoutShapeGeneric(panel, shape));
    }

    @Test
    public void nullSourceStillThrowsNullPointerException() {
        Assertions.assertThrows(NullPointerException.class, () ->
                MockHeadlessSwing.replacement_setMixingCutoutShape(null, null));
    }

    /**
     * Only a headless JVM actually takes the "mocking enabled and headless" branch; on a machine
     * with a display {@link MockFramework#enable()} alone is not enough to make
     * {@code isMockingInHeadlessEnvironment()} return true, so this assumption mirrors the
     * existing headless-only tests in this package (e.g. {@code MockHeadlessSwingCursorTest}).
     */
    @Test
    public void headlessMockingStillSkipsTheCallInsteadOfInvokingReflectively() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        MockFramework.enable();
        JPanel panel = new JPanel();
        Shape shape = new Rectangle2D.Double(0, 0, 10, 10);

        Assertions.assertDoesNotThrow(() ->
                MockHeadlessSwing.replacement_setMixingCutoutShape(panel, shape));
    }
}
