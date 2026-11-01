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

package org.evosuite.testcase.synthesizer.var;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.testcase.synthesizer.VariableInTest;
import org.evosuite.testcase.variable.VariableReference;

import java.util.Map;

public class ThisVariableWrapper extends DepVariableWrapper {

    protected ThisVariableWrapper(DepVariable var) {
        super(var);
    }

    @Override
    public VarRelevance generateOrFindStatement(TestCase test, boolean isLeaf, VariableInTest variable,
            Map<DepVariableWrapper, VarRelevance> map, Branch b, boolean allowNullValue) {
        return VarRelevance.of(generateOrFind(test, isLeaf, variable.callerObject, map, b, allowNullValue));
    }

    public VariableReference generateOrFind(TestCase test, boolean isLeaf, VariableReference callerObject,
            Map<DepVariableWrapper, VarRelevance> map, Branch b, boolean allowNullValue) {
        return find(test, isLeaf, callerObject, map);
    }

    @Override
    public VariableReference find(TestCase test, boolean isLeaf, VariableReference callerObject,
            Map<DepVariableWrapper, VarRelevance> map) {
        if (this.parents.isEmpty()) {
            if (org.evosuite.testcase.synthesizer.TargetContext.activeConstructorEntry()) {
                ConstructorStatement constructorStat = findActiveConstructorStatement(test);
                if (constructorStat != null) {
                    return constructorStat.getReturnValue();
                }
            }
            MethodStatement methodStat = TargetContext.findTargetMethodCallStatement(test);
            if (methodStat != null) {
                VariableReference generatedVariable = methodStat.getCallee();
                return generatedVariable;
            }
        } else {
            for (DepVariableWrapper parentNode: this.parents) {
                if (map.get(parentNode) != null) {
                    VariableReference generatedVariable = map.get(parentNode).matchedVars.get(0);
                    return generatedVariable;
                }
            }
        }

        return null;
    }

    private ConstructorStatement findActiveConstructorStatement(TestCase test) {
        String signature = org.evosuite.testcase.synthesizer.TargetContext.activeMethodSignature();
        String className = org.evosuite.testcase.synthesizer.TargetContext.activeClassName();
        if (signature == null || signature.isEmpty()) {
            return null;
        }
        for (int i = 0; i < test.size(); i++) {
            if (test.getStatement(i) instanceof ConstructorStatement) {
                ConstructorStatement constructorStat = (ConstructorStatement) test.getStatement(i);
                if (constructorStat.getConstructor().getNameWithDescriptor().equals(signature)
                        && className != null && className.equals(
                                constructorStat.getConstructor().getDeclaringClass().getName())) {
                    return constructorStat;
                }
            }
        }
        return null;
    }

}
