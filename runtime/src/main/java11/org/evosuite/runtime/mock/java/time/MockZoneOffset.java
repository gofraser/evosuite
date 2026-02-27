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
