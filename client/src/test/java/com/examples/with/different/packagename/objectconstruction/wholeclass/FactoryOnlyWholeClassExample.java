package com.examples.with.different.packagename.objectconstruction.wholeclass;

/** A whole-class fixture whose instances can only be obtained through a static factory. */
public final class FactoryOnlyWholeClassExample {

    private int state;

    private FactoryOnlyWholeClassExample(int state) {
        this.state = state;
    }

    public static FactoryOnlyWholeClassExample create(int state) {
        return new FactoryOnlyWholeClassExample(state);
    }

    public static FactoryOnlyWholeClassExample createNull() {
        return null;
    }

    public boolean entry(int expected) {
        if (state == expected) {
            return true;
        }
        return false;
    }
}
