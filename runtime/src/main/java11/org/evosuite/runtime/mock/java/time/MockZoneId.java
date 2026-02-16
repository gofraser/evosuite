/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.time;

import org.evosuite.runtime.mock.StaticReplacementMock;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic static replacement for {@link ZoneId}.
 */
public final class MockZoneId implements StaticReplacementMock {

    private static final ZoneId DETERMINISTIC_DEFAULT = ZoneOffset.UTC;

    @Override
    public String getMockedClassName() {
        return ZoneId.class.getName();
    }

    public static ZoneId systemDefault() {
        return DETERMINISTIC_DEFAULT;
    }

    public static Set<String> getAvailableZoneIds() {
        return ZoneId.getAvailableZoneIds();
    }

    public static ZoneId of(String zoneId, Map<String, String> aliasMap) {
        return ZoneId.of(zoneId, aliasMap);
    }

    public static ZoneId of(String zoneId) {
        return ZoneId.of(zoneId);
    }

    public static ZoneId ofOffset(String prefix, ZoneOffset offset) {
        return ZoneId.ofOffset(prefix, offset);
    }
}
