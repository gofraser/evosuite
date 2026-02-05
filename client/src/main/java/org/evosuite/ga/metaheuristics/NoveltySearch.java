/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * <p>
 * This file is part of EvoSuite.
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.NoveltyFunction;
import org.evosuite.ga.archive.Archive;
import org.evosuite.novelty.BranchNoveltyFunction;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class NoveltySearch extends GeneticAlgorithm<TestChromosome> {

    private final static Logger logger = LoggerFactory.getLogger(NoveltySearch.class);
    private static final long serialVersionUID = -1047550745990198972L;

    private NoveltyFunction<TestChromosome> noveltyFunction;
    private final NoveltyFitnessFunction noveltyFitnessFunction = new NoveltyFitnessFunction();

    public NoveltySearch(ChromosomeFactory<TestChromosome> factory) {
        super(factory);

        noveltyFunction = new BranchNoveltyFunction();
        addFitnessFunction(noveltyFitnessFunction);
    }

    public void setNoveltyFunction(NoveltyFunction<TestChromosome> function) {
        this.noveltyFunction = function;
    }

    /**
     * Calculate fitness for all individuals
     */
    protected void calculateNoveltyAndSortPopulation() {
        logger.debug("Calculating novelty for " + population.size() + " individuals");

        List<TestChromosome> union = new ArrayList<>(population);
        union.addAll(Archive.getArchiveInstance().getSolutions());

        for (TestChromosome c : population) {
            double novelty = noveltyFunction.getNovelty(c, union);
            c.setFitness(noveltyFitnessFunction, novelty);
        }

        // Sort population
        this.sortPopulation();
    }

    @Override
    public void initializePopulation() {
        notifySearchStarted();
        currentIteration = 0;

        // Set up initial population
        generateInitialPopulation(Properties.POPULATION);

        // Determine novelty
        calculateNoveltyAndSortPopulation();
        this.notifyIteration();
    }

    @Override
    protected void evolve() {

        List<TestChromosome> newGeneration = new ArrayList<>();

        while (!isNextPopulationFull(newGeneration)) {
            TestChromosome parent1 = selectionFunction.select(population);
            TestChromosome parent2 = selectionFunction.select(population);

            TestChromosome offspring1 = parent1.clone();
            TestChromosome offspring2 = parent2.clone();

            try {
                if (Randomness.nextDouble() <= Properties.CROSSOVER_RATE) {
                    crossoverFunction.crossOver(offspring1, offspring2);
                }

                notifyMutation(offspring1);
                offspring1.mutate();
                notifyMutation(offspring2);
                offspring2.mutate();

                if (offspring1.isChanged()) {
                    offspring1.updateAge(currentIteration);
                }
                if (offspring2.isChanged()) {
                    offspring2.updateAge(currentIteration);
                }
            } catch (ConstructionFailedException e) {
                logger.info("CrossOver/Mutation failed.");
                continue;
            }

            if (!isTooLong(offspring1))
                newGeneration.add(offspring1);
            else
                newGeneration.add(parent1);

            if (isNextPopulationFull(newGeneration)) {
                break;
            }

            if (!isTooLong(offspring2))
                newGeneration.add(offspring2);
            else
                newGeneration.add(parent2);
        }

        population = newGeneration;
        //archive
        updateFitnessFunctionsAndValues();
        //
        currentIteration++;
    }

    @Override
    public void generateSolution() {

        if (population.isEmpty())
            initializePopulation();

        logger.info("Starting evolution of novelty search algorithm");

        while (!isFinished()) {
            logger.info("Current population: " + getAge() + "/" + Properties.SEARCH_BUDGET);

            evolve();

            calculateNoveltyAndSortPopulation();

            this.notifyIteration();
        }

        updateBestIndividualFromArchive();
        notifySearchFinished();

    }

    private static class NoveltyFitnessFunction extends FitnessFunction<TestChromosome> {
        private static final long serialVersionUID = -2919343715691060010L;

        @Override
        public double getFitness(TestChromosome individual) {
            return individual.getFitness(this);
        }

        @Override
        public boolean isMaximizationFunction() {
            return true;
        }
    }
}
