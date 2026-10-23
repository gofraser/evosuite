/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.setup;

import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With a target class prefix, deciding whether a class is a target must not run its static
 * initialiser: that would execute SUT code outside the instrumenting class loader.
 */
public class DependencyAnalysisTargetClassNameTest {

    static boolean probeInitialised;

    static class Probe {
        static {
            probeInitialised = true;
        }
    }

    private final String defaultPrefix = Properties.TARGET_CLASS_PREFIX;

    @BeforeEach
    public void setUp() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        probeInitialised = false;
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS_PREFIX = defaultPrefix;
        ClassPathHandler.resetSingleton();
    }

    @Test
    public void prefixModeDoesNotInitialiseTheClass() {
        String name = Probe.class.getName();
        Properties.TARGET_CLASS_PREFIX = name;

        assertTrue(DependencyAnalysis.isTargetClassName(name));
        assertFalse(probeInitialised);
    }
}
