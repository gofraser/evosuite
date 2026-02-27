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
