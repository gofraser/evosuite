/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.io.Console;

/**
 * Deterministic replacement hooks for {@link java.lang.System}.
 */
public class MockSystem implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return java.lang.System.class.getName();
    }

    /**
     * Replacement for {@link java.lang.System#console()}.
     *
     * @return the console
     */
    public static Console console() {
        if (!MockFramework.isEnabled()) {
            return java.lang.System.console();
        }
        // Deterministic policy: no real console device in unit tests.
        return null;
    }
}
