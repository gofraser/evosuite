/**
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
package org.evosuite.coverage.mutation;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.evosuite.Properties;
import org.evosuite.ga.archive.Archive;
import org.evosuite.testcase.ExecutableChromosome;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.AbstractTestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;

/**
 * <p>
 * WeakMutationSuiteFitness class.
 * </p>
 * 
 * @author fraser
 */
public class WeakMutationSuiteFitness extends MutationSuiteFitness {

	private static final long serialVersionUID = -1812256816400338180L;

	/* (non-Javadoc)
	 * @see org.evosuite.ga.FitnessFunction#getFitness(org.evosuite.ga.Chromosome)
	 */
	/** {@inheritDoc} */
	@Override
	public double getFitness(
	        AbstractTestSuiteChromosome<? extends ExecutableChromosome> individual) {
		/**
		 * e.g. classes with only static constructors
		 */
		if (this.numMutants == 0) {
			updateIndividual(this, individual, 0.0);
			((TestSuiteChromosome) individual).setCoverage(this, 1.0);
			((TestSuiteChromosome) individual).setNumOfCoveredGoals(this, 0);
			return 0.0;
		}

		List<ExecutionResult> results = runTestSuite(individual);

		// First objective: achieve branch coverage
		logger.debug("Calculating branch fitness: ");
		/*
		 * Note: results are cached, so the test suite is not executed again when we
		 * calculated the branch fitness
		 */
		double fitness = branchFitness.getFitness(individual);
		Map<Integer, Double> mutant_distance = new LinkedHashMap<Integer, Double>();
		Set<Integer> touchedMutants = new LinkedHashSet<Integer>();

		for (ExecutionResult result : results) {
			// Using private reflection can lead to false positives
			// that represent unrealistic behaviour. Thus, we only
			// use reflection for basic criteria, not for mutation
			if(result.calledReflection())
				continue;

			touchedMutants.addAll(result.getTrace().getTouchedMutants());

			Map<Integer, Double> touchedMutantsDistances = result.getTrace().getMutationDistances();
			if (touchedMutantsDistances.isEmpty()) {
			  // if 'result' does not touch any mutant, no need to continue
			  continue;
			}

			Iterator<Entry<Integer, MutationTestFitness>> it = this.mutantMap.entrySet().iterator();
			while (it.hasNext()) {
				Entry<Integer, MutationTestFitness> entry = it.next();

				int mutantID = entry.getKey();
				TestFitnessFunction goal = entry.getValue();

				double fit = 0.0;
				if (touchedMutantsDistances.containsKey(mutantID)) {
					fit = touchedMutantsDistances.get(mutantID);

					if (!mutant_distance.containsKey(mutantID)) {
						mutant_distance.put(mutantID, fit);
					} else {
						mutant_distance.put(mutantID, Math.min(mutant_distance.get(mutantID), fit));
					}
				} else {
					TestChromosome tc = new TestChromosome();
					tc.setTestCase(result.test);
					fit = goal.getFitness(tc, result);
				}

				if (fit == 0.0) {
					result.test.addCoveredGoal(goal); // update list of covered goals
					this.toRemoveMutants.add(mutantID); // goal to not be considered by the next iteration of the evolutionary algorithm
				}

				if (Properties.TEST_ARCHIVE) {
					Archive.getArchiveInstance().updateArchive(goal, result, fit);
				}
			}
		}

		// Second objective: touch all mutants?
		fitness += MutationPool.getMutantCounter() - touchedMutants.size();
		int covered = removedMutants.size();

		for (Double distance : mutant_distance.values()) {
			if (distance < 0) {
				logger.warn("Distance is " + distance + " / " + Integer.MAX_VALUE + " / "
				        + Integer.MIN_VALUE);
				distance = 0.0; // FIXXME
			}

			fitness += normalize(distance);
			if (distance == 0.0) {
				covered++;
			}
		}
		
		updateIndividual(this, individual, fitness);
		((TestSuiteChromosome) individual).setCoverage(this, (double) covered / (double) this.numMutants);
		((TestSuiteChromosome) individual).setNumOfCoveredGoals(this, covered);

		return fitness;
	}
}
