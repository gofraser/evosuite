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
package org.evosuite.coverage.dataflow.analysis;

import org.evosuite.graphs.ccfg.CCFGMethodCallNode;
import org.evosuite.graphs.cfg.BytecodeInstruction;


/**
 * Represents a single invocation of a method during the Inter-Method pair
 * search.
 *
 * <p>This class is used to keep track of the current call stack during the
 * search and to differentiate different method calls to the same method.
 *
 * @author Andre Mis
 */
public class MethodCall {
    private final CCFGMethodCallNode methodCall;
    private final int invocationNumber;
    private final String calledMethod;

    /**
     * Initializes a new MethodCall with the given method call node, called method name,
     * and invocation number.
     *
     * @param methodCall the CCFG method call node.
     * @param calledMethod the name of the called method.
     * @param invocationNumber the invocation number.
     */
    public MethodCall(CCFGMethodCallNode methodCall, String calledMethod, int invocationNumber) {
        this.methodCall = methodCall;
        this.invocationNumber = invocationNumber;
        this.calledMethod = calledMethod;
    }

    /**
     * Checks if this method call represents the entry point of the search.
     *
     * @return true if this is the initial method call.
     */
    public boolean isInitialMethodCall() {
        return methodCall == null;
    }

    /**
     * Checks if this instance represents the call at the given instruction.
     *
     * @param callInstruction the bytecode instruction to check against.
     * @return true if this is a method call for the given instruction.
     */
    public boolean isMethodCallFor(BytecodeInstruction callInstruction) {
        if (methodCall == null) {
            return callInstruction == null;
        }
        return methodCall.getCallInstruction().equals(callInstruction);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + invocationNumber;
        result = prime * result
                + ((methodCall == null) ? 0 : methodCall.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        MethodCall other = (MethodCall) obj;
        if (invocationNumber != other.invocationNumber) {
            return false;
        }
        if (methodCall == null) {
            return other.methodCall == null;
        } else {
            return methodCall.equals(other.methodCall);
        }
    }

    @Override
    public String toString() {
        if (methodCall == null) {
            return "initCall for " + calledMethod + " " + invocationNumber;
        }
        return methodCall.getCalledMethod() + " " + invocationNumber;
    }

    /**
     * Returns the name of the called method.
     *
     * @return the called method name.
     */
    public String getCalledMethodName() {
        return calledMethod;
    }
}
