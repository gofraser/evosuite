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
package org.evosuite.coverage.branch;

import com.examples.with.different.packagename.cdg.BranchPoolFixture;
import org.evosuite.ContextResetter;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class or method that is cleared from a {@link BranchPool} must leave no trace in any of its
 * registries, so that instrumenting it again numbers its branches as a fresh pool would.
 */
public class BranchPoolClearingTest {

    private static final String CLASS_NAME = BranchPoolFixture.class.getCanonicalName();
    private static final String SIGN = "sign(I)I";
    private static final String DAY_TYPE = "dayType(I)Ljava/lang/String;";

    private BranchPool pool;

    @BeforeEach
    public void setUp() throws ClassNotFoundException {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        Properties.getInstance().resetToDefaults();
        Properties.TARGET_CLASS = "";
        ContextResetter.reset();
        ClassReInitializer.resetSingleton();

        Properties.TARGET_CLASS = CLASS_NAME;
        String cp = ClassPathHandler.getInstance().getTargetProjectClasspath();
        DependencyAnalysis.analyzeClass(CLASS_NAME, Arrays.asList(cp.split(File.pathSeparator)));
        pool = BranchPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT());
    }

    @AfterEach
    public void tearDown() {
        ContextResetter.reset();
        ClassReInitializer.resetSingleton();
        Properties.getInstance().resetToDefaults();
    }

    /** The branch instructions of the pool, once each, in the order they were registered. */
    private static List<BytecodeInstruction> instructionsInIdOrder(BranchPool source) {
        List<Branch> branches = new ArrayList<>(source.getAllBranches());
        branches.sort(null);
        Set<BytecodeInstruction> instructions = new LinkedHashSet<>();
        for (Branch b : branches) {
            instructions.add(b.getInstruction());
        }
        return new ArrayList<>(instructions);
    }

    private static List<String> signature(BranchPool source) {
        List<Branch> branches = new ArrayList<>(source.getAllBranches());
        branches.sort(null);
        List<String> result = new ArrayList<>();
        for (Branch b : branches) {
            result.add(b.getActualBranchId() + " " + b.getClassName() + " " + b.getMethodName()
                    + " " + b.getInstruction().getInstructionId() + " " + (b.isSwitchCaseBranch() ? b.getTargetCaseValue() : "-"));
        }
        result.add("counter " + source.getBranchCounter());
        result.add("branchless " + source.getBranchlessMethods(CLASS_NAME));
        return result;
    }

    private static void fill(BranchPool target, List<BytecodeInstruction> instructions,
                             Set<String> branchlessMethods) {
        for (BytecodeInstruction ins : instructions) {
            target.registerAsBranch(ins);
        }
        for (String method : branchlessMethods) {
            target.addBranchlessMethod(CLASS_NAME, method, 0);
        }
    }

    private static List<LabelNode> labelsOf(BytecodeInstruction ins) {
        if (ins.getASMNode() instanceof TableSwitchInsnNode) {
            return ((TableSwitchInsnNode) ins.getASMNode()).labels;
        }
        if (ins.getASMNode() instanceof LookupSwitchInsnNode) {
            return ((LookupSwitchInsnNode) ins.getASMNode()).labels;
        }
        return new ArrayList<>();
    }

    private void assertNoTraceOf(BytecodeInstruction ins, Set<Integer> ids) {
        assertFalse(pool.isKnownAsBranch(ins));
        for (int id : ids) {
            assertNull(pool.getBranch(id));
        }
        for (LabelNode label : labelsOf(ins)) {
            assertNull(pool.getBranchForLabel(label));
        }
        if (ins.isSwitch()) {
            assertThrows(IllegalArgumentException.class, () -> pool.getDefaultBranchForSwitch(ins));
        }
    }

    @Test
    public void clearingAClassLeavesNoTraceInAnyRegistry() {
        List<BytecodeInstruction> instructions = instructionsInIdOrder(pool);
        assertTrue(instructions.size() >= 2, "expected an if and a switch");
        Set<Integer> ids = new LinkedHashSet<>();
        for (Branch b : pool.getAllBranches()) {
            ids.add(b.getActualBranchId());
        }

        pool.clear(CLASS_NAME);

        for (BytecodeInstruction ins : instructions) {
            assertNoTraceOf(ins, ids);
        }
        assertTrue(pool.getAllBranches().isEmpty());
        assertEquals(0, pool.getBranchCountForClass(CLASS_NAME));
        assertEquals(0, pool.getBranchCounter());
        assertFalse(pool.knownClasses().contains(CLASS_NAME));
        assertTrue(pool.getBranchlessMethods(CLASS_NAME).isEmpty());
        assertTrue(pool.getMethodsPrefix(CLASS_NAME).isEmpty());
    }

    @Test
    public void reregisteringAClearedClassNumbersItAsAFreshPoolWould() {
        List<BytecodeInstruction> instructions = instructionsInIdOrder(pool);
        Set<String> branchless = new LinkedHashSet<>(pool.getBranchlessMethods(CLASS_NAME));
        List<String> original = signature(pool);

        BranchPool fresh = new BranchPool();
        fill(fresh, instructions, branchless);
        assertEquals(original, signature(fresh));

        pool.clear(CLASS_NAME);
        fill(pool, instructions, branchless);

        assertEquals(original, signature(pool));
    }

    @Test
    public void clearingAMethodLeavesNoTraceAndKeepsTheOtherMethods() {
        Set<String> branchless = new LinkedHashSet<>(pool.getBranchlessMethods(CLASS_NAME));
        List<String> original = signature(pool);
        List<BytecodeInstruction> cleared = new ArrayList<>();
        Set<Integer> ids = new LinkedHashSet<>();
        for (Branch b : pool.retrieveBranchesInMethod(CLASS_NAME, DAY_TYPE)) {
            ids.add(b.getActualBranchId());
            if (!cleared.contains(b.getInstruction())) {
                cleared.add(b.getInstruction());
            }
        }
        assertFalse(ids.isEmpty());
        int others = pool.getBranchCountForMethod(CLASS_NAME, SIGN);

        pool.clear(CLASS_NAME, DAY_TYPE);

        for (BytecodeInstruction ins : cleared) {
            assertNoTraceOf(ins, ids);
        }
        assertEquals(0, pool.getBranchCountForMethod(CLASS_NAME, DAY_TYPE));
        assertEquals(others, pool.getBranchCountForMethod(CLASS_NAME, SIGN));
        assertEquals(others, pool.getAllBranches().size());

        // The cleared method held the highest ids, so registering it again restores them.
        for (BytecodeInstruction ins : cleared) {
            pool.registerAsBranch(ins);
        }
        assertEquals(original, signature(pool));
        assertEquals(branchless, new LinkedHashSet<>(pool.getBranchlessMethods(CLASS_NAME)));
    }

    @Test
    public void clearingAnEarlierMethodNeverHandsOutAnIdInUse() {
        int total = pool.getAllBranches().size();
        List<BytecodeInstruction> earlier = new ArrayList<>();
        for (Branch b : pool.retrieveBranchesInMethod(CLASS_NAME, SIGN)) {
            earlier.add(b.getInstruction());
        }

        pool.clear(CLASS_NAME, SIGN);
        for (BytecodeInstruction ins : earlier) {
            pool.registerAsBranch(ins);
        }

        assertEquals(total, pool.getAllBranches().size());
        Set<Integer> ids = new LinkedHashSet<>();
        for (Branch b : pool.getAllBranches()) {
            assertTrue(ids.add(b.getActualBranchId()), "duplicate id " + b.getActualBranchId());
            assertEquals(b, pool.getBranch(b.getActualBranchId()));
        }
    }

    @Test
    public void snapshotsDoNotChangeWhenTheClassIsReinstrumented() {
        Set<String> branchless = new LinkedHashSet<>(pool.getBranchlessMethods(CLASS_NAME));
        BytecodeInstruction sw = null;
        for (Branch b : pool.getAllBranches()) {
            if (b.isSwitchCaseBranch()) {
                sw = b.getInstruction();
            }
        }
        List<Branch> cases = pool.getCaseBranchesForSwitch(sw);
        int caseCount = cases.size();
        Set<String> branchlessView = pool.getBranchlessMethods(CLASS_NAME);
        Collection<Branch> view = pool.getAllBranches();
        int viewSize = view.size();
        assertTrue(viewSize > 0);

        pool.clear(CLASS_NAME);

        assertEquals(viewSize, view.size());
        assertEquals(caseCount, cases.size());
        assertEquals(branchless, branchlessView);
    }

    @Test
    public void readersIteratingWhileTheClassIsReinstrumentedSeeNoCorruption() throws Exception {
        List<BytecodeInstruction> instructions = instructionsInIdOrder(pool);
        Set<String> branchless = new LinkedHashSet<>(pool.getBranchlessMethods(CLASS_NAME));
        List<String> original = signature(pool);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 2000; i++) {
                    pool.clear(CLASS_NAME);
                    fill(pool, instructions, branchless);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        Thread reader = new Thread(() -> {
            try {
                while (writer.isAlive()) {
                    for (Branch b : pool.getAllBranches()) {
                        assertTrue(b.getActualBranchId() > 0);
                    }
                    for (String m : pool.getBranchlessMethods(CLASS_NAME)) {
                        assertTrue(branchless.contains(m));
                    }
                    pool.retrieveBranchesInMethod(CLASS_NAME, DAY_TYPE);
                    pool.knownClasses();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(original, signature(pool));
    }
}
