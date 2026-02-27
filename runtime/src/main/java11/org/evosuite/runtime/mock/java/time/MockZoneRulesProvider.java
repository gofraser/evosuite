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
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesException;
import java.time.zone.ZoneRulesProvider;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic replacement for {@link ZoneRulesProvider}.
 */
public final class MockZoneRulesProvider implements StaticReplacementMock {

    private static final String PRIMARY_ZONE = "UTC";
    private static final String ZULU_ZONE = "Z";

    @Override
    public String getMockedClassName() {
        return ZoneRulesProvider.class.getName();
    }

    public static Set<String> getAvailableZoneIds() {
        return Collections.singleton(PRIMARY_ZONE);
    }

    public static ZoneRules getRules(String zoneId, boolean forCaching) {
        if (PRIMARY_ZONE.equals(zoneId) || ZULU_ZONE.equals(zoneId)) {
            return ZoneOffset.UTC.getRules();
        }
        throw new ZoneRulesException("Unknown time-zone ID: " + zoneId);
    }

    public static NavigableMap<String, ZoneRules> getVersions(String zoneId) {
        if (!PRIMARY_ZONE.equals(zoneId) && !ZULU_ZONE.equals(zoneId)) {
            throw new ZoneRulesException("Unknown time-zone ID: " + zoneId);
        }
        NavigableMap<String, ZoneRules> versions = new TreeMap<>();
        versions.put("evosuite", ZoneOffset.UTC.getRules());
        return Collections.unmodifiableNavigableMap(versions);
    }

    public static boolean refresh() {
        return false;
    }
}
