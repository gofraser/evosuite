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

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.examples.with.different.packagename.synthobject.NestedReturnSetterFixture;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.setup.DependencyAnalysis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The field-setter search looks a setter up in the bytecode by name and descriptor, so the
 * descriptor must be the JVM's. Built from the canonical name, a nested-class return type came
 * out as {@code LOuter/Handle;} instead of {@code LOuter$Handle;}, and an object-array return
 * type as {@code Ljava/lang/String[];} instead of {@code [Ljava/lang/String;}, so a setter
 * returning either was never found.
 */
public class ReflectionUtilTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = "";
        Properties.CLASS_PREFIX = "";
        Properties.APPLY_OBJECT_RULE = false;
        TestGenerationContext.getInstance().resetContext();
    }

    @Test
    public void signatureIsTheJvmDescriptor() throws Exception {
        for (Method m : NestedReturnSetterFixture.class.getDeclaredMethods()) {
            assertEquals(Type.getMethodDescriptor(m), ReflectionUtil.getSignature(m), m.toString());
        }
        assertEquals(Type.getConstructorDescriptor(NestedReturnSetterFixture.class.getConstructor()),
                ReflectionUtil.getSignature(NestedReturnSetterFixture.class.getConstructor()));
    }

    @Test
    public void setterSearchFindsSettersWithNestedOrArrayReturnTypes() throws Exception {
        Properties.CLIENT_ON_THREAD = true;
        Properties.TARGET_CLASS = NestedReturnSetterFixture.class.getName();
        Properties.CLASS_PREFIX = NestedReturnSetterFixture.class.getPackage().getName();
        Properties.APPLY_OBJECT_RULE = true;
        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);

        assertSetterFound("plain", "setPlain");
        assertSetterFound("nested", "setNested");
        assertSetterFound("array", "setArray");
    }

    private static void assertSetterFound(String fieldName, String setterName) throws Exception {
        Field field = NestedReturnSetterFixture.class.getDeclaredField(fieldName);
        LinkedHashMap<Executable, ?> setters = DataDependencyUtil.searchForPotentialSettersInClass(
                field, NestedReturnSetterFixture.class.getName());
        boolean found = false;
        for (Executable e : setters.keySet()) {
            found |= e.getName().equals(setterName);
        }
        assertTrue(found, setterName + " not found as a setter of " + fieldName + ": " + setters.keySet());
    }
}
