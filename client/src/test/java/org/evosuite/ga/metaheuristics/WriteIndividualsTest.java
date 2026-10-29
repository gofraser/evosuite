/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.ga.FitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WriteIndividualsTest {

    private static final class FailingFitnessFunction extends FitnessFunction<SPEA2Test.TestChromosome> {
        private static final long serialVersionUID = 1L;

        @Override
        public double getFitness(SPEA2Test.TestChromosome individual) {
            throw new IllegalStateException("fitness not available");
        }

        @Override
        public boolean isMaximizationFunction() {
            return false;
        }
    }

    @TempDir
    Path reportDir;

    private boolean writeIndividuals;
    private String oldReportDir;
    private Properties.Algorithm algorithm;

    @BeforeEach
    public void setUp() {
        writeIndividuals = Properties.WRITE_INDIVIDUALS;
        oldReportDir = Properties.REPORT_DIR;
        algorithm = Properties.ALGORITHM;
        Properties.WRITE_INDIVIDUALS = true;
        Properties.REPORT_DIR = reportDir.toString();
        Properties.ALGORITHM = Properties.Algorithm.SPEA2;
    }

    @AfterEach
    public void tearDown() {
        Properties.WRITE_INDIVIDUALS = writeIndividuals;
        Properties.REPORT_DIR = oldReportDir;
        Properties.ALGORITHM = algorithm;
    }

    @Test
    public void theFileIsClosedWhenAFitnessValueCannotBeRead() throws IOException {
        SPEA2<SPEA2Test.TestChromosome> spea2 = new SPEA2<>(new SPEA2Test.TestChromosomeFactory());
        spea2.addFitnessFunction(new FailingFitnessFunction());

        assertThrows(IllegalStateException.class,
                () -> spea2.writeIndividuals(Collections.singletonList(new SPEA2Test.TestChromosome("A"))));

        // the header was written before the failure; it reaches the file only if the writer was closed
        String written = new String(Files.readAllBytes(reportDir.resolve("pareto_0.csv")), StandardCharsets.UTF_8);
        assertEquals("strength,FailingFitnessFunction" + System.lineSeparator(), written);
    }
}
