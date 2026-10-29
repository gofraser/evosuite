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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveUpdateSuppressionTest {

    private boolean testArchive;

    @BeforeEach
    void setUp() {
        testArchive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = true;
    }

    @AfterEach
    void tearDown() {
        Properties.TEST_ARCHIVE = testArchive;
    }

    @Test
    void updatesAreSuspendedOnlyInsideTheScope() {
        assertTrue(Archive.isUpdating());
        try (Archive.UpdateSuppression ignored = Archive.suppressUpdates()) {
            assertFalse(Archive.isUpdating());
        }
        assertTrue(Archive.isUpdating());
    }

    @Test
    void scopesNest() {
        try (Archive.UpdateSuppression outer = Archive.suppressUpdates()) {
            try (Archive.UpdateSuppression inner = Archive.suppressUpdates()) {
                assertFalse(Archive.isUpdating());
            }
            assertFalse(Archive.isUpdating());
        }
        assertTrue(Archive.isUpdating());
    }

    @Test
    void scopeIsClosedWhenTheEvaluationThrows() {
        assertThrows(IllegalStateException.class, () -> {
            try (Archive.UpdateSuppression ignored = Archive.suppressUpdates()) {
                throw new IllegalStateException();
            }
        });
        assertTrue(Archive.isUpdating());
    }

    @Test
    void closingTwiceReleasesOnlyOnce() {
        try (Archive.UpdateSuppression outer = Archive.suppressUpdates()) {
            Archive.UpdateSuppression inner = Archive.suppressUpdates();
            inner.close();
            inner.close();
            assertFalse(Archive.isUpdating());
        }
        assertTrue(Archive.isUpdating());
    }

    @Test
    void disabledArchiveNeverUpdatesAndKeepsItsConfiguration() {
        Properties.TEST_ARCHIVE = false;
        try (Archive.UpdateSuppression ignored = Archive.suppressUpdates()) {
            assertFalse(Archive.isUpdating());
        }
        assertFalse(Archive.isUpdating());
        assertFalse(Properties.TEST_ARCHIVE);
    }
}
