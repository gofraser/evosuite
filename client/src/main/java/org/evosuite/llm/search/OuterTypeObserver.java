/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
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

import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OuterTypeObserver {

    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();

    Map<String, OuterTypeAttemptSignal> observe(List<TestChromosome> snapshot) {
        Map<String, OuterTypeAttemptSignal> signals = new LinkedHashMap<>();
        if (snapshot == null || snapshot.isEmpty()) {
            return signals;
        }
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            if (result == null) {
                continue;
            }
            TestCase test = chromosome.getTestCase() != null ? chromosome.getTestCase() : result.test;
            if (test == null) {
                continue;
            }
            int executedStatements = result.getExecutedStatements();
            int safeSize = ExtractorObservationSupport.safeTestSize(test);
            if (safeSize <= 0) {
                continue;
            }
            int thrownPos = ExtractorObservationSupport.firstThrownPosition(result);
            if (result.hasTestException() && thrownPos >= 0) {
                executedStatements = Math.max(executedStatements, thrownPos + 1);
            }
            if (executedStatements <= 0 || executedStatements > safeSize) {
                executedStatements = safeSize;
            }
            for (int i = 0; i < executedStatements; i++) {
                Statement statement = ExtractorObservationSupport.statementAt(test, i);
                if (!(statement instanceof MethodStatement)) {
                    continue;
                }
                MethodStatement methodStatement = (MethodStatement) statement;
                String outerType = ExtractorObservationSupport.outerTypeName(
                        ExtractorObservationSupport.declaringTypeForMethod(methodStatement));
                if (outerType.isEmpty()) {
                    continue;
                }
                OuterTypeAttemptSignal signal = signals.computeIfAbsent(outerType, OuterTypeAttemptSignal::new);
                String label = goalDescriptionMapper.describeMethodOperation(methodStatement).getDisplayLabel();
                signal.attempts++;
                if (result.hasTestException() && thrownPos == i) {
                    signal.exceptions++;
                    String key = ExtractorObservationSupport.exceptionKey(result);
                    if (!key.isEmpty()) {
                        signal.exceptionTypeCounts.put(key,
                                signal.exceptionTypeCounts.getOrDefault(key, 0) + 1);
                    }
                    ProblemCardLabels.incrementLabel(signal.failingEntryPointLabelCounts, label);
                } else if (!result.hasTimeout()) {
                    signal.successes++;
                    ProblemCardLabels.incrementLabel(signal.successfulEntryPointLabelCounts, label);
                }
            }
        }
        return signals;
    }

}
