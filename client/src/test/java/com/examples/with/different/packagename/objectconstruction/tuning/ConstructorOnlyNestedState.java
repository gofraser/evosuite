package com.examples.with.different.packagename.objectconstruction.tuning;

/** Nested state whose only writer is construction of an object that already exists at synthesis. */
public class ConstructorOnlyNestedState {

    private final ConstructorOnlyModule module;

    public ConstructorOnlyNestedState(ConstructorOnlyModule module) {
        this.module = module;
    }

    public boolean target() {
        return module != null && module.isInitialized();
    }

}
