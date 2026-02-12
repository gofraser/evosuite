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
package org.evosuite.lm;

/**
 * Represents a chromosome (individual) in the Language Model genetic algorithm.
 * Each chromosome wraps a String value and its associated fitness.
 */
class Chromosome implements Comparable<Chromosome>, Cloneable {
    private String value;
    private double fitness = -1;
    private boolean isEvaluated = false;

    /**
     * Constructs a new Chromosome with the given string value.
     *
     * @param value the string value of this chromosome.
     */
    public Chromosome(String value) {
        this.value = value;
    }

    /**
     * Returns the string value of this chromosome.
     *
     * @return the string value.
     */
    public String getValue() {
        return value;
    }

    /**
     * Sets the string value of this chromosome.
     * If the value changes, the fitness is marked as not evaluated.
     *
     * @param value the new string value.
     */
    public void setValue(String value) {
        if (this.value.equals(value)) {
            return;
        }

        this.value = value;
        this.isEvaluated = false;
    }

    /**
     * Returns the fitness of this chromosome.
     *
     * @return the fitness score.
     */
    public double getFitness() {
        return fitness;
    }

    /**
     * Sets the fitness of this chromosome and marks it as evaluated.
     *
     * @param fitness the fitness score.
     */
    public void setFitness(double fitness) {
        this.fitness = fitness;
        this.isEvaluated = true;
    }

    /**
     * Checks if this chromosome has been evaluated.
     *
     * @return true if evaluated, false otherwise.
     */
    public boolean isEvaluated() {
        return isEvaluated;
    }

    @Override
    public Chromosome clone() {
        Chromosome other = new Chromosome(this.getValue());
        other.setFitness(this.getFitness());
        other.isEvaluated = this.isEvaluated();
        return other;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Chromosome that = (Chromosome) o;

        //Don't care about fitness or evaluation status:

        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        int result;
        result = value.hashCode();
        return result;
    }

    @Override
    public int compareTo(Chromosome o) {
        if (o == null) {
            return 1;
        }

        return Double.compare(this.getFitness(), o.getFitness());

    }
}
