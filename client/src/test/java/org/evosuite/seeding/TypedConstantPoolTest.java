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
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the draws of the four pool flavours. The expected strings were recorded from the
 * separate per-flavour classes the typed pool replaced, so a change here means the search
 * sees different constants for the same seed.
 */
public class TypedConstantPoolTest {

    private String targetClass;
    private int dynamicPoolSize;
    private boolean restrictPool;
    private long maxSeededConstant;

    @BeforeEach
    public void setUp() {
        targetClass = Properties.TARGET_CLASS;
        dynamicPoolSize = Properties.DYNAMIC_POOL_SIZE;
        restrictPool = Properties.RESTRICT_POOL;
        maxSeededConstant = Properties.MAX_SEEDED_CONSTANT;
        Properties.TARGET_CLASS = "com.example.Foo";
        Properties.RESTRICT_POOL = false;
        Properties.MAX_SEEDED_CONSTANT = 0L;
        Properties.DYNAMIC_POOL_SIZE = 5;
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = targetClass;
        Properties.DYNAMIC_POOL_SIZE = dynamicPoolSize;
        Properties.RESTRICT_POOL = restrictPool;
        Properties.MAX_SEEDED_CONSTANT = maxSeededConstant;
    }

    private static final int DRAWS = 12;

    private static String draw(ConstantPool pool) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DRAWS; i++) {
            sb.append(pool.getRandomString()).append('|');
        }
        sb.append('\n');
        for (int i = 0; i < DRAWS; i++) {
            sb.append(pool.getRandomType()).append('|');
        }
        sb.append('\n');
        for (int i = 0; i < DRAWS; i++) {
            sb.append(pool.getRandomInt()).append('|');
        }
        sb.append('\n');
        for (int i = 0; i < DRAWS; i++) {
            sb.append(pool.getRandomLong()).append('|');
        }
        sb.append('\n');
        for (int i = 0; i < DRAWS; i++) {
            sb.append(pool.getRandomFloat()).append('|');
        }
        sb.append('\n');
        for (int i = 0; i < DRAWS; i++) {
            sb.append(pool.getRandomDouble()).append('|');
        }
        return sb.toString();
    }

    private static void fill(ConstantPool pool) {
        pool.add(null);
        pool.add(Boolean.TRUE);
        for (String s : new String[]{"beta", "alpha", "beta", "gamma", "delta", "alpha", "beta", "eps", "zeta"}) {
            pool.add(s);
        }
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i <= Properties.MAX_STRING; i++) {
            tooLong.append('x');
        }
        pool.add(tooLong.toString());
        pool.add(Type.getObjectType("com/example/Bar"));
        pool.add(Type.getType(String[][].class));
        pool.add(Type.getType(int.class));
        pool.add(Type.getObjectType("com/example/Bar"));
        for (int v : new int[]{7, -3, 7, 42, 7, 1000, 5, 5, 99}) {
            pool.add(v);
        }
        for (long v : new long[]{9L, -4L, 9L, 123456789012L, 3L, 3L, 8L}) {
            pool.add(v);
        }
        for (float v : new float[]{2.5f, -0.5f, 2.5f, 1e10f, 3.25f, 3.25f, 8f}) {
            pool.add(v);
        }
        for (double v : new double[]{2.5, -0.5, 2.5, 1e10, 3.25, 3.25, 8.0}) {
            pool.add(v);
        }
    }

    private static String run(Supplier<ConstantPool> factory) {
        Randomness.setSeed(4711L);
        ConstantPool pool = factory.get();
        String before = draw(pool);
        fill(pool);
        String filled = draw(pool);
        int removed10 = pool.pruneOversizedNumericConstants(10L);
        String pruned = draw(pool);
        int removed1 = pool.pruneOversizedNumericConstants(1L);
        String emptied = draw(pool);
        return before + "\n--\n" + filled + "\n--\nremoved " + removed10 + "\n" + pruned
                + "\n--\nremoved " + removed1 + "\n" + emptied + "\n";
    }

    private static String expected(String section) throws IOException {
        StringBuilder all = new StringBuilder();
        try (InputStream in = TypedConstantPoolTest.class.getResourceAsStream("typed-pool-draws.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            boolean inSection = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("== ")) {
                    inSection = line.substring(3).equals(section);
                } else if (inSection) {
                    all.append(line).append('\n');
                }
            }
        }
        return all.toString().trim();
    }

    private static void assertDraws(String section, ConstantPool pool) throws IOException {
        assertEquals(expected(section), run(() -> pool).trim());
    }

    @Test
    public void staticUniformDraws() throws IOException {
        assertDraws("static", ConstantPoolManager.newStaticPool(false));
    }

    @Test
    public void staticFrequencyDraws() throws IOException {
        assertDraws("staticvp", ConstantPoolManager.newStaticPool(true));
    }

    @Test
    public void dynamicUniformDraws() throws IOException {
        assertDraws("dynamic", ConstantPoolManager.newDynamicPool(false));
    }

    @Test
    public void dynamicFrequencyDraws() throws IOException {
        assertDraws("dynamicvp", ConstantPoolManager.newDynamicPool(true));
    }

    @Test
    public void staticPoolsFlattenArraysAndDynamicPoolsKeepThem() {
        ConstantPool staticPool = ConstantPoolManager.newStaticPool(false);
        ConstantPool dynamicPool = ConstantPoolManager.newDynamicPool(false);
        staticPool.add(Type.getType(String[][].class));
        dynamicPool.add(Type.getType(String[][].class));
        assertTrue(drawsType(staticPool, Type.getType(String.class)));
        assertFalse(drawsType(staticPool, Type.getType(String[][].class)));
        assertTrue(drawsType(dynamicPool, Type.getType(String[][].class)));
    }

    private static boolean drawsType(ConstantPool pool, Type type) {
        Randomness.setSeed(1L);
        for (int i = 0; i < 200; i++) {
            if (pool.getRandomType().equals(type)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void numericSeedsDifferBetweenStaticAndDynamicPools() {
        assertEquals(Arrays.asList(-1, 0, 1), sorted(ConstantPoolManager.newStaticPool(false).getInts()));
        assertEquals(Arrays.asList(-1, 0, 1), sorted(ConstantPoolManager.newStaticPool(true).getInts()));
        assertEquals(Collections.singletonList(0), sorted(ConstantPoolManager.newDynamicPool(false).getInts()));
        assertEquals(Collections.singletonList(0), sorted(ConstantPoolManager.newDynamicPool(true).getInts()));
    }

    private static List<Integer> sorted(Collection<Integer> values) {
        List<Integer> list = new ArrayList<>(values);
        Collections.sort(list);
        return list;
    }

    @Test
    public void dynamicPoolsEvictTheOldestConstantStaticPoolsKeepAll() {
        Properties.DYNAMIC_POOL_SIZE = 3;
        ConstantPool dynamicPool = ConstantPoolManager.newDynamicPool(false);
        ConstantPool staticPool = ConstantPoolManager.newStaticPool(false);
        for (String s : new String[]{"a", "b", "c", "d"}) {
            dynamicPool.add(s);
            staticPool.add(s);
        }
        // the empty seed and "a" are evicted
        assertEquals(Arrays.asList("b", "c", "d"), new ArrayList<>(dynamicPool.getStrings()));
        assertEquals(5, staticPool.getStrings().size());
    }

    @Test
    public void frequencyPoolsCountOccurrencesWhenPruning() {
        ConstantPool uniform = ConstantPoolManager.newStaticPool(false);
        ConstantPool frequency = ConstantPoolManager.newStaticPool(true);
        for (ConstantPool pool : new ConstantPool[]{uniform, frequency}) {
            pool.add(500);
            pool.add(500);
        }
        assertEquals(1, uniform.pruneOversizedNumericConstants(100L));
        assertEquals(2, frequency.pruneOversizedNumericConstants(100L));
    }

    @Test
    public void toStringNamesThePoolKind() {
        assertTrue(ConstantPoolManager.newStaticPool(false).toString().startsWith("StaticConstantPool:{"));
        assertTrue(ConstantPoolManager.newDynamicPool(true).toString().startsWith("DynamicConstantPool:{"));
    }
}
