package org.maven_test_project.j4;

public class Range {

    private final int low;
    private final int high;

    public Range(int low, int high) {
        if (low > high) {
            throw new IllegalArgumentException("low > high");
        }
        this.low = low;
        this.high = high;
    }

    public boolean contains(int x) {
        return x >= low && x <= high;
    }
}
