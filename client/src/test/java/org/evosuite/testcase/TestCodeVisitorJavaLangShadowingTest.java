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
package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class whose simple name is that of a java.lang type must not be imported: the suite refers
 * to java.lang types by their simple names (String[] EVO_INIT_ORDER) without asking the visitor.
 */
public class TestCodeVisitorJavaLangShadowingTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUpTargetClass() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = TestCodeVisitorJavaLangShadowingTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void restoreTargetClass() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void classShadowingJavaLangTypeIsRenderedFullyQualified() throws Exception {
        Class<?> shadowing = com.examples.with.different.packagename.shadowing.String.class;
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(shadowing.getConstructor(), shadowing), Collections.emptyList()));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();
        Set<Class<?>> imports = visitor.getImports();

        assertTrue(code.contains("com.examples.with.different.packagename.shadowing.String string0 = "
                        + "new com.examples.with.different.packagename.shadowing.String();"),
                "A class shadowing java.lang.String must be fully qualified:\n" + code);
        assertFalse(imports.contains(shadowing), "A class shadowing java.lang.String must not be imported");
        assertEquals("String", visitor.getClassName(String.class));
        assertEquals("Object", visitor.getClassName(Object.class));
    }
}
