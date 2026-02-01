package org.evosuite.coverage.mutation;

import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MutationPoolTest {

    @Before
    public void setUp() {
        // Clear global state if necessary, though we use new ClassLoaders
    }

    @Test
    public void testSingletonPerClassLoader() {
        ClassLoader cl1 = new ClassLoader() {};
        ClassLoader cl2 = new ClassLoader() {};

        MutationPool pool1 = MutationPool.getInstance(cl1);
        MutationPool pool2 = MutationPool.getInstance(cl1);
        MutationPool pool3 = MutationPool.getInstance(cl2);

        assertSame("Same classloader should return same pool instance", pool1, pool2);
        assertNotSame("Different classloader should return different pool instance", pool1, pool3);
    }

    @Test
    public void testAddAndRetrieveMutation() {
        ClassLoader cl = new ClassLoader() {};
        MutationPool pool = MutationPool.getInstance(cl);

        BytecodeInstruction instruction = mock(BytecodeInstruction.class);
        when(instruction.getLineNumber()).thenReturn(10);
        AbstractInsnNode mutationNode = new LabelNode();
        InsnList distance = new InsnList();

        Mutation m = pool.addMutation("MyClass", "myMethod", "Mut1", instruction, mutationNode, distance);

        assertNotNull(m);
        assertEquals("MyClass", m.getClassName());
        assertEquals("myMethod", m.getMethodName());

        List<Mutation> mutations = pool.retrieveMutationsInMethod("MyClass", "myMethod");
        assertEquals(1, mutations.size());
        assertEquals(m, mutations.get(0));

        List<Mutation> allMutants = pool.getMutants();
        assertEquals(1, allMutants.size());
        assertEquals(m, allMutants.get(0));
    }

    @Test
    public void testThreadSafety() throws InterruptedException {
        ClassLoader cl = new ClassLoader() {};
        MutationPool pool = MutationPool.getInstance(cl);
        int numThreads = 10;
        int mutationsPerThread = 100;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < mutationsPerThread; j++) {
                        BytecodeInstruction instruction = mock(BytecodeInstruction.class);
                        when(instruction.getLineNumber()).thenReturn(10);
                        AbstractInsnNode mutationNode = new LabelNode();
                        InsnList distance = new InsnList();
                        pool.addMutation("Class", "Method", "Mut", instruction, mutationNode, distance);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(numThreads * mutationsPerThread, pool.getMutantCounter());
        assertEquals(numThreads * mutationsPerThread, pool.getMutants().size());
    }
}
