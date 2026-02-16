/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.util.random;

import org.evosuite.runtime.mock.StaticReplacementMock;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Stream;

/**
 * Deterministic replacement for {@link RandomGeneratorFactory}.
 */
public class MockRandomGeneratorFactory implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return RandomGeneratorFactory.class.getName();
    }

    public static RandomGeneratorFactory<RandomGenerator> getDefault() {
        return RandomGeneratorFactory.of("L64X128MixRandom");
    }

    public static RandomGeneratorFactory<RandomGenerator> of(String name) {
        return RandomGeneratorFactory.of(name);
    }

    @SuppressWarnings("unchecked")
    public static Stream<RandomGeneratorFactory<RandomGenerator>> all() {
        return (Stream<RandomGeneratorFactory<RandomGenerator>>) (Stream<?>) RandomGeneratorFactory.all();
    }

    public static RandomGenerator create(RandomGeneratorFactory<?> factory) {
        return MockRandomGenerator.deterministic();
    }

    public static RandomGenerator create(RandomGeneratorFactory<?> factory, long seed) {
        return MockRandomGenerator.deterministic(seed);
    }
}
