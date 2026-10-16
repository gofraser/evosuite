package org.evosuite.testcase.synthesizer.var;

import org.evosuite.testcase.variable.VariableReference;

import java.util.List;

public class VarRelevance {
    /**
     * Given an instruction (or operand), the source code variables in test which can influence its value.
     */
    public List<VariableReference> influentialVars;

    /**
     * Given an instruction (or operand), the source code variable which can exactly correspond to it.
     *
     * <p>This matched variable can typically be used to construct the children variable.
     */
    public List<VariableReference> matchedVars;

    /**
     * Creates a relevance record from the influential and the exactly matching variables.
     *
     * @param influentialVars the test variables that can influence the instruction's value
     * @param matchedVars the test variables that correspond exactly to the instruction
     */
    public VarRelevance(List<VariableReference> influentialVars, List<VariableReference> matchedVars) {
        super();
        this.influentialVars = influentialVars;
        this.matchedVars = matchedVars;
    }

    /**
     * Adds the variables of another relevance record that this record does not contain yet.
     *
     * @param relevance the record to merge into this one
     */
    public void merge(VarRelevance relevance) {
        if (influentialVars == null) {
            this.influentialVars = relevance.influentialVars;
        } else {
            for (VariableReference variable : relevance.influentialVars) {
                if (!this.influentialVars.contains(variable)) {
                    this.influentialVars.add(variable);
                }
            }
        }

        if (matchedVars == null) {
            this.matchedVars = relevance.matchedVars;
        } else {
            for (VariableReference variable : relevance.matchedVars) {
                if (!this.matchedVars.contains(variable)) {
                    this.matchedVars.add(variable);
                }
            }
        }

    }


}
