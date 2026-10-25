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
package org.evosuite.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;

/**
 * The extension gives the system properties a test class reads the values they had at
 * generation time, and puts every property back after each test and after the class.
 */
public class EvoSuiteExtensionSystemPropertiesTest {

    private static final String READ = "evosuite.extension.test.read";
    private static final String WRITTEN = "evosuite.extension.test.written";

    @EvoRunnerParameters
    public static class Annotated_ESTest {
    }

    private java.util.Properties saved;

    @BeforeEach
    public void setUp() {
        saved = (java.util.Properties) java.lang.System.getProperties().clone();
        java.lang.System.setProperty(READ, "machine value");
    }

    @AfterEach
    public void tearDown() {
        java.lang.System.setProperties(saved);
    }

    private static ExtensionContext context(String id) {
        ExtensionContext context = Mockito.mock(ExtensionContext.class);
        Mockito.when(context.getUniqueId()).thenReturn(id);
        return context;
    }

    @Test
    public void readPropertiesAreSetAndEverythingIsRestored() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Annotated_ESTest.class, new String[0],
                new String[]{READ, "generation value"});
        ExtensionContext classContext = context("[class:c]");
        ExtensionContext testContext = context("[class:c]/[test:t]");

        extension.beforeAll(classContext);
        Assertions.assertEquals("generation value", java.lang.System.getProperty(READ));

        extension.beforeEach(testContext);
        Assertions.assertEquals("generation value", java.lang.System.getProperty(READ));
        java.lang.System.setProperty(WRITTEN, "by the test");
        java.lang.System.setProperty(READ, "overwritten by the test");
        extension.afterEach(testContext);

        extension.beforeEach(testContext);
        Assertions.assertNull(java.lang.System.getProperty(WRITTEN));
        Assertions.assertEquals("generation value", java.lang.System.getProperty(READ));
        extension.afterEach(testContext);

        extension.afterAll(classContext);
        Assertions.assertEquals("machine value", java.lang.System.getProperty(READ));
        Assertions.assertNull(java.lang.System.getProperty(WRITTEN));
    }

    @Test
    public void propertiesAreLeftAloneWithoutADeclaration() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Annotated_ESTest.class);
        ExtensionContext classContext = context("[class:c]");
        ExtensionContext testContext = context("[class:c]/[test:t]");

        extension.beforeAll(classContext);
        extension.beforeEach(testContext);
        java.lang.System.setProperty(WRITTEN, "by the test");
        extension.afterEach(testContext);
        extension.afterAll(classContext);

        Assertions.assertEquals("by the test", java.lang.System.getProperty(WRITTEN));
    }
}
