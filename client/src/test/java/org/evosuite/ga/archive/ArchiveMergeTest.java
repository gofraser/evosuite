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
package org.evosuite.ga.archive;

import org.evosuite.Properties;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Merging the archive into a suite must leave {@code TEST_ARCHIVE} as it found it. */
class ArchiveMergeTest {

    private boolean originalTestArchive;

    @BeforeEach
    void rememberTestArchive() {
        originalTestArchive = Properties.TEST_ARCHIVE;
    }

    @AfterEach
    void restoreTestArchive() {
        Properties.TEST_ARCHIVE = originalTestArchive;
    }

    @Test
    void coverageArchiveKeepsDisabledArchiveDisabled() {
        Properties.TEST_ARCHIVE = false;

        new CoverageArchive().createMergedSolution(new TestSuiteChromosome());

        assertFalse(Properties.TEST_ARCHIVE);
    }

    @Test
    void coverageArchiveRestoresArchiveWhenMergeFails() {
        Properties.TEST_ARCHIVE = true;

        assertThrows(IllegalStateException.class,
                () -> new CoverageArchive().createMergedSolution(failingSuite()));

        assertTrue(Properties.TEST_ARCHIVE);
    }

    @Test
    void mioArchiveKeepsDisabledArchiveDisabled() {
        Properties.TEST_ARCHIVE = false;

        new MIOArchive().createMergedSolution(new TestSuiteChromosome());

        assertFalse(Properties.TEST_ARCHIVE);
    }

    @Test
    void mioArchiveRestoresArchiveWhenMergeFails() {
        Properties.TEST_ARCHIVE = true;

        assertThrows(IllegalStateException.class,
                () -> new MIOArchive().createMergedSolution(failingSuite()));

        assertTrue(Properties.TEST_ARCHIVE);
    }

    private static TestSuiteChromosome failingSuite() {
        TestSuiteChromosome suite = mock(TestSuiteChromosome.class);
        when(suite.clone()).thenThrow(new IllegalStateException("clone failed"));
        return suite;
    }
}
