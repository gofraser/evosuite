/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ParsedFewShotExampleSource} cache invalidation.
 *
 * <p>Uses the package-private {@code loaderOverride} hook to intercept
 * the reload path, proving that {@code getInstance()} automatically
 * invalidates the cached instance when the property tuple changes.
 */
class ParsedFewShotExampleSourceTest {

    private String origTargetClass;
    private String origSelectedJunit;
    private String origSeedTestSourceDir;

    @BeforeEach
    void save() {
        origTargetClass = Properties.TARGET_CLASS;
        origSelectedJunit = Properties.SELECTED_JUNIT;
        origSeedTestSourceDir = Properties.SEED_TEST_SOURCE_DIR;
        ParsedFewShotExampleSource.reset();
        ParsedFewShotExampleSource.loaderOverride = null;
    }

    @AfterEach
    void restore() {
        Properties.TARGET_CLASS = origTargetClass;
        Properties.SELECTED_JUNIT = origSelectedJunit;
        Properties.SEED_TEST_SOURCE_DIR = origSeedTestSourceDir;
        ParsedFewShotExampleSource.loaderOverride = null;
        ParsedFewShotExampleSource.reset();
    }

    private static TestCase makeTestCase(String marker) {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new StringPrimitiveStatement(tc, marker));
        return tc;
    }

    // ---- propertyKey composition ----

    @Test
    void propertyKeyReflectsAllThreeProperties() {
        Properties.TARGET_CLASS = "A";
        Properties.SELECTED_JUNIT = "B";
        Properties.SEED_TEST_SOURCE_DIR = "C";
        assertEquals("A|B|C", ParsedFewShotExampleSource.currentPropertyKey());
    }

    @Test
    void propertyKeyHandlesNulls() {
        Properties.TARGET_CLASS = null;
        Properties.SELECTED_JUNIT = null;
        Properties.SEED_TEST_SOURCE_DIR = null;
        assertEquals("null|null|null", ParsedFewShotExampleSource.currentPropertyKey());
    }

    // ---- cache reuse when properties unchanged ----

    @Test
    void unchangedProperties_reusesCachedInstance() {
        Properties.TARGET_CLASS = "com.example.Foo";
        Properties.SELECTED_JUNIT = "FooTest";
        Properties.SEED_TEST_SOURCE_DIR = null;

        AtomicInteger loadCount = new AtomicInteger(0);
        ParsedFewShotExampleSource.loaderOverride = () -> {
            loadCount.incrementAndGet();
            return Collections.singletonList(makeTestCase("loaded"));
        };

        ParsedFewShotExampleSource first = ParsedFewShotExampleSource.getInstance();
        ParsedFewShotExampleSource second = ParsedFewShotExampleSource.getInstance();

        assertSame(first, second, "Same property tuple must reuse cached instance");
        assertEquals(1, loadCount.get(), "Loader must be called exactly once");
        assertEquals(1, first.getParsedTests().size());
    }

    // ---- auto-invalidation when property tuple changes ----

    @Test
    void changedTargetClass_autoInvalidatesAndReloads() {
        Properties.TARGET_CLASS = "com.example.Alpha";
        Properties.SELECTED_JUNIT = "AlphaTest";
        Properties.SEED_TEST_SOURCE_DIR = null;

        List<String> loadedMarkers = new ArrayList<>();
        ParsedFewShotExampleSource.loaderOverride = () -> {
            String marker = Properties.TARGET_CLASS;
            loadedMarkers.add(marker);
            return Collections.singletonList(makeTestCase(marker));
        };

        // First load
        ParsedFewShotExampleSource first = ParsedFewShotExampleSource.getInstance();
        assertEquals(1, loadedMarkers.size(), "First call should trigger load");
        assertTrue(first.getPropertyKey().startsWith("com.example.Alpha|"));

        // Change TARGET_CLASS — no reset(), no setInstance()
        Properties.TARGET_CLASS = "com.example.Beta";
        ParsedFewShotExampleSource second = ParsedFewShotExampleSource.getInstance();

        assertEquals(2, loadedMarkers.size(), "Property change must trigger reload");
        assertNotSame(first, second, "New instance must be created after property change");
        assertTrue(second.getPropertyKey().startsWith("com.example.Beta|"));
    }

    @Test
    void changedSelectedJunit_autoInvalidatesAndReloads() {
        Properties.TARGET_CLASS = "com.example.Foo";
        Properties.SELECTED_JUNIT = "FooTest";
        Properties.SEED_TEST_SOURCE_DIR = null;

        AtomicInteger loadCount = new AtomicInteger(0);
        ParsedFewShotExampleSource.loaderOverride = () -> {
            loadCount.incrementAndGet();
            return Collections.emptyList();
        };

        ParsedFewShotExampleSource first = ParsedFewShotExampleSource.getInstance();
        assertEquals(1, loadCount.get());

        Properties.SELECTED_JUNIT = "BarTest";
        ParsedFewShotExampleSource second = ParsedFewShotExampleSource.getInstance();

        assertEquals(2, loadCount.get(), "SELECTED_JUNIT change must trigger reload");
        assertNotSame(first, second);
    }

    @Test
    void changedSeedTestSourceDir_autoInvalidatesAndReloads() {
        Properties.TARGET_CLASS = "com.example.Foo";
        Properties.SELECTED_JUNIT = "FooTest";
        Properties.SEED_TEST_SOURCE_DIR = "/old/path";

        AtomicInteger loadCount = new AtomicInteger(0);
        ParsedFewShotExampleSource.loaderOverride = () -> {
            loadCount.incrementAndGet();
            return Collections.emptyList();
        };

        ParsedFewShotExampleSource first = ParsedFewShotExampleSource.getInstance();
        assertEquals(1, loadCount.get());

        Properties.SEED_TEST_SOURCE_DIR = "/new/path";
        ParsedFewShotExampleSource second = ParsedFewShotExampleSource.getInstance();

        assertEquals(2, loadCount.get(), "SEED_TEST_SOURCE_DIR change must trigger reload");
        assertNotSame(first, second);
    }

    @Test
    void multiplePropertyChanges_eachTriggersReload() {
        Properties.TARGET_CLASS = "v1";
        Properties.SELECTED_JUNIT = "v1";
        Properties.SEED_TEST_SOURCE_DIR = "v1";

        AtomicInteger loadCount = new AtomicInteger(0);
        ParsedFewShotExampleSource.loaderOverride = () -> {
            loadCount.incrementAndGet();
            return Collections.emptyList();
        };

        ParsedFewShotExampleSource.getInstance();
        assertEquals(1, loadCount.get());

        // Change #1
        Properties.TARGET_CLASS = "v2";
        ParsedFewShotExampleSource.getInstance();
        assertEquals(2, loadCount.get());

        // No change — should reuse
        ParsedFewShotExampleSource.getInstance();
        assertEquals(2, loadCount.get(), "No property change should not trigger reload");

        // Change #2
        Properties.SELECTED_JUNIT = "v2";
        ParsedFewShotExampleSource.getInstance();
        assertEquals(3, loadCount.get());

        // Change #3
        Properties.SEED_TEST_SOURCE_DIR = "v2";
        ParsedFewShotExampleSource.getInstance();
        assertEquals(4, loadCount.get());
    }

    // ---- data returned by reload reflects the new loader call ----

    @Test
    void reloadedInstanceContainsNewLoaderData() {
        Properties.TARGET_CLASS = "phase1";
        Properties.SELECTED_JUNIT = "X";
        Properties.SEED_TEST_SOURCE_DIR = null;

        ParsedFewShotExampleSource.loaderOverride = () ->
                Collections.singletonList(makeTestCase(Properties.TARGET_CLASS));

        ParsedFewShotExampleSource first = ParsedFewShotExampleSource.getInstance();
        assertEquals(1, first.getParsedTests().size());
        assertTrue(first.getParsedTests().get(0).toCode().contains("phase1"));

        Properties.TARGET_CLASS = "phase2";
        ParsedFewShotExampleSource second = ParsedFewShotExampleSource.getInstance();
        assertEquals(1, second.getParsedTests().size());
        assertTrue(second.getParsedTests().get(0).toCode().contains("phase2"),
                "Reloaded instance must reflect current property state");
    }
}
