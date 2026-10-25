/*
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
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.search;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks exception-barrier evidence across multiple stagnation snapshots.
 */
final class ExceptionBarrierTracker {

    private static final int DEFAULT_WINDOW_SIZE = 6;
    private static final int DEFAULT_MAX_METHODS = 512;

    private final int windowSize;
    private final int maxMethods;
    private final Map<String, Deque<AttemptStats>> observationsByMethod = new LinkedHashMap<>();

    ExceptionBarrierTracker() {
        this(DEFAULT_WINDOW_SIZE, DEFAULT_MAX_METHODS);
    }

    ExceptionBarrierTracker(int windowSize, int maxMethods) {
        this.windowSize = Math.max(1, windowSize);
        this.maxMethods = Math.max(16, maxMethods);
    }

    /**
     * Adds the per-method stats the extractor computed for one population snapshot
     * to the window of its goal methods.
     */
    synchronized void record(Map<String, AttemptStats> statsByMethod) {
        if (statsByMethod == null || statsByMethod.isEmpty()) {
            return;
        }
        for (Map.Entry<String, AttemptStats> entry : statsByMethod.entrySet()) {
            Deque<AttemptStats> deque = observationsByMethod.computeIfAbsent(entry.getKey(),
                    ignored -> new ArrayDeque<>());
            deque.addLast(entry.getValue());
            while (deque.size() > windowSize) {
                deque.removeFirst();
            }
        }
        pruneToMaxMethods(statsByMethod.keySet());
    }

    /**
     * Returns the stats summed over the window for the requested methods.
     */
    synchronized Map<String, AttemptStats> snapshot(Set<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, AttemptStats> result = new HashMap<>();
        for (String method : methods) {
            Deque<AttemptStats> deque = observationsByMethod.get(method);
            if (deque == null || deque.isEmpty()) {
                continue;
            }
            AttemptStats sum = new AttemptStats();
            for (AttemptStats observation : deque) {
                sum.addAll(observation);
            }
            result.put(method, sum);
        }
        return result;
    }

    /**
     * Clears all accumulated observations.
     */
    synchronized void reset() {
        observationsByMethod.clear();
    }

    private void pruneToMaxMethods(Set<String> activeMethods) {
        if (observationsByMethod.size() <= maxMethods) {
            return;
        }
        // Prefer retaining currently relevant methods.
        List<String> removable = new ArrayList<>();
        for (String key : observationsByMethod.keySet()) {
            if (activeMethods == null || !activeMethods.contains(key)) {
                removable.add(key);
            }
        }
        for (String key : removable) {
            observationsByMethod.remove(key);
            if (observationsByMethod.size() <= maxMethods) {
                return;
            }
        }
        while (observationsByMethod.size() > maxMethods) {
            String first = observationsByMethod.keySet().iterator().next();
            observationsByMethod.remove(first);
        }
    }
}
