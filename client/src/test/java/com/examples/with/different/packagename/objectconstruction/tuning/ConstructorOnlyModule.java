package com.examples.with.different.packagename.objectconstruction.tuning;

public final class ConstructorOnlyModule {

    private final boolean initialized;

    public ConstructorOnlyModule(boolean initialized) {
        this.initialized = initialized;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
