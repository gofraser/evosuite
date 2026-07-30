package com.examples.with.different.packagename.objectconstruction.wholeclass;

/**
 * A CIDR-shaped scalar parser. Its branches depend on array elements, but the array is created
 * inside the target method from an immutable String input, so there is no pre-call object state for
 * EvoObj to construct.
 */
public class LocalDerivedArrayWholeClassExample {

    public static boolean parse(String value) {
        String[] parts = value.split("/");
        if (parts.length != 2) {
            return false;
        }
        return Integer.parseInt(parts[1]) > 31;
    }
}
