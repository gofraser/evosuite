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
package org.evosuite.ga.archive;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coverage Archive.
 * 
 * @author José Campos
 */
public class CoverageArchive<F extends TestFitnessFunction, T extends TestCase>
    extends Archive<F, T> {

  private static final long serialVersionUID = -4046845573050661961L;

  private static final Logger logger = LoggerFactory.getLogger(CoverageArchive.class);

  /**
   * Map used to store all targets (keys of the map) and the corresponding covering solutions
   * (values of the map)
   **/
  protected final Map<F, T> archive = new LinkedHashMap<F, T>();

  public static final CoverageArchive<TestFitnessFunction, TestCase> instance =
      new CoverageArchive<TestFitnessFunction, TestCase>();

  /**
   * {@inheritDoc}
   */
  @Override
  public void addTarget(F target) {
    assert target != null;

    if (!this.archive.containsKey(target)) {
      logger.debug("Registering new target '" + target + "'");
      this.archive.put(target, null);
    }

    this.registerNonCoveredTargetOfAMethod(target);
  }

  /**
   * {@inheritDoc}
   */
  @SuppressWarnings("unchecked")
  @Override
  public void updateArchive(F target, ExecutionResult executionResult, double fitnessValue) {
    assert target != null;
    assert this.archive.containsKey(target);

    if (fitnessValue > 0.0) {
      // as this type of archive only cares about covered targets, it ignores all
      // targets with a fitness value greater than 0.0
      return;
    }

    boolean isNewCoveredTarget = false;
    boolean isNewSolutionBetterThanCurrent = false;

    T currentSolution = this.archive.get(target);

    ExecutionResult executionResultClone = executionResult.clone();
    T solutionClone = (T) executionResultClone.test.clone(); // in case executionResult.clone() has
                                                             // not cloned the test
    executionResultClone.setTest(solutionClone);

    if (currentSolution == null) {
      logger.debug("Solution for non-covered target '" + target + "'");
      isNewCoveredTarget = true;
    } else {
      isNewSolutionBetterThanCurrent = this.isBetterThanCurrent(currentSolution, solutionClone);
    }

    if (isNewCoveredTarget || isNewSolutionBetterThanCurrent) {
      // remove all statements after an exception
      if (!executionResultClone.noThrownExceptions()) {
        solutionClone.chop(executionResultClone.getFirstPositionOfThrownException() + 1);
      }

      // update the archive
      this.addToArchive(target, solutionClone);

      // check for collateral coverage only when there is improvement over current target,
      // as it could be a bit expensive
      this.handleCollateralCoverage(executionResultClone, solutionClone);
    }
  }

  private void addToArchive(F target, T solution) {
    this.archive.put(target, solution);
    this.removeNonCoveredTargetOfAMethod(target);
    this.hasBeenUpdated = true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void handleCollateralCoverage(ExecutionResult executionResult, T solution) {
    for (F target : this.archive.keySet()) {
      // does solution cover target?
      if (!target.isCovered(executionResult)) {
        continue;
      }

      T currentSolution = this.archive.get(target);
      if (currentSolution == null) {
        // there is no solution for target yet, therefore include it
        this.addToArchive(target, solution);
      } else {
        if (this.isBetterThanCurrent(currentSolution, solution)) {
          this.addToArchive(target, solution);
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isArchiveEmpty() {
    return this.getNumberOfSolutions() == 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getNumberOfTargets() {
    return this.archive.keySet().size();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getNumberOfCoveredTargets() {
    return this.getCoveredTargets().size();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<F> getCoveredTargets() {
    return this.archive.keySet().stream().filter(target -> this.archive.get(target) != null)
        .collect(Collectors.toSet());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean hasTarget(F target) {
    assert target != null;
    return this.archive.containsKey(target);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getNumberOfSolutions() {
    return this.getSolutions().size();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<T> getSolutions() {
    return this.archive.values().stream().filter(solution -> solution != null)
        .collect(Collectors.toSet());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public T getSolution() {
    return this.getRandomSolution();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public T getSolution(F target) {
    assert target != null;
    assert this.archive.containsKey(target);
    return this.archive.get(target);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean hasSolution(F target) {
    assert target != null;
    assert this.archive.containsKey(target);
    return this.archive.get(target) != null;
  }

  /**
   * {@inheritDoc}
   */
  @SuppressWarnings("unchecked")
  @Override
  public T getRandomSolution() {
    // TODO this gives higher probability to tests that cover more targets. Maybe it is not the best
    // way, but likely the quickest to compute. A proper way to do it would be to first call
    // 'getSolutions' and only then select one at random.
    T randomChoice = Randomness.choice(this.archive.values().stream()
        .filter(solution -> solution != null).collect(Collectors.toSet()));
    if (randomChoice == null) {
      return null;
    }
    return (T) randomChoice.clone();
  }

  /**
   * {@inheritDoc}
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  @Override
  public TestSuiteChromosome mergeArchiveAndSolution(Chromosome solution) {
    // Deactivate in case a test is executed and would access the archive as this might cause a
    // concurrent access
    Properties.TEST_ARCHIVE = false;

    TestSuiteChromosome mergedSolution = (TestSuiteChromosome) solution.clone();

    // to avoid adding the same solution to 'mergedSolution' suite
    Set<T> solutionsSampledFromArchive = new LinkedHashSet<T>();

    for (F target : this.archive.keySet()) {
      // does solution cover target?
      if (!target.isCoveredBy(mergedSolution)) {
        T chromosome = this.archive.get(target);

        // is there any solution in the archive that covers it, and has that solution not been
        // considered yet?
        if (chromosome != null && !solutionsSampledFromArchive.contains(chromosome)) {
          solutionsSampledFromArchive.add(chromosome);

          T chromosomeClone = (T) chromosome.clone();
          mergedSolution.addTest(chromosomeClone);
        }
      }
    }

    // re-evaluate merged solution
    for (FitnessFunction fitnessFunction : solution.getFitnessValues().keySet()) {
      fitnessFunction.getFitness(mergedSolution);
    }

    // re-active it
    Properties.TEST_ARCHIVE = true;

    logger.info("Final test suite size from archive: " + mergedSolution);
    return mergedSolution;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void shrinkSolutions(int size) {
    // NO-OP
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toString() {
    return "NumTargets: " + this.getNumberOfTargets() + ", NumCoveredTargets: "
        + this.getNumberOfCoveredTargets() + ", NumSolutions: " + this.getNumberOfSolutions();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void reset() {
    super.reset();
    this.archive.clear();
  }
}
