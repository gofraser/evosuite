/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.time;

import org.evosuite.runtime.mock.StaticReplacementMock;

import java.time.ZoneOffset;
import java.time.zone.ZoneRules;

/**
 * Static replacement hook for {@link ZoneRules}.
 */
public final class MockZoneRules implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return ZoneRules.class.getName();
    }

    public static ZoneRules of(ZoneOffset offset) {
        return ZoneRules.of(offset);
    }
}
