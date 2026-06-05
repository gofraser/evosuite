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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Package-private label/count helpers shared by extractor stats classes and
 * card builders. Centralised here so the moved sibling classes (stage A of the
 * extractor split) don't need to reach into {@link ProblemCardExtractor}.
 */
final class ProblemCardLabels {

    private ProblemCardLabels() {}

    static void incrementLabel(Map<String, Integer> counts, String label) {
        if (counts == null || label == null || label.isEmpty()) {
            return;
        }
        counts.put(label, counts.getOrDefault(label, 0) + 1);
    }

    static void mergeLabelCounts(Map<String, Integer> destination, Map<String, Integer> source) {
        if (destination == null || source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) {
                continue;
            }
            destination.put(entry.getKey(),
                    destination.getOrDefault(entry.getKey(), 0) + entry.getValue());
        }
    }

    static String dominantLabel(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        String best = "";
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    static List<String> topLabels(Map<String, Integer> counts, int maxLabels) {
        if (counts == null || counts.isEmpty() || maxLabels <= 0) {
            return Collections.emptyList();
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((left, right) -> {
            int countCompare = Integer.compare(
                    right.getValue() == null ? 0 : right.getValue(),
                    left.getValue() == null ? 0 : left.getValue());
            if (countCompare != 0) {
                return countCompare;
            }
            String leftKey = left.getKey() == null ? "" : left.getKey();
            String rightKey = right.getKey() == null ? "" : right.getKey();
            return leftKey.compareTo(rightKey);
        });
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            String label = entry.getKey();
            if (label == null || label.isEmpty()) {
                continue;
            }
            labels.add(label);
            if (labels.size() >= maxLabels) {
                break;
            }
        }
        return labels;
    }

    static double failureRate(int failures, int successes) {
        int total = failures + successes;
        if (total <= 0) {
            return 0.0;
        }
        return ((double) failures) / (double) total;
    }
}
