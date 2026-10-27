package org.evosuite.testcase.synthesizer.var;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
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
            MethodStatement methodStat = test.findTargetMethodCallStatement();
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
