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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.examples.with.different.packagename.synthobject.ObjectOverrideFixture;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class that nulls a field in a public finalize() or clone() override used to be offered, by the
 * field-setter search, once as its own method and once as the protected java.lang.Object method of
 * the same signature (the search walks the superclass chain with getDeclaredMethods()). The Object
 * entry was rendered as {@code ((Object) x).finalize()}, which javac rejects with 'has protected
 * access in java.lang.Object'.
 */
public class ObjectMethodSetterTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = "";
        Properties.CLASS_PREFIX = "";
        Properties.APPLY_OBJECT_RULE = false;
        TestGenerationContext.getInstance().resetContext();
    }

    @Test
    public void setterSearchDoesNotOfferProtectedObjectMethods() throws Exception {
        Properties.CLIENT_ON_THREAD = true;
        Properties.TARGET_CLASS = ObjectOverrideFixture.class.getName();
        Properties.CLASS_PREFIX = ObjectOverrideFixture.class.getPackage().getName();
        Properties.APPLY_OBJECT_RULE = true;
        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);

        Field data = ObjectOverrideFixture.class.getDeclaredField("data");
        LinkedHashMap<Executable, ?> setters = DataDependencyUtil.searchForPotentialSettersInClass(
                data, ObjectOverrideFixture.class.getName());

        boolean ownOverrideFound = false;
        for (Executable e : setters.keySet()) {
            assertFalse(e.getDeclaringClass() == Object.class, "Object method offered: " + e);
            ownOverrideFound |= e.getName().equals("finalize") || e.getName().equals("clone");
        }
        assertTrue(ownOverrideFound, "the class's own override must still be found: " + setters.keySet());

        // The call the Object entry was rendered as is rejected by javac.
        String source = "package " + Properties.CLASS_PREFIX + ";\n"
                + "public class ObjectCastSnippet {\n"
                + "  public void test0() throws Throwable {\n"
                + "    ObjectOverrideFixture f = new ObjectOverrideFixture();\n"
                + "    ((Object)f).finalize();\n"
                + "  }\n}\n";
        JavacScratchCompiler.Result compiled = JavacScratchCompiler.compile(
                "evosuite-object-cast", "ObjectCastSnippet", source);
        assertFalse(compiled.success, source);
        assertTrue(compiled.stderr.contains("protected access"), compiled.stderr);
    }
}
