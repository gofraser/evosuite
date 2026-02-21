/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.statistics;

import com.examples.with.different.packagename.Compositional;

import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.Properties.Criterion;
import org.evosuite.statistics.backend.DebugStatisticsBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;


public class TimelineForCombinedFitness2SystemTest extends SystemTestBase {

    private final Criterion[] oldCriterion = Arrays.copyOf(Properties.CRITERION, Properties.CRITERION.length);

    private final String ANALYSIS_CRITERIA = Properties.ANALYSIS_CRITERIA;

    private final boolean ASSERTIONS = Properties.ASSERTIONS;

    @AfterEach
    public void afterTest() {
        Properties.CRITERION = oldCriterion;
        Properties.ANALYSIS_CRITERIA = ANALYSIS_CRITERIA;
        Properties.ASSERTIONS = ASSERTIONS;
    }

    @Test
    public void testTimelineForCombinedFitnessAll() {
        EvoSuite evosuite = new EvoSuite();
        String targetClass = Compositional.class.getCanonicalName();
        Properties.ASSERTIONS = false;
        Properties.TARGET_CLASS = targetClass;
        Properties.MINIMIZE = true;
        Properties.CRITERION = new Properties.Criterion[5];
        Properties.CRITERION[0] = Properties.Criterion.ONLYBRANCH;
        Properties.CRITERION[1] = Properties.Criterion.METHODNOEXCEPTION;
        Properties.CRITERION[2] = Properties.Criterion.OUTPUT;
        Properties.CRITERION[3] = Properties.Criterion.ONLYMUTATION;
        Properties.CRITERION[4] = Properties.Criterion.CBRANCH;

        StringBuilder analysisCriteria = new StringBuilder();
        analysisCriteria.append(Properties.Criterion.LINE);
        analysisCriteria.append(",");
        analysisCriteria.append(Properties.Criterion.ONLYBRANCH);
        analysisCriteria.append(",");
        analysisCriteria.append(Properties.Criterion.METHODTRACE);
        analysisCriteria.append(",");
        analysisCriteria.append(Properties.Criterion.METHOD);
        analysisCriteria.append(",");
        analysisCriteria.append(Properties.Criterion.METHODNOEXCEPTION);
        analysisCriteria.append(",");
        analysisCriteria.append(Properties.Criterion.OUTPUT);
        analysisCriteria.append(",");
        analysisCriteria.append(Properties.Criterion.ONLYMUTATION);
        analysisCriteria.append(",");
        analysisCriteria.append(Properties.Criterion.CBRANCH);
        analysisCriteria.append(",");
        analysisCriteria.append(Properties.Criterion.EXCEPTION);
        Properties.ANALYSIS_CRITERIA = analysisCriteria.toString();

        StringBuilder outputVariables = new StringBuilder();
        outputVariables.append(RuntimeVariable.CoverageTimeline);
        outputVariables.append(",");
        outputVariables.append(RuntimeVariable.OnlyBranchCoverageTimeline);
        outputVariables.append(",");
        outputVariables.append(RuntimeVariable.MethodNoExceptionCoverageTimeline);
        outputVariables.append(",");
        outputVariables.append(RuntimeVariable.CBranchFitnessTimeline);
        outputVariables.append(",");
        outputVariables.append(RuntimeVariable.CBranchCoverageTimeline);
        outputVariables.append(",");
        outputVariables.append(RuntimeVariable.OutputCoverageTimeline);
        Properties.OUTPUT_VARIABLES = outputVariables.toString();

        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        evosuite.parseCommandLine(command);
        Map<String, OutputVariable<?>> map = DebugStatisticsBackend.getLatestWritten();
        Assertions.assertNotNull(map);

        String strVar1 = RuntimeVariable.CoverageTimeline.toString();
        OutputVariable cov = getLastTimelineVariable(map, strVar1);
        Assertions.assertNotNull(cov);
        Assertions.assertEquals(1.0, cov.getValue(), "Incorrect last timeline value for " + strVar1);

        String strVar2 = RuntimeVariable.OnlyBranchCoverageTimeline.toString();
        OutputVariable method = getLastTimelineVariable(map, strVar2);
        Assertions.assertNotNull(method);
        Assertions.assertEquals(1.0, method.getValue(), "Incorrect last timeline value for " + strVar2);

        String strVar3 = RuntimeVariable.MethodNoExceptionCoverageTimeline.toString();
        OutputVariable methodNE = getLastTimelineVariable(map, strVar3);
        Assertions.assertNotNull(methodNE);
        Assertions.assertEquals(1.0, methodNE.getValue(), "Incorrect last timeline value for " + strVar3);

        String strVar4 = RuntimeVariable.OutputCoverageTimeline.toString();
        OutputVariable output = getLastTimelineVariable(map, strVar4);
        Assertions.assertNotNull(output);
        Assertions.assertEquals(1.0, output.getValue(), "Incorrect last timeline value for " + strVar4);

        String strVar5 = RuntimeVariable.CBranchFitnessTimeline.toString();
        OutputVariable cbranchF = getLastTimelineVariable(map, strVar5);
        Assertions.assertNotNull(cbranchF);
        Assertions.assertEquals(0.0, cbranchF.getValue(), "Incorrect last timeline value for " + strVar5);

        String strVar6 = RuntimeVariable.CBranchCoverageTimeline.toString();
        OutputVariable cbranchC = getLastTimelineVariable(map, strVar6);
        Assertions.assertNotNull(cbranchC);
        Assertions.assertEquals(1.0, cbranchC.getValue(), "Incorrect last timeline value for " + strVar6);

    }

    private OutputVariable getLastTimelineVariable(Map<String, OutputVariable<?>> map, String name) {
        OutputVariable timelineVar = null;
        int max = -1;
        for (Map.Entry<String, OutputVariable<?>> e : map.entrySet()) {
            if (e.getKey().startsWith(name)) {
                int index = Integer.parseInt((e.getKey().split("_T"))[1]);
                if (index > max) {
                    max = index;
                    timelineVar = e.getValue();
                }
            }
        }
        return timelineVar;
    }
}
