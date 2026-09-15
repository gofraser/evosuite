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
package org.evosuite.llm.prompt;

import com.examples.with.different.packagename.llm.SwitchOperandFixture;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.execution.BranchOperands;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises {@link BranchOperandHint} against a real switch compiled and instrumented through
 * EvoSuite's normal pipeline, not a mocked {@link org.evosuite.graphs.cfg.BytecodeInstruction}:
 * confirms both what the switch instrumentation actually records for a case branch and what the
 * hint renders from it, matching {@code BranchOperandHintTest}'s mocked switch-case expectations
 * against the real bytecode.
 */
class BranchOperandHintSwitchInstrumentationTest {

    private static final String CLASS_NAME = SwitchOperandFixture.class.getCanonicalName();

    private boolean originalFlag;

    @BeforeEach
    void setUp() throws ClassNotFoundException {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        Properties.getInstance().resetToDefaults();
        Properties.TARGET_CLASS = "";
        TestGenerationContext.getInstance().resetContext();
        ClassReInitializer.resetSingleton();

        originalFlag = Properties.LLM_BRANCH_OPERAND_HINTS;
        Properties.LLM_BRANCH_OPERAND_HINTS = true;

        Properties.TARGET_CLASS = CLASS_NAME;
        String cp = ClassPathHandler.getInstance().getTargetProjectClasspath();
        DependencyAnalysis.analyzeClass(CLASS_NAME, Arrays.asList(cp.split(File.pathSeparator)));
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_BRANCH_OPERAND_HINTS = originalFlag;
        ExecutionTracer.getExecutionTracer().clear();
        TestGenerationContext.getInstance().resetContext();
        ClassReInitializer.resetSingleton();
        Properties.getInstance().resetToDefaults();
    }

    @Test
    void switchCaseHintReflectsWhatTheRealInstrumentationRecords() throws Exception {
        InstrumentingClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        Class<?> fixtureClass = loader.loadClass(CLASS_NAME);

        ExecutionTracer.enable();
        ExecutionTracer.setCheckCallerThread(false);
        ExecutionTracer.getExecutionTracer().clear();

        Object instance = fixtureClass.getConstructor().newInstance();
        Field codeField = fixtureClass.getField("code");
        codeField.setInt(instance, 3);
        Method route = fixtureClass.getMethod("route");
        route.invoke(instance);

        ExecutionTrace trace = ExecutionTracer.getExecutionTracer().getTrace();

        Branch caseFive = null;
        for (Branch b : BranchPool.getInstance(loader).getAllBranches()) {
            if (b.getClassName().equals(CLASS_NAME) && b.isSwitchCaseBranch()
                    && Integer.valueOf(5).equals(b.getTargetCaseValue())) {
                caseFive = b;
                break;
            }
        }
        assertNotNull(caseFive, "expected a case-5 switch branch for " + CLASS_NAME);

        BranchOperands operands = trace.getLastBranchOperands().get(caseFive.getActualBranchId());
        assertEquals(BranchOperands.ofInts(3, 5, Opcodes.IF_ICMPEQ), operands);

        BranchCoverageTestFitness goal = new BranchCoverageTestFitness(
                new BranchCoverageGoal(caseFive, true, caseFive.getClassName(), caseFive.getMethodName()));

        assertEquals("Last evaluation: code=3 vs 5 (IF_ICMPEQ); need code == 5",
                BranchOperandHint.forGoal(goal, trace));
    }
}
