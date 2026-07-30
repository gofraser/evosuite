/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package com.examples.with.different.packagename.objectconstruction.tuning;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regression fixture for javac's reuse of the same synthetic iterator local for consecutive
 * enhanced-for loops. Each loop predicate is controlled by exactly one map.
 */
public class ReusedLoopSlotExample {

    private final Map<String, Object> first = new ConcurrentHashMap<>();
    private final Map<String, Object> second = new ConcurrentHashMap<>();
    private final Map<String, Object> third = new ConcurrentHashMap<>();
    private final Map<String, Object> fourth = new ConcurrentHashMap<>();

    void putFirst(String key, Object value) {
        first.put(key, value);
    }

    void putFirstViaHelper(String key, Object value) {
        Map<String, Object> target = first;
        putInto(target, key, value);
    }

    private static void putInto(Map<String, Object> target, String key, Object value) {
        target.put(key, value);
    }

    void putSecond(String key, Object value) {
        second.put(key, value);
    }

    void putThird(String key, Object value) {
        third.put(key, value);
    }

    void putFourth(String key, Object value) {
        fourth.put(key, value);
    }

    public Map<String, Object> getFirst() {
        return first;
    }

    public Map<String, Object> getSecond() {
        return second;
    }

    public Map<String, Object> getThird() {
        return third;
    }

    public Map<String, Object> getFourth() {
        return fourth;
    }

    void copyFirstTo(Set<Object> output) {
        for (Object value : first.values()) {
            output.add(value);
        }
    }

    public int scan() {
        int count = 0;
        for (Object value : first.values()) {
            count += value == null ? 0 : 1;
        }
        for (Object value : second.values()) {
            count += value == null ? 0 : 1;
        }
        for (Object value : third.values()) {
            count += value == null ? 0 : 1;
        }
        for (Object value : fourth.values()) {
            count += value == null ? 0 : 1;
        }
        return count;
    }
}
