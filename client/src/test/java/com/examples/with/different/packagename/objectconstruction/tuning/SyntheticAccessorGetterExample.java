package com.examples.with.different.packagename.objectconstruction.tuning;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixture whose private field is read by an anonymous class, so that javac (release 8) emits a
 * synthetic {@code access$000} getter next to the real {@code getItems()} getter.
 */
public class SyntheticAccessorGetterExample {

    private final List<String> items = new ArrayList<>();

    public List<String> getItems() {
        return items;
    }

    public Runnable task() {
        return new Runnable() {
            @Override
            public void run() {
                items.add("x");
            }
        };
    }

    public boolean target(int ignored) {
        return items.isEmpty();
    }
}
