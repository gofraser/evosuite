package com.examples.with.different.packagename.objectconstruction.tuning;

/** Fixture whose uncovered branch is controlled by one primitive object field. */
public class PrimitiveFieldTuningExample {

    public PrimitiveFieldTuningState state;

    public boolean target(int ignored) {
        return state.getBranchValue() != 0;
    }
}
