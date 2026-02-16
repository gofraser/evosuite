/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.time;

import org.evosuite.runtime.mock.StaticReplacementMock;

import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;

/**
 * Static replacement hook for {@link ZoneOffset}.
 */
public final class MockZoneOffset implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return ZoneOffset.class.getName();
    }

    public static ZoneOffset of(String offsetId) {
        return ZoneOffset.of(offsetId);
    }

    public static ZoneOffset ofHours(int hours) {
        return ZoneOffset.ofHours(hours);
    }

    public static ZoneOffset ofHoursMinutes(int hours, int minutes) {
        return ZoneOffset.ofHoursMinutes(hours, minutes);
    }

    public static ZoneOffset ofHoursMinutesSeconds(int hours, int minutes, int seconds) {
        return ZoneOffset.ofHoursMinutesSeconds(hours, minutes, seconds);
    }

    public static ZoneOffset ofTotalSeconds(int totalSeconds) {
        return ZoneOffset.ofTotalSeconds(totalSeconds);
    }

    public static ZoneOffset from(TemporalAccessor temporal) {
        return ZoneOffset.from(temporal);
    }
}
