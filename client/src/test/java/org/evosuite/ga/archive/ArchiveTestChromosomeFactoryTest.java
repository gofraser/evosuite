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
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArchiveTestChromosomeFactoryTest {

    private Properties.ArchiveType archiveType;
    private Properties.Criterion[] criteria;
    private int seedMutations;
    private double seedClone;

    @BeforeEach
    void setUp() {
        archiveType = Properties.ARCHIVE_TYPE;
        criteria = Properties.CRITERION;
        seedMutations = Properties.SEED_MUTATIONS;
        seedClone = Properties.SEED_CLONE;
        Properties.ARCHIVE_TYPE = Properties.ArchiveType.COVERAGE;
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.LINE};
        Properties.SEED_CLONE = 1.0;
        CoverageArchive.instance.reset();
    }

    @AfterEach
    void tearDown() {
        CoverageArchive.instance.reset();
        Properties.ARCHIVE_TYPE = archiveType;
        Properties.CRITERION = criteria;
        Properties.SEED_MUTATIONS = seedMutations;
        Properties.SEED_CLONE = seedClone;
    }

    @Test
    void noSeedMutationsClonesFromArchiveWithoutMutating() {
        LineCoverageTestFitness target = mock(LineCoverageTestFitness.class);
        when(target.getTargetClass()).thenReturn("Foo");
        when(target.getTargetMethod()).thenReturn("bar");
        CoverageArchive.instance.addTarget(target);
        TestChromosome solution = new TestChromosome();
        solution.setTestCase(new DefaultTestCase());
        CoverageArchive.instance.updateArchive(target, solution, 0.0);

        Properties.SEED_MUTATIONS = 0;

        assertNotNull(new ArchiveTestChromosomeFactory().getChromosome());
    }
}
