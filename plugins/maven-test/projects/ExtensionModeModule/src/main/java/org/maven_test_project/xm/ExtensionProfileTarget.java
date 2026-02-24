package org.maven_test_project.xm;

public class ExtensionProfileTarget {

    public static int clampToNonNegative(int value) {
        return value < 0 ? 0 : value;
    }
}
