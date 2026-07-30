/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package com.examples.with.different.packagename.objectconstruction.wholeclass;

/**
 * Public entry with object-state branches but deliberately no callable receiver constructor or
 * factory. Used to verify that failed speculative entry construction is baseline-transparent.
 */
public final class UnconstructableWholeClassExample {

    private final int value;

    private UnconstructableWholeClassExample(int value) {
        this.value = value;
    }

    public boolean entry() {
        return value > 0;
    }
}
