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
package org.evosuite.junit;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JUnitAnalyzerTmpDirTest {

    @Test
    public void tmpDirsAreDistinctAndEmpty() throws Exception {
        File first = JUnitAnalyzer.createNewTmpDir();
        File second = JUnitAnalyzer.createNewTmpDir();
        try {
            assertNotNull(first);
            assertNotNull(second);
            assertTrue(first.isDirectory());
            assertTrue(second.isDirectory());
            assertTrue(!first.equals(second));
            assertEquals(0, first.list().length);
        } finally {
            FileUtils.deleteQuietly(first);
            FileUtils.deleteQuietly(second);
        }
    }

    @Test
    public void tmpDirsCreatedByConcurrentThreadsAreDistinct() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<File>> futures = new ArrayList<>();
        Callable<File> task = JUnitAnalyzer::createNewTmpDir;
        for (int i = 0; i < 200; i++) {
            futures.add(pool.submit(task));
        }
        Set<File> dirs = new HashSet<>();
        try {
            for (Future<File> f : futures) {
                File dir = f.get();
                assertNotNull(dir);
                dirs.add(dir);
            }
            assertEquals(200, dirs.size());
        } finally {
            pool.shutdownNow();
            for (File dir : dirs) {
                FileUtils.deleteQuietly(dir);
            }
        }
    }

    @Test
    public void unusableTmpDirYieldsNull() throws Exception {
        String original = System.getProperty("java.io.tmpdir");
        File blocker = File.createTempFile("JUnitAnalyzerTmpDirBlocker", ".tmp");
        try {
            System.setProperty("java.io.tmpdir", blocker.getAbsolutePath());
            assertNull(JUnitAnalyzer.createNewTmpDir());
        } finally {
            System.setProperty("java.io.tmpdir", original);
            blocker.delete();
        }
    }
}
