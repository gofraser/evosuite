/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.graphs.interprocedural;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InterproceduralGraphAnalyzerBoundaryTest {

    @Test
    public void recognizesPlatformImplementationBoundaries() {
        assertTrue(InterproceduralGraphAnalyzer.isPlatformImplementationClass("java.lang.Class"));
        assertTrue(InterproceduralGraphAnalyzer.isPlatformImplementationClass(
                "jdk.internal.reflect.ReflectionFactory"));
        assertTrue(InterproceduralGraphAnalyzer.isPlatformImplementationClass("sun.misc.Unsafe"));
        assertFalse(InterproceduralGraphAnalyzer.isPlatformImplementationClass(
                "ch.jalu.configme.beanmapper.context.MappingContextImpl"));
    }
}
