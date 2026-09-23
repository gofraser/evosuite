/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.runtime.util;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Frame computation reads the bytecode of both types through the thread's context class
 * loader, but a thread is allowed not to have one — a {@code ForkJoinPool} worker is the
 * common case. The cache is keyed through a null-safe helper, so when the lookup itself
 * used the raw context loader it threw, and the resulting "cannot resolve this type" was
 * filed under the fallback loader, where healthy threads then read it back and widened
 * their frames to {@code Object}.
 */
class ComputeClassWriterLoaderTest {

    /** Exposes the protected hook ASM would call. */
    private static final class Probe extends ComputeClassWriter {
        Probe() {
            super(ClassWriter.COMPUTE_FRAMES);
        }

        String commonSuperClass(String type1, String type2) {
            return getCommonSuperClass(type1, type2);
        }
    }

    @Test
    void resolvesTypesOnAThreadWithoutAContextClassLoader() throws InterruptedException {
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                answer.set(new Probe().commonSuperClass(
                        "java/util/ArrayList", "java/util/LinkedList"));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.setContextClassLoader(null);
        worker.start();
        worker.join();

        assertNull(failure.get(), "frame computation must not depend on a context class loader");
        assertEquals("java/util/AbstractList", answer.get());
    }

    @Test
    void aThreadWithoutAContextClassLoaderDoesNotPoisonTheCacheForOthers() throws InterruptedException {
        Thread worker = new Thread(() -> new Probe().commonSuperClass(
                "java/util/ArrayList", "java/util/LinkedList"));
        worker.setContextClassLoader(null);
        worker.start();
        worker.join();

        // Same question on a normal thread: it shares the fallback cache bucket, so a
        // MISSING entry written by the worker would surface here as Object.
        assertEquals("java/util/AbstractList",
                new Probe().commonSuperClass("java/util/ArrayList", "java/util/LinkedList"));
    }
}
