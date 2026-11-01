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

package org.evosuite.coverage.fbranch;

import org.evosuite.coverage.dataflow.DefUsePool;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.RawControlFlowGraph;

import java.util.HashMap;
import java.util.Map;

/**
 * Idempotent per-method registration of all def-use instructions of a raw CFG into the
 * {@link DefUsePool}. Needed by the EvoObj slicer before def-use queries. Ported from the
 * {@code evosuite-plus-plus} fork.
 */
public class FBranchDefUseAnalyzer {
    public static Map<String, Boolean> analyzedMethods = new HashMap<>();

    /**
     * Registers all def-use instructions of a raw CFG in the {@link DefUsePool}, once per method.
     *
     * @param completeCFG the raw control flow graph of the method
     * @return whether all def-use instructions could be registered
     */
    public static boolean analyze(RawControlFlowGraph completeCFG) {
        String methodName = completeCFG.getClassName() + "#" + completeCFG.getMethodName();
        if (analyzedMethods.containsKey(methodName)) {
            return analyzedMethods.get(methodName);
        }

        boolean isValid = true;
        for (BytecodeInstruction v : completeCFG.vertexSet()) {
            if (v.isDefUse()) {

                if (v.isMethodCallOfField()) {
                    // keep track of field method calls, though we do not know
                    // how to handle them at this point during the analysis
                    // (need complete CCFGs first)
                    if (!DefUsePool.isKnownAsFieldMethodCall(v)) {
                        DefUsePool.addAsFieldMethodCall(v);
                    }
                } else {
                    // keep track of uses
                    try {
                        if (v.isUse() && !DefUsePool.isKnownAsUse(v)) {
                            boolean success = DefUsePool.addAsUse(v);
                            if (!success && !DefUsePool.isKnownAsUse(v)) {
                                isValid = false;
                            }
                        }
                        // keep track of definitions
                        if (v.isDefinition() && !DefUsePool.isKnownAsDefinition(v)) {
                            DefUsePool.addAsDefinition(v);
                        }
                    } catch (Exception e) {
                        isValid = false;
                    }
                }
            }
        }

        analyzedMethods.put(methodName, isValid);

        return isValid;
    }
}
