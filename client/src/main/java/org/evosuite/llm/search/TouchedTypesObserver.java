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

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TouchedTypesObserver {

    Set<String> observe(List<TestChromosome> snapshot, Set<String> coveredMethods) {
        Set<String> touched = new LinkedHashSet<>();
        if (coveredMethods != null) {
            for (String key : coveredMethods) {
                if (key == null || key.isEmpty()) {
                    continue;
                }
                int dot = key.lastIndexOf('.');
                if (dot > 0) {
                    touched.add(key.substring(0, dot));
                }
            }
        }
        if (snapshot == null || snapshot.isEmpty()) {
            return touched;
        }
        for (TestChromosome chromosome : snapshot) {
            if (chromosome == null) {
                continue;
            }
            ExecutionResult result = chromosome.getLastExecutionResult();
            TestCase test = chromosome.getTestCase() != null
                    ? chromosome.getTestCase()
                    : (result == null ? null : result.test);
            if (test == null) {
                continue;
            }
            int safeSize = ExtractorObservationSupport.safeTestSize(test);
            for (int i = 0; i < safeSize; i++) {
                Statement statement = ExtractorObservationSupport.statementAt(test, i);
                if (statement instanceof ConstructorStatement) {
                    String name = ExtractorObservationSupport.constructorType((ConstructorStatement) statement);
                    if (!name.isEmpty()) {
                        touched.add(name);
                    }
                } else if (statement instanceof MethodStatement) {
                    MethodStatement methodStatement = (MethodStatement) statement;
                    String receiver = ExtractorObservationSupport.thrownTypeForMethod(methodStatement);
                    if (!receiver.isEmpty()) {
                        touched.add(receiver);
                    }
                    String declaring = ExtractorObservationSupport.declaringTypeForMethod(methodStatement);
                    if (!declaring.isEmpty()) {
                        touched.add(declaring);
                    }
                }
            }
        }
        return touched;
    }

}
