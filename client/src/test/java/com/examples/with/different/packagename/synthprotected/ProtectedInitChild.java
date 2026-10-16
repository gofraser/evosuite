package com.examples.with.different.packagename.synthprotected;

import com.examples.with.different.packagename.synthprotected.base.ProtectedInitBase;

/** Class under test: inherits a protected initializer from a class in another package. */
public class ProtectedInitChild extends ProtectedInitBase {

    public boolean target() {
        return items == null;
    }
}
