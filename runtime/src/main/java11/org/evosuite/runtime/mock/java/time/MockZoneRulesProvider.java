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
