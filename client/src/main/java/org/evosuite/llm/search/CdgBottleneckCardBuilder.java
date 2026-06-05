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
import java.util.List;

final class CdgBottleneckCardBuilder implements CardBuilder {

    @Override
    public void emit(List<ProblemCard> out, CardBuildContext context) {
        if (context == null || context.cdgSignals == null || context.cdgSignals.isEmpty()) {
            return;
        }
        for (CdgDependencySignal signal : context.cdgSignals.values()) {
            if (signal == null || signal.relatedGoals.isEmpty()) {
                continue;
            }
            if (CardBuildSupport.isSyntheticCompilerMethod(signal.branchTarget.getTarget().getBaseMethodName())) {
                continue;
            }
            if (signal.desiredHits > 0 || signal.oppositeHits <= 0) {
                continue;
            }
            double impact = CardBuildSupport.normalizeCount(signal.relatedGoals.size(), 5.0);
            double blockage = signal.predicateExecutions <= 0
                    ? 1.0
                    : ((double) signal.oppositeHits / (double) signal.predicateExecutions);
            double confidence = signal.predicateExecutions <= 0
                    ? 0.6
                    : CardBuildSupport.normalizeCount(signal.oppositeHits, 6.0);
            String branchLocation = signal.branchTarget.getLocationLabel();
            String desiredLabel = signal.branchTarget.getOutcomeLabel();
            List<String> evidence = new ArrayList<>();
            evidence.add("Required control dependency outcome " + desiredLabel
                    + " was never observed for " + branchLocation + ".");
            evidence.add("Uncovered goals transitively blocked by this dependency: "
                    + signal.relatedGoals.size() + ".");
            if (signal.predicateExecutions > 0) {
                evidence.add("Opposite dependency outcome observed in "
                        + signal.oppositeHits + " tests.");
            } else {
                evidence.add("Dependency branch was not observed in current executions.");
            }
            out.add(ProblemCard.builder(ProblemCardType.CDG_BOTTLENECK)
                    .title("Control-dependency bottleneck in " + branchLocation + " ("
                            + signal.branchTarget.getNeedLabel() + ")")
                    .evidence(evidence)
                    .relatedGoals(signal.relatedGoals)
                    .impact(impact)
                    .blockage(blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.LOCAL)
                    .rootCauseKey(signal.signalKey())
                    .scopeKey(signal.signalKey())
                    .selectionFingerprint("cdg:" + signal.signalKey()
                            + "|opposite=" + signal.oppositeHits
                            + "|desired=" + signal.desiredHits
                            + "|executions=" + signal.predicateExecutions
                            + "|blockedGoals=" + signal.relatedGoals.size())
                    .build());
        }
    }
}
