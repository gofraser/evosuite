package org.evosuite.coverage.branch;

/**
 * Small marker interface for branch-oriented fitness functions, used by the EvoObj
 * mutation-position discriminator to recognize branch goals. Upstream
 * {@link BranchCoverageTestFitness} already provides {@link #getBranchGoal()}.
 *
 * <p>The fork's version also carried call-context/target helpers required by its flag-branch
 * fitness; those are out of scope for the EvoObj port, so the interface is trimmed
 * to the single member actually consumed.
 */
public interface BranchFitness {
    BranchCoverageGoal getBranchGoal();
}
