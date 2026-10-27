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
package org.evosuite.assertion;

import org.evosuite.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Pins the exact assertion text rendered for each canonical assertion kind and test format.
 */
public class CanonicalAssertionRendererTest {

    private Properties.OutputFormat savedFormat;

    @Before
    public void rememberFormat() {
        savedFormat = Properties.TEST_FORMAT;
    }

    @After
    public void restoreFormat() {
        Properties.TEST_FORMAT = savedFormat;
    }

    private static String render(TemplateAssertionKind kind, String delta, boolean arrays) {
        return CanonicalAssertionRenderer.forConfiguredFormat().render(kind, "e", "a", delta, arrays);
    }

    @Test
    public void sharedKindsRenderTheSameInEveryFormat() {
        for (Properties.OutputFormat format : Properties.OutputFormat.values()) {
            Properties.TEST_FORMAT = format;
            assertEquals("assertEquals(e, a);", render(TemplateAssertionKind.EQUALS, null, false));
            assertEquals("assertEquals(e, a, d);", render(TemplateAssertionKind.EQUALS, "d", false));
            assertEquals("assertTrue(a);", render(TemplateAssertionKind.TRUE, null, false));
            assertEquals("assertFalse(a);", render(TemplateAssertionKind.FALSE, null, false));
            assertEquals("assertNull(a);", render(TemplateAssertionKind.NULL, null, false));
            assertEquals("assertNotNull(a);", render(TemplateAssertionKind.NOT_NULL, null, false));
            assertEquals("assertSame(e, a);", render(TemplateAssertionKind.SAME, null, false));
            assertEquals("assertNotSame(e, a);", render(TemplateAssertionKind.NOT_SAME, null, false));
            assertEquals("assertTrue(a.contains(e));", render(TemplateAssertionKind.CONTAINS, null, false));
            assertEquals("assertFalse(a.contains(e));", render(TemplateAssertionKind.NOT_CONTAINS, null, false));
            assertEquals("assertEquals(e, a.size());", render(TemplateAssertionKind.SIZE_EQUALS, null, false));
            assertEquals("assertTrue(a.containsKey(e));",
                    render(TemplateAssertionKind.MAP_CONTAINS_KEY, null, false));
            assertEquals("assertTrue(a.isEmpty());", render(TemplateAssertionKind.IS_EMPTY, null, false));
            assertEquals("assertTrue(a > e);", render(TemplateAssertionKind.GREATER, null, false));
            assertEquals("assertTrue(a < e);", render(TemplateAssertionKind.LESS, null, false));
            assertEquals("assertTrue(a >= e);", render(TemplateAssertionKind.GREATER_EQUALS, null, false));
            assertEquals("assertTrue(a <= e);", render(TemplateAssertionKind.LESS_EQUALS, null, false));
        }
    }

    @Test
    public void defaultOverloadDoesNotRenderArrayEquality() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        assertEquals("assertEquals(e, a);",
                CanonicalAssertionRenderer.forConfiguredFormat().render(TemplateAssertionKind.EQUALS, "e", "a", null));
    }

    @Test
    public void junit4And5RenderArrayAndInequalityAssertions() {
        for (Properties.OutputFormat format : new Properties.OutputFormat[]{
                Properties.OutputFormat.JUNIT4, Properties.OutputFormat.JUNIT5}) {
            Properties.TEST_FORMAT = format;
            assertEquals("assertArrayEquals(e, a);", render(TemplateAssertionKind.EQUALS, null, true));
            assertEquals("assertNotEquals(e, a);", render(TemplateAssertionKind.NOT_EQUALS, null, false));
            assertEquals("assertNotEquals(e, a, d);", render(TemplateAssertionKind.NOT_EQUALS, "d", false));
        }
    }

    @Test
    public void junit3RendersArrayAndInequalityAssertionsWithoutNewerAsserts() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT3;
        assertEquals("assertTrue(java.util.Arrays.equals(e, a));", render(TemplateAssertionKind.EQUALS, null, true));
        assertEquals("assertFalse(java.util.Objects.equals(e, a));",
                render(TemplateAssertionKind.NOT_EQUALS, null, false));
        assertEquals("assertTrue(Double.compare((double)(e), (double)(a)) != 0"
                        + " && !(Math.abs((double)(e) - (double)(a)) <= d));",
                render(TemplateAssertionKind.NOT_EQUALS, "d", false));
    }

    @Test
    public void arrayEqualityRejectsDelta() {
        for (Properties.OutputFormat format : Properties.OutputFormat.values()) {
            Properties.TEST_FORMAT = format;
            assertThrows(IllegalStateException.class, () -> render(TemplateAssertionKind.EQUALS, "d", true));
        }
    }
}
