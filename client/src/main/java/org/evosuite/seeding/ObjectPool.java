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
package org.evosuite.seeding;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pool of interesting method sequences for different objects.
 * Thread-safe: uses ConcurrentHashMap so LLM enrichment can add sequences
 * while the search reads concurrently.
 *
 * @author Gordon Fraser
 */
public class ObjectPool implements Serializable {

    private static final long serialVersionUID = 2016387518459994272L;

    /**
     * The actual object pool. ConcurrentHashMap for thread-safe concurrent read/write.
     * Values use Collections.synchronizedSet wrapping to protect the inner Set.
     */
    protected final Map<GenericClass<?>, Set<TestCase>> pool = new ConcurrentHashMap<>();

    protected static final Logger logger = LoggerFactory.getLogger(ObjectPool.class);

    /**
     * Insert a new sequence for given Type.
     *
     * @param clazz    a {@link java.lang.reflect.Type} object.
     * @param sequence a {@link TestCase} object.
     */
    public void addSequence(GenericClass<?> clazz, TestCase sequence) {
        ObjectSequence seq = new ObjectSequence(clazz, sequence);
        addSequence(seq);
    }

    /**
     * Helper method to add sequences.
     *
     * @param sequence the sequence to add
     */
    private void addSequence(ObjectSequence sequence) {
        pool.computeIfAbsent(sequence.getGeneratedClass(),
                        k -> Collections.synchronizedSet(new HashSet<>()))
                .add(sequence.getSequence());
        logger.info("Added new sequence for {}", sequence.getGeneratedClass());
        logger.debug("Sequence code:\n{}", sequence.getSequence().toCode());
    }

    /**
     * Randomly choose a sequence for a given Type.
     *
     * @param clazz a {@link java.lang.reflect.Type} object.
     * @return a {@link org.evosuite.testcase.TestCase} object.
     */
    public TestCase getRandomSequence(GenericClass<?> clazz) {
        Set<TestCase> sequences = getSequences(clazz);
        if (sequences == null || sequences.isEmpty()) {
            return null;
        }
        return Randomness.choice(sequences);
    }

    /**
     * Retrieve all possible sequences for a given Type.
     * Returns a snapshot to avoid ConcurrentModificationException during search.
     *
     * @param clazz a {@link java.lang.reflect.Type} object.
     * @return a {@link java.util.Set} object.
     */
    public Set<TestCase> getSequences(GenericClass<?> clazz) {
        Set<TestCase> exact = pool.get(clazz);
        if (exact != null && !exact.isEmpty()) {
            // Return defensive copy to avoid CME if set is mutated concurrently
            synchronized (exact) {
                return new LinkedHashSet<>(exact);
            }
        }

        Set<Set<TestCase>> candidates = new LinkedHashSet<>();
        for (Map.Entry<GenericClass<?>, Set<TestCase>> entry : pool.entrySet()) {
            if (entry.getKey().isAssignableTo(clazz)) {
                Set<TestCase> val = entry.getValue();
                synchronized (val) {
                    candidates.add(new LinkedHashSet<>(val));
                }
            }
        }

        if (candidates.isEmpty()) {
            return Collections.emptySet();
        }

        return Randomness.choice(candidates);
    }

    public Set<GenericClass<?>> getClasses() {
        return pool.keySet();
    }

    /**
     * Check if there are sequences for given Type.
     * Safe for concurrent reads — iterates ConcurrentHashMap snapshot view.
     *
     * @param clazz a {@link java.lang.reflect.Type} object.
     * @return a boolean.
     */
    public boolean hasSequence(GenericClass<?> clazz) {
        Set<TestCase> exact = pool.get(clazz);
        if (exact != null && !exact.isEmpty()) {
            return true;
        }

        for (Map.Entry<GenericClass<?>, Set<TestCase>> entry : pool.entrySet()) {
            if (entry.getKey().isAssignableTo(clazz) && !entry.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public int getNumberOfClasses() {
        return pool.size();
    }

    public int getNumberOfSequences() {
        return pool.values().stream().mapToInt(Set::size).sum();
    }

    public boolean isEmpty() {
        return pool.isEmpty();
    }

    /**
     * Read a serialized pool.
     *
     * @param fileName the name of the file
     * @return the object pool
     */
    public static ObjectPool getPoolFromFile(String fileName) {
        try (InputStream in = new FileInputStream(fileName);
                ObjectInputStream objectIn = new ObjectInputStream(in)) {
            ObjectPool pool = (ObjectPool) objectIn.readObject();
            // TODO: Do we also need to call that in the other factory methods?
            pool.filterUnaccessibleTests();
            return pool;
        } catch (IOException | ClassNotFoundException e) {
            logger.error("Exception while trying to get object pool from {} , {}", fileName, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Removes tests containing inaccessible elements.
     */
    protected void filterUnaccessibleTests() {
        for (Set<TestCase> testSet : pool.values()) {
            synchronized (testSet) {
                Iterator<TestCase> testIterator = testSet.iterator();
                while (testIterator.hasNext()) {
                    TestCase currentTest = testIterator.next();
                    if (!currentTest.isAccessible()) {
                        logger.info("Removing test containing inaccessible elements");
                        testIterator.remove();
                    }
                }
            }
        }
    }

    /**
     * Convert a test suite to a pool.
     *
     * @param testSuite the test suite
     * @return the object pool
     */
    public static ObjectPool getPoolFromTestSuite(TestSuiteChromosome testSuite) {
        ObjectPool pool = new ObjectPool();

        for (TestChromosome testChromosome : testSuite.getTestChromosomes()) {
            TestCase test = testChromosome.getTestCase().clone();
            test.removeAssertions();

            Class<?> targetClass = Properties.getTargetClassAndDontInitialise();

            if (!testChromosome.hasException()
                    && test.hasObject(targetClass, test.size())) {
                pool.addSequence(GenericClassFactory.get(targetClass), test);
            }
        }

        return pool;
    }

    /**
     * Writes the object pool to a file.
     *
     * @param fileName the name of the file
     */
    public void writePool(String fileName) {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(fileName))) {
            out.writeObject(this);
        } catch (IOException e) {
            logger.warn("Error while writing pool to file {}: {}", fileName, e.getMessage(), e);
        }
    }

}
