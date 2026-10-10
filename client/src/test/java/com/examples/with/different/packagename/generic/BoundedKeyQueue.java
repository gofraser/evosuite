package com.examples.with.different.packagename.generic;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * A class type variable with a self-referential bound and several parameters of that type, in
 * the manner of summa's WindowQueue.
 */
public class BoundedKeyQueue<T extends Comparable<? super T>> extends PriorityQueue<T> {

    private final T lowerBound;
    private final T upperBound;

    public BoundedKeyQueue(Comparator<T> comparator, T lowerBound, T upperBound) {
        super(10, comparator);
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    public T getLowerBound() {
        return lowerBound;
    }

    public T getUpperBound() {
        return upperBound;
    }
}
