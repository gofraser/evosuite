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
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SPEA2Test {

    static class TestChromosome extends Chromosome<TestChromosome> {
        private static final long serialVersionUID = 1L;
        private final String name;

        public TestChromosome(String name) {
            this.name = name;
        }

        @Override
        public TestChromosome clone() { return new TestChromosome(name); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestChromosome that = (TestChromosome) o;
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }

        @Override
        public int compareTo(TestChromosome o) { return 0; }
        @Override
        public int compareSecondaryObjective(TestChromosome o) { return 0; }
        @Override
        public void mutate() {}
        @Override
        public void crossOver(TestChromosome other, int position1, int position2) throws ConstructionFailedException {}
        @Override
        public boolean localSearch(LocalSearchObjective<TestChromosome> objective) { return false; }
        @Override
        public int size() { return 1; }
        @Override
        public String toString() { return name; }
        @Override
        public TestChromosome self() { return this; }

        public void setFitnessValue(FitnessFunction<TestChromosome> ff, double value) {
            super.setFitness(ff, value);
        }
    }

    static class TestChromosomeFactory implements ChromosomeFactory<TestChromosome> {
        private static final long serialVersionUID = 1L;
        @Override
        public TestChromosome getChromosome() { return new TestChromosome("Default"); }
    }

    static class MockFitnessFunction extends FitnessFunction<TestChromosome> {
        private static final long serialVersionUID = 1L;
        @Override
        public double getFitness(TestChromosome individual) { return 0; }
        @Override
        public boolean isMaximizationFunction() { return false; }
    }

    static class SPEA2Exposed extends SPEA2<TestChromosome> {
        private static final long serialVersionUID = 1L;
        public SPEA2Exposed(ChromosomeFactory<TestChromosome> factory) {
            super(factory);
        }

        @Override
        public List<TestChromosome> environmentalSelection(List<TestChromosome> union) {
            return super.environmentalSelection(union);
        }

        @Override
        public void computeStrength(List<TestChromosome> solution) {
            super.computeStrength(solution);
        }
    }

    @Test
    public void testEnvironmentalSelectionBug() {
        Properties.POPULATION = 1;

        SPEA2Exposed spea2 = new SPEA2Exposed(new TestChromosomeFactory());

        TestChromosome a = new TestChromosome("A");
        TestChromosome b = new TestChromosome("B");
        TestChromosome c = new TestChromosome("C");
        TestChromosome d = new TestChromosome("D");

        MockFitnessFunction f1 = new MockFitnessFunction();
        MockFitnessFunction f2 = new MockFitnessFunction();

        a.setFitnessValue(f1, 0.0); a.setFitnessValue(f2, 0.0);
        b.setFitnessValue(f1, 1.0); b.setFitnessValue(f2, -1.0);
        c.setFitnessValue(f1, 11.0); c.setFitnessValue(f2, -11.0);
        d.setFitnessValue(f1, 12.0); d.setFitnessValue(f2, -12.0);

        List<TestChromosome> union = new ArrayList<>();
        union.add(a);
        union.add(b);
        union.add(c);
        union.add(d);

        spea2.computeStrength(union);

        for(TestChromosome t : union) {
            assertTrue("Should be non-dominated", t.getDistance() < 1.0);
        }

        List<TestChromosome> result = spea2.environmentalSelection(union);

        assertEquals(1, result.size());

        // With BUG, logic suggests it returns A.
        // Without BUG, logic suggests it returns D.
        System.out.println("Result: " + result.get(0).toString());
        assertEquals("Should return D if bug is fixed", "D", result.get(0).toString());
    }
}
