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
package org.evosuite.graphs.cfg;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The pool is shared by the thread that loads classes and by threads that read it while an
 * abandoned test execution is still loading a class.
 */
public class BytecodeInstructionPoolConcurrencyTest {

    private static final int WRITERS = 4;
    private static final int METHODS_PER_WRITER = 400;
    private static final int INSTRUCTIONS_PER_METHOD = 5;

    @Test
    public void registrationAndLookupMayOverlap() throws Exception {
        ClassLoader loader = new ClassLoader(null) { };
        BytecodeInstructionPool pool = BytecodeInstructionPool.getInstance(loader);
        try {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int w = 0; w < WRITERS; w++) {
                final int writer = w;
                threads.add(new Thread(() -> {
                    await(start);
                    try {
                        for (int m = 0; m < METHODS_PER_WRITER; m++) {
                            String method = "m" + writer + "_" + m;
                            for (int i = 0; i < INSTRUCTIONS_PER_METHOD; i++) {
                                pool.registerInstruction(new BytecodeInstruction(loader, "C", method, i, i,
                                        new InsnNode(Opcodes.NOP)));
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }));
            }
            for (int r = 0; r < WRITERS; r++) {
                threads.add(new Thread(() -> {
                    await(start);
                    try {
                        for (int i = 0; i < 2000; i++) {
                            pool.getInstructionsIn("C");
                            pool.knownMethods("C");
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }));
            }
            threads.forEach(Thread::start);
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(WRITERS * METHODS_PER_WRITER, pool.knownMethods("C").size());
            assertEquals(WRITERS * METHODS_PER_WRITER * INSTRUCTIONS_PER_METHOD,
                    pool.getInstructionsIn("C").size());
        } finally {
            BytecodeInstructionPool.release(loader);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
