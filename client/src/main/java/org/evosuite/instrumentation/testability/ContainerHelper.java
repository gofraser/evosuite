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
package org.evosuite.instrumentation.testability;

import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.setup.TestCluster;

import java.util.Collection;
import java.util.Map;

/**
 * Created by Andrea Arcuri on 26/03/15.
 */
public class ContainerHelper {

    /**
     * Helper function that is called instead of Map.isEmpty
     *
     * @param m a {@link java.util.Map} object.
     * @return a int.
     */
    public static int mapIsEmpty(Map<?, ?> m) {
        return m.isEmpty() ? BooleanHelper.TRUE : -m.size();
    }

    /**
     * Helper function that is called instead of Collection.isEmpty
     *
     * @param c a {@link java.util.Collection} object.
     * @return a int.
     */
    public static int collectionIsEmpty(Collection<?> c) {
        return c.isEmpty() ? BooleanHelper.TRUE : -c.size();
    }

    /**
     * Maximum number of elements to iterate when examining a collection.
     * Guards against infinite loops when the collection (or its iterator)
     * is a mock whose hasNext() always returns true.
     */
    private static final int MAX_ITERATION = 10_000;

    /**
     * Helper function that is called instead of Collection.contains.
     * <p>
     * The sign of the result is always the outcome of the real {@code contains} call, so that
     * the instrumented class behaves exactly as it does without instrumentation: containers
     * that do not decide membership with a linear {@code equals} scan (a {@code TreeSet}
     * compares, a {@code HashSet} hashes, a {@code SetUniqueList} delegates) keep throwing what
     * they throw and answering what they answer. The scan only supplies the distance.
     *
     * @param c  a {@link java.util.Collection} object.
     * @param o1 a {@link java.lang.Object} object.
     * @return a int.
     */
    public static int collectionContains(Collection<?> c, Object o1) {
        if (o1 != null) {
            TestCluster.getInstance().addCastClassForContainer(o1.getClass());
        }
        return combine(c.contains(o1), c, o1);
    }

    /**
     * Combines the answer of the container itself with the result of a scan: positive exactly
     * when the container found the element.
     */
    private static int combine(boolean found, Collection<?> elements, Object o1) {
        int scanned;
        try {
            scanned = scanContains(elements, o1);
        } catch (RuntimeException | StackOverflowError e) {
            // Only the distance is lost: the scan reads the container the way the program never
            // does, so it may meet an iterator or an element that cannot be traversed.
            scanned = -1;
        }
        if (found) {
            return Math.max(scanned, 1);
        }
        return scanned > 0 ? -1 : scanned;
    }

    private static int scanContains(Collection<?> c, Object o1) {
        int matching = 0;
        double minDistance = Double.MAX_VALUE;
        int count = 0;
        for (Object o2 : c) {
            if (++count > MAX_ITERATION) {
                break;
            }
            if (o2 == o1 || (o2 != null && o2.equals(o1))) {
                matching++;
            } else {
                if (o2 != null && o1 != null) {
                    if (o2.getClass().equals(o1.getClass())) {
                        if (o1 instanceof Number) {
                            Number n1 = (Number) o1;
                            Number n2 = (Number) o2;
                            minDistance = Math.min(minDistance,
                                    Math.abs(n1.doubleValue()
                                            - n2.doubleValue()));
                        } else if (o2 instanceof String) {
                            ConstantPoolManager.addDynamicConstantIfTracing(o1);
                            minDistance = Math.min(minDistance,
                                    StringHelper.editDistance((String) o1, (String) o2));
                        }
                    }
                }
            }
        }
        if (matching > 0) {
            return matching;
        }
        if (minDistance == Double.MAX_VALUE) {
            return -c.size() - 1;
        }
        return -1 * (int) Math.ceil(BooleanHelper.K * minDistance / (minDistance + 1.0));
    }

    /**
     * Helper function that is called instead of Collection.containsAll
     *
     * @param c  a {@link java.util.Collection} object.
     * @param c2 a {@link java.util.Collection} object.
     * @return a int.
     */
    public static int collectionContainsAll(Collection<?> c, Collection<?> c2) {
        if (c.containsAll(c2)) {
            return c2.size() + 1;
        }
        int mismatch = 0;
        try {
            int count = 0;
            for (Object o : c2) {
                if (++count > MAX_ITERATION) {
                    break;
                }
                if (!c.contains(o)) {
                    mismatch++;
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            mismatch = 0;
        }
        return -Math.max(mismatch, 1);
    }

    /**
     * Helper function that is called instead of Map.containsKey
     *
     * @param o1 a {@link java.lang.Object} object.
     * @param m  a {@link java.util.Map} object.
     * @return a int.
     */
    public static int mapContainsKey(Map<?, ?> m, Object o1) {
        if (o1 != null) {
            TestCluster.getInstance().addCastClassForContainer(o1.getClass());
        }

        return combine(m.containsKey(o1), m.keySet(), o1);
    }

    /**
     * Helper function that is called instead of Map.containsValue
     *
     * @param o1 a {@link java.lang.Object} object.
     * @param m  a {@link java.util.Map} object.
     * @return a int.
     */
    public static int mapContainsValue(Map<?, ?> m, Object o1) {
        if (o1 != null) {
            TestCluster.getInstance().addCastClassForContainer(o1.getClass());
        }

        return combine(m.containsValue(o1), m.values(), o1);
    }

}
