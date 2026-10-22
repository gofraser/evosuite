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
package org.evosuite.graphs;

import org.evosuite.graphs.ccfg.ClassControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The class control flow graph is derived from the per-method graphs, so it must not outlive
 * them when they are cleared.
 */
public class GraphPoolCcfgCacheTest {

    private static final String CLASS_NAME = "CachedClass";

    private GraphPool pool;

    @BeforeEach
    public void setUp() {
        pool = GraphPool.getInstance(new ClassLoader() {
        });
        pool.registerRawCFG(methodCfg("m1"));
    }

    @AfterEach
    public void tearDown() {
        GraphPool.clearAll();
    }

    @Test
    public void rebuiltMethodGraphInvalidatesClassGraph() {
        ClassControlFlowGraph first = pool.getCCFG(CLASS_NAME);
        assertSame(first, pool.getCCFG(CLASS_NAME));

        pool.clear(CLASS_NAME, "m1");
        pool.registerRawCFG(methodCfg("m1"));

        assertNotSame(first, pool.getCCFG(CLASS_NAME));
    }

    @Test
    public void clearedClassInvalidatesClassGraph() {
        ClassControlFlowGraph first = pool.getCCFG(CLASS_NAME);

        pool.clear(CLASS_NAME);
        pool.registerRawCFG(methodCfg("m1"));

        assertNotSame(first, pool.getCCFG(CLASS_NAME));
    }

    @Test
    public void clearedPoolInvalidatesClassGraph() {
        ClassControlFlowGraph first = pool.getCCFG(CLASS_NAME);

        pool.clear();
        pool.registerRawCFG(methodCfg("m1"));

        assertNotSame(first, pool.getCCFG(CLASS_NAME));
    }

    private static RawControlFlowGraph methodCfg(String methodName) {
        RawControlFlowGraph cfg = mock(RawControlFlowGraph.class);
        when(cfg.getClassName()).thenReturn(CLASS_NAME);
        when(cfg.getMethodName()).thenReturn(methodName);

        BytecodeInstruction entry = instruction(methodName, 0);
        BytecodeInstruction exit = instruction(methodName, 1);
        when(cfg.determineEntryPoint()).thenReturn(entry);
        when(cfg.determineExitPoints()).thenReturn(Collections.singleton(exit));
        when(cfg.vertexSet()).thenReturn(new HashSet<>(Arrays.asList(entry, exit)));
        when(cfg.edgeSet()).thenReturn(Collections.emptySet());
        when(cfg.determineMethodCallsToOwnClass()).thenReturn(Collections.emptyList());
        return cfg;
    }

    private static BytecodeInstruction instruction(String methodName, int id) {
        BytecodeInstruction ins = mock(BytecodeInstruction.class);
        when(ins.getMethodName()).thenReturn(methodName);
        when(ins.getInstructionId()).thenReturn(id);
        when(ins.toString()).thenReturn(methodName + "_" + id);
        return ins;
    }
}
