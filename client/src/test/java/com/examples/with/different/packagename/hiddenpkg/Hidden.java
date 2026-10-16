package com.examples.with.different.packagename.hiddenpkg;

/** Package-private class with a public constructor, like xerces' PSVIErrorList. */
final class Hidden implements Visible {
    public Hidden(String[] values, boolean flag) {
    }

    @Override
    public int size() {
        return 0;
    }
}
