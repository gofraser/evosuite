package org.evosuite.testcase.synthesizer;

import java.util.Set;

public class ParameterMatch {
    public boolean isMatch;
    public Set<Integer> parameterPoisitions;

    /**
     * Creates a match result.
     * @param isMatch whether the parameters match
     * @param parameterPoisitions the positions of the matching parameters
     */
    public ParameterMatch(boolean isMatch, Set<Integer> parameterPoisitions) {
        super();
        this.isMatch = isMatch;
        this.parameterPoisitions = parameterPoisitions;
    }

}
