/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.archive;

import org.evosuite.Properties;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ArchiveTest {

    @Mock
    private LineCoverageTestFitness target;

    @Mock
    private TestChromosome chromosome;

    private Properties.Criterion[] originalCriteria;

    @BeforeEach
    public void setUp() {
        originalCriteria = Properties.CRITERION;
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.LINE};
        when(target.getTargetClass()).thenReturn("Foo");
        when(target.getTargetMethod()).thenReturn("bar");
    }

    @AfterEach
    public void tearDown() {
        Properties.CRITERION = originalCriteria;
    }

    @Test
    public void testCoverageArchiveAddTarget() {
        CoverageArchive archive = new CoverageArchive();
        archive.addTarget(target);
        assertTrue(archive.hasTarget(target));
        assertEquals(1, archive.getNumberOfTargets());
        assertEquals(1, archive.getNumberOfUncoveredTargets());
        assertEquals(0, archive.getNumberOfCoveredTargets());
    }

    @Test
    public void testCoverageArchiveUpdateArchive() {
        CoverageArchive archive = new CoverageArchive();
        archive.addTarget(target);

        archive.updateArchive(target, chromosome, 0.0);

        assertEquals(1, archive.getNumberOfCoveredTargets());
        assertEquals(0, archive.getNumberOfUncoveredTargets());
        assertTrue(archive.hasSolution(target));
        assertEquals(chromosome, archive.getSolution(target));
    }

    @Test
    public void testMIOArchiveAddTarget() {
        MIOArchive archive = new MIOArchive();
        archive.addTarget(target);
        assertTrue(archive.hasTarget(target));
        assertEquals(1, archive.getNumberOfTargets());
    }
}
