package com.examples.with.different.packagename.fields;

/** Public gateway to the package-private {@link HiddenStaticHolder}. */
public class HiddenStaticHolderAccess {
    public static Class<?> holderClass() {
        return HiddenStaticHolder.class;
    }

    public static int read() {
        return HiddenStaticHolder.hiddenStatic;
    }
}
