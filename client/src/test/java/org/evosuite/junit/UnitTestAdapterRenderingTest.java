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
package org.evosuite.junit;

import org.evosuite.Properties;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the exact text the JUnit 3 and JUnit 4 adapters contribute to a generated suite.
 */
public class UnitTestAdapterRenderingTest {

    private static final String NFR = "  @org.junit.Rule\n"
            + "  public org.evosuite.runtime.vnet.NonFunctionalRequirementRule nfr = "
            + "new org.evosuite.runtime.vnet.NonFunctionalRequirementRule();\n\n";

    private String targetClass;
    private boolean eclipse;
    private int timeout;

    private void remember() {
        targetClass = Properties.TARGET_CLASS;
        eclipse = Properties.ECLIPSE_PLUGIN;
        timeout = Properties.TIMEOUT;
    }

    @After
    public void restore() {
        if (targetClass != null) {
            Properties.TARGET_CLASS = targetClass;
            Properties.ECLIPSE_PLUGIN = eclipse;
            Properties.TIMEOUT = timeout;
        }
    }

    @Test
    public void junit3RendersPlainTestCase() {
        remember();
        Properties.TARGET_CLASS = "Foo";
        Properties.ECLIPSE_PLUGIN = false;
        UnitTestAdapter adapter = new JUnit3TestAdapter();
        assertEquals("import junit.framework.TestCase;\n", adapter.getImports());
        assertEquals("public class Foo_ESTest extends TestCase", adapter.getClassDefinition("Foo_ESTest"));
        assertEquals("public void test00() ", adapter.getMethodDefinition("test00"));
        StringBuilder sb = new StringBuilder();
        adapter.addNFR(sb);
        assertEquals(NFR, sb.toString());
        assertEquals(org.junit.Test.class, adapter.testAnnotation());
        assertEquals(org.junit.BeforeClass.class, adapter.beforeAll());
        assertEquals(org.junit.Before.class, adapter.beforeEach());
        assertEquals(org.junit.AfterClass.class, adapter.afterAll());
        assertEquals(org.junit.After.class, adapter.afterEach());
    }

    @Test
    public void junit4RendersAnnotatedMethods() {
        remember();
        Properties.TARGET_CLASS = "Foo";
        Properties.ECLIPSE_PLUGIN = false;
        Properties.TIMEOUT = 4000;
        UnitTestAdapter adapter = new JUnit4TestAdapter();
        assertEquals("import org.junit.Test;\nimport static org.junit.Assert.*;\n", adapter.getImports());
        assertEquals("public class Foo_ESTest", adapter.getClassDefinition("Foo_ESTest"));
        assertEquals("  @Test(timeout = 4000)\n  public void test00() ", adapter.getMethodDefinition("test00"));
        StringBuilder sb = new StringBuilder();
        adapter.addNFR(sb);
        assertEquals(NFR, sb.toString());
    }
}
