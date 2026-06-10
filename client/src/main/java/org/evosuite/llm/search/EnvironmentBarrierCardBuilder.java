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

import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.List;

final class EnvironmentBarrierCardBuilder implements CardBuilder {

    private static final int MAX_SAMPLE = 3;

    @Override
    public void emit(List<ProblemCard> out, CardBuildContext context) {
        if (context == null || context.environmentBarrierSignals == null
                || context.environmentBarrierSignals.isEmpty()) {
            return;
        }
        for (EnvironmentBarrierSignal signal : context.environmentBarrierSignals.values()) {
            if (signal == null || !signal.hasAnyAccess()) {
                continue;
            }
            List<TestFitnessFunction> relatedGoals = CardBuildSupport.deduplicateGoals(signal.relatedGoals);
            if (relatedGoals.isEmpty()) {
                continue;
            }
            String label = signal.methodLabel == null || signal.methodLabel.isEmpty()
                    ? signal.methodKey : signal.methodLabel;
            List<String> evidence = new ArrayList<>();
            evidence.add(label + " was reached by " + signal.observingTests
                    + " test(s) that read the external environment, yet the goal stayed uncovered — "
                    + "the environment was not seeded with the values needed to drive it.");
            if (signal.hasFileAccess()) {
                evidence.add("File-system reads observed: " + sample(signal.accessedFiles)
                        + ". Seed these virtual file(s) with the required contents first.");
            }
            if (signal.hasPropertyAccess()) {
                evidence.add("System properties read: " + sample(signal.readProperties)
                        + ". Set these properties before the call.");
            }
            if (signal.hasNetworkAccess()) {
                evidence.add(signal.remoteResources.isEmpty()
                        ? "Network access observed; provide the expected remote response."
                        : "Network resources read: " + sample(signal.remoteResources)
                                + ". Provide the expected remote response(s).");
            }
            evidence.add("Related uncovered goals: " + relatedGoals.size() + ".");

            double impact = CardBuildSupport.leverageAwareImpact(relatedGoals, 5.0);
            double blockage = 1.0;
            double confidence = Math.max(0.5,
                    CardBuildSupport.normalizeCount(signal.observingTests, 3.0));
            out.add(ProblemCard.builder(ProblemCardType.ENVIRONMENT_BARRIER)
                    .title("Environment barrier in " + label)
                    .evidence(evidence)
                    .relatedGoals(relatedGoals)
                    .impact(impact)
                    .blockage(blockage)
                    .confidence(confidence)
                    .family(ProblemCardFamily.STRUCTURAL)
                    .rootCauseKey("environment:" + signal.methodKey)
                    .scopeKey("environment-barrier:" + signal.methodKey)
                    .selectionFingerprint("environment-barrier:" + signal.methodKey
                            + "|files=" + signal.accessedFiles.size()
                            + "|props=" + signal.readProperties.size()
                            + "|network=" + signal.hasNetworkAccess()
                            + "|tests=" + signal.observingTests)
                    .build());
        }
    }

    private String sample(java.util.Collection<String> values) {
        List<String> bounded = new ArrayList<>();
        for (String value : values) {
            bounded.add(value);
            if (bounded.size() >= MAX_SAMPLE) {
                break;
            }
        }
        String joined = String.join(", ", bounded);
        return values.size() > bounded.size() ? joined + ", …" : joined;
    }
}
