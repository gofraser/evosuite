package com.examples.with.different.packagename.synthprotected.base;

import java.util.List;

/** Base class in another package than its subclass: its protected members are not callable from the subclass's package. */
public abstract class ProtectedInitBase {

    protected List<String> items;

    protected void initialize(List<String> input) {
        this.items = input;
    }

    /** Package-private: callable only from this package, like JSDocInfo.declareParam. */
    boolean declareItems(List<String> input) {
        this.items = input;
        return true;
    }

    public void setItems(List<String> input) {
        this.items = input;
    }
}
