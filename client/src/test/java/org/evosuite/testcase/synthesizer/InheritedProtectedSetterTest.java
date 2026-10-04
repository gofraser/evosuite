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

import com.examples.with.different.packagename.synthprotected.ProtectedInitChild;
import com.examples.with.different.packagename.synthprotected.base.ProtectedInitBase;
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
 * Generated tests live in the class under test's package, so the field-setter search must not
 * offer a protected method that the class inherits from a class in another package: javac
 * answers {@code initialize(...) has protected access in ProtectedInitBase}.
 */
public class InheritedProtectedSetterTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = "";
        Properties.CLASS_PREFIX = "";
        Properties.APPLY_OBJECT_RULE = false;
        TestGenerationContext.getInstance().resetContext();
    }

    @Test
    public void setterSearchSkipsProtectedMethodsInheritedFromAnotherPackage() throws Exception {
        Properties.CLIENT_ON_THREAD = true;
        Properties.TARGET_CLASS = ProtectedInitChild.class.getName();
        Properties.CLASS_PREFIX = ProtectedInitChild.class.getPackage().getName();
        Properties.APPLY_OBJECT_RULE = true;
        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);

        Field items = ProtectedInitBase.class.getDeclaredField("items");
        LinkedHashMap<Executable, ?> setters = DataDependencyUtil.searchForPotentialSettersInClass(
                items, ProtectedInitBase.class.getName());

        boolean hasPublicSetter = false;
        for (Executable e : setters.keySet()) {
            assertFalse(e.getName().equals("initialize"), "protected inherited method offered: " + e);
            assertFalse(e.getName().equals("declareItems"),
                    "package-private method of another package offered: " + e);
            hasPublicSetter |= e.getName().equals("setItems");
        }
        assertTrue(hasPublicSetter, "the public setter must still be found: " + setters.keySet());

        // The excluded call really is rejected by javac from the class-under-test package.
        String source = "package " + Properties.CLASS_PREFIX + ";\n"
                + "import java.util.ArrayList;\n"
                + "public class InheritedProtectedSnippet {\n"
                + "  public void test0() {\n"
                + "    ProtectedInitChild child = new ProtectedInitChild();\n"
                + "    ((" + ProtectedInitBase.class.getName() + ") child).initialize(new ArrayList<String>());\n"
                + "  }\n}\n";
        JavacScratchCompiler.Result compiled = JavacScratchCompiler.compile(
                "evosuite-inherited-protected", "InheritedProtectedSnippet", source);
        assertFalse(compiled.success, source);
        assertTrue(compiled.stderr.contains("protected access"), compiled.stderr);
    }
}
