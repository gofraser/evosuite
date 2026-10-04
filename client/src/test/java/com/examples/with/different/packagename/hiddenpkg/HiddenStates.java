package com.examples.with.different.packagename.hiddenpkg;

/** Hands out a constant of the package-private enum. */
public class HiddenStates {
    public static HiddenStateHolder holder() {
        return new HiddenStateHolder();
    }

    /** Public view; the getter's declared type is Object, as for an interface-typed accessor. */
    public static class HiddenStateHolder {
        public Object getState() {
            return HiddenState.ACTIVE;
        }
    }
}
