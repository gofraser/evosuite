package com.examples.with.different.packagename;

public enum EnumWithUserMethodsFixture {
    ONE;

    public static EnumWithUserMethodsFixture value(int ignored) {
        return ONE;
    }

    public int customValue() {
        return 42;
    }
}
