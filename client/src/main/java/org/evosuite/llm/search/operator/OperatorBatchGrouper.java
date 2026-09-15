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
package org.evosuite.llm.search.operator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Shapes a rank-ordered list of {@link OperatorTarget}s into homogeneous batches for {@link
 * LlmOperatorExecutor#submit}, independent of selector order: a target joining a kind whose
 * current batch is already full starts a new one for that kind, so two targets of the same kind
 * separated by a different-kind target in between still end up in the same call whenever room
 * allows.
 */
final class OperatorBatchGrouper {

    private OperatorBatchGrouper() {
    }

    /** One batch under construction: its targets plus the rank of the first one added. */
    private static final class PendingBatch {
        private final int firstRank;
        private final List<OperatorTarget> items = new ArrayList<>();

        private PendingBatch(int firstRank) {
            this.firstRank = firstRank;
        }
    }

    /**
     * Walks {@code targets} once, keeping one open {@link PendingBatch} per kind; a batch that
     * reaches {@code batchSize} is sealed. Every batch — sealed or still open when the walk ends
     * — is then ordered by the rank of its first target and truncated to {@code maxCalls}, so a
     * batch that happens to fill up later than another with an earlier first target still sorts
     * before it.
     *
     * @param targets   candidates in rank order (highest priority first); {@code null} entries
     *                  are skipped
     * @param batchSize the maximum size of one batch
     * @param maxCalls  the maximum number of batches to return
     * @return up to {@code maxCalls} batches, in the order they should be submitted
     */
    static List<List<OperatorTarget>> groupIntoBatches(List<OperatorTarget> targets, int batchSize, int maxCalls) {
        List<PendingBatch> sealed = new ArrayList<>();
        Map<OperatorTarget.Kind, PendingBatch> open = new EnumMap<>(OperatorTarget.Kind.class);
        for (int rank = 0; rank < targets.size(); rank++) {
            OperatorTarget target = targets.get(rank);
            if (target == null) {
                continue;
            }
            OperatorTarget.Kind kind = target.getKind();
            PendingBatch batch = open.get(kind);
            if (batch == null) {
                batch = new PendingBatch(rank);
                open.put(kind, batch);
            }
            batch.items.add(target);
            if (batch.items.size() >= batchSize) {
                sealed.add(batch);
                open.remove(kind);
            }
        }
        List<PendingBatch> all = new ArrayList<>(sealed);
        all.addAll(open.values());
        all.sort(Comparator.comparingInt(b -> b.firstRank));

        List<List<OperatorTarget>> result = new ArrayList<>(Math.min(maxCalls, all.size()));
        for (PendingBatch batch : all) {
            if (result.size() >= maxCalls) {
                break;
            }
            result.add(batch.items);
        }
        return result;
    }
}
