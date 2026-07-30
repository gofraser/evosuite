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
package org.evosuite.testcase.synthesizer.var;

import java.io.DataInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VariableCodeGenerationUtilTest {

    @Test
    public void rejectsIndirectStreamInfluenceButKeepsControllableSetters() throws Exception {
        Field value = SetterFixture.class.getDeclaredField("value");
        Method unpack = SetterFixture.class.getMethod("unpack", DataInputStream.class);
        Method direct = SetterFixture.class.getMethod("setValue", int.class);
        Method boxed = SetterFixture.class.getMethod("setBoxedValue", Integer.class);
        Method fixed = SetterFixture.class.getMethod("reset");
        Method fixedWithUnrelatedInput = SetterFixture.class.getMethod(
                "resetWithUnrelatedInput", byte[].class);
        Constructor<SetterFixture> constructor = SetterFixture.class.getConstructor(int.class);

        assertEquals("no_directly_compatible_value_parameter",
                VariableCodeGenerationUtil.candidateRejectionReason(
                        value, unpack, Collections.singleton(0), false));
        assertNull(VariableCodeGenerationUtil.candidateRejectionReason(
                value, direct, Collections.singleton(0), false));
        assertNull(VariableCodeGenerationUtil.candidateRejectionReason(
                value, boxed, Collections.singleton(0), false));
        assertNull(VariableCodeGenerationUtil.candidateRejectionReason(
                value, fixed, Collections.emptySet(), true));
        assertNull(VariableCodeGenerationUtil.candidateRejectionReason(
                value, fixedWithUnrelatedInput, Collections.emptySet(), true));
        assertNull(VariableCodeGenerationUtil.candidateRejectionReason(
                value, constructor, Collections.singleton(0), false));
    }

    @Test
    public void rejectsPlatformImplementationStateAsAnActionTarget() throws Exception {
        Field reflectionData = Class.class.getDeclaredField("reflectionData");

        assertEquals(true, VariableCodeGenerationUtil.isPlatformImplementationState(
                reflectionData, Class.class.getName()));
    }

    @Test
    public void recognizesOnlyUnmodifiedDirectConstructorReceiversAsFresh() throws Exception {
        TestCase test = new DefaultTestCase();
        Constructor<SetterFixture> constructor = SetterFixture.class.getConstructor(int.class);
        VariableReference receiver = TestFactory.getInstance().addConstructor(
                test, new GenericConstructor(constructor, SetterFixture.class), 0, 0);

        assertTrue(VariableCodeGenerationUtil.isFreshlyConstructedReceiver(test, receiver));

        Method setter = SetterFixture.class.getMethod("setValue", int.class);
        TestFactory.getInstance().addMethodFor(test, receiver,
                new GenericMethod(setter, SetterFixture.class), test.size(), false);
        assertFalse(VariableCodeGenerationUtil.isFreshlyConstructedReceiver(test, receiver));
    }

    public static class SetterFixture {
        private int value;

        public SetterFixture(int value) {
            this.value = value;
        }

        public void unpack(DataInputStream input) throws Exception {
            value = input.readInt();
        }

        public void setValue(int value) {
            this.value = value;
        }

        public void setBoxedValue(Integer value) {
            this.value = value;
        }

        public void reset() {
            value = 0;
        }

        public void resetWithUnrelatedInput(byte[] ignored) {
            value = 0;
        }
    }
}
