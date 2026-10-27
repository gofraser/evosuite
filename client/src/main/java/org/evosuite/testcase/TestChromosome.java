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
package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationExecutionResult;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.ga.SecondaryObjective;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.evosuite.llm.search.BlendChannel;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.localsearch.TestCaseLocalSearch;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.AbstractTestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

/**
 * Chromosome representation of test cases.
 *
 * @author Gordon Fraser
 */
public final class TestChromosome extends ExecutableChromosome<TestChromosome> {

    private static final long serialVersionUID = 7532366007973252782L;

    private static final Logger logger = LoggerFactory.getLogger(TestChromosome.class);

    /**
     * The test case encoded in this chromosome.
     */
    protected TestCase test = new DefaultTestCase();

    /**
     * Secondary objectives used during ranking.
     */
    private static final List<SecondaryObjective<TestChromosome>> secondaryObjectives =
            new ArrayList<>();

    /**
     * Marks the external source that introduced this chromosome into the MOSA union (set in {@code
     * collectExternalCandidates}). Cleared on {@code clone()} so offspring of an injected parent
     * are not themselves flagged as injected: the marker reflects fresh arrivals only, used by the
     * population species recorder to annotate disruption events.
     *
     * <p>Transient because the marker is only meaningful within a single generation on the client
     * side; it has no role after RMI transfer.
     */
    private transient InjectionSource injectionSource;

    /**
     * Monotonic lineage identifier assigned when this chromosome is injected from an external
     * source. Negative when unset.
     */
    private transient long injectionLineageId = -1L;

    /**
     * Generation at which this chromosome was injected. Negative when unset.
     */
    private transient int injectionGeneration = -1;

    /**
     * Whether this chromosome is eligible for incubator-species protection. Eligibility is set at
     * injection time and evaluated in survival selection.
     */
    private transient boolean incubatorEligible = false;

    /**
     * Diagnostic card types associated with the prompt that produced this chromosome. Cleared on
     * clone so descendants are not treated as fresh prompt outputs.
     */
    private transient List<ProblemCardType> diagnosticCardTypes = Collections.emptyList();

    /**
     * Channel through which this chromosome entered the MOSA union when it is a blend-related
     * entrant (raw injection, mutation-burst variant, or crossover blend). Null for ordinary GA
     * offspring. Cleared on clone like the other injection fields; telemetry-only, never read by
     * selection.
     */
    private transient BlendChannel blendChannel;

    /**
     * Injected lineages this chromosome genetically descends from, propagated explicitly at every
     * offspring birth (breeding, random insertion, brood blending). Must NOT be folded into {@link
     * #injectionLineageId}: that field's cleared-on-clone semantics mark fresh arrivals only and
     * are load-bearing for incubator protection and the species recorder, whereas descent marks
     * must follow genetic material through ordinary breeding so lineage take-over is measurable.
     * Capped at {@link #DESCENT_LINEAGE_CAP} entries, keeping the lowest (oldest) lineage ids.
     */
    private transient Set<Long> descentLineageIds = Collections.emptySet();

    /** Maximum number of descent lineage ids retained per chromosome. */
    public static final int DESCENT_LINEAGE_CAP = 4;

    /**
     * Drops the per-invocation method-call records from the cached execution result,
     * keeping the aggregated coverage data. See {@link ExecutionTrace#withoutMethodCalls()}.
     */
    public void discardMethodCallTraces() {
        if (lastExecutionResult != null && lastExecutionResult.getTrace() != null) {
            lastExecutionResult.setTrace(lastExecutionResult.getTrace().withoutMethodCalls());
        }
    }

    /**
     * Wraps the test of an execution result in an unchanged chromosome that carries the result.
     *
     * @param result the execution result of the test
     * @return a new chromosome for {@code result.test}
     */
    public static TestChromosome fromResult(ExecutionResult result) {
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(result.test);
        chromosome.setLastExecutionResult(result);
        chromosome.setChanged(false);
        return chromosome;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLastExecutionResult(ExecutionResult lastExecutionResult) {
        if (lastExecutionResult == null) {
            return;
        }
        assert lastExecutionResult.test.equals(this.test);
        this.lastExecutionResult = lastExecutionResult;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setChanged(boolean changed) {
        super.setChanged(changed);
        if (changed) {
            clearCachedResults();
        }
    }

    @Override
    public TestChromosome self() {
        return this;
    }

    /**
     * Returns where this chromosome was injected from.
     *
     * @return the injection source, or null if this chromosome originated from the GA itself
     */
    public InjectionSource getInjectionSource() {
        return injectionSource;
    }

    /** Tags this chromosome with the external source that introduced it. */
    public void setInjectionSource(InjectionSource source) {
        this.injectionSource = source;
    }

    public long getInjectionLineageId() {
        return injectionLineageId;
    }

    public void setInjectionLineageId(long injectionLineageId) {
        this.injectionLineageId = injectionLineageId;
    }

    public int getInjectionGeneration() {
        return injectionGeneration;
    }

    public void setInjectionGeneration(int injectionGeneration) {
        this.injectionGeneration = injectionGeneration;
    }

    public boolean isIncubatorEligible() {
        return incubatorEligible;
    }

    public void setIncubatorEligible(boolean incubatorEligible) {
        this.incubatorEligible = incubatorEligible;
    }

    /**
     * Returns the problem card types attached to this chromosome.
     *
     * @return the diagnostic card types, possibly empty
     */
    public List<ProblemCardType> getDiagnosticCardTypes() {
        return diagnosticCardTypes;
    }

    /**
     * Sets the problem card types attached to this chromosome.
     *
     * @param types the card types; duplicates are dropped, and null or empty clears them
     */
    public void setDiagnosticCardTypes(Collection<ProblemCardType> types) {
        if (types == null || types.isEmpty()) {
            this.diagnosticCardTypes = Collections.emptyList();
            return;
        }
        LinkedHashSet<ProblemCardType> uniqueTypes = new LinkedHashSet<>();
        for (ProblemCardType type : types) {
            if (type != null) {
                uniqueTypes.add(type);
            }
        }
        this.diagnosticCardTypes = uniqueTypes.isEmpty()
                ? Collections.<ProblemCardType>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(uniqueTypes));
    }

    /**
     * Returns the blend channel this chromosome was produced on.
     *
     * @return the blend channel, or null for ordinary GA offspring
     */
    public BlendChannel getBlendChannel() {
        return blendChannel;
    }

    /**
     * Sets the blend channel this chromosome was produced on.
     *
     * @param blendChannel the blend channel, or null for ordinary GA offspring
     */
    public void setBlendChannel(BlendChannel blendChannel) {
        this.blendChannel = blendChannel;
    }

    /**
     * Returns the injected lineages this chromosome descends from.
     *
     * @return the descent lineage ids (never null)
     */
    public Set<Long> getDescentLineageIds() {
        return descentLineageIds;
    }

    /**
     * Merges the given lineage ids into this chromosome's descent marks, enforcing {@link
     * #DESCENT_LINEAGE_CAP} by keeping the lowest (oldest) ids so long-lived lineages remain
     * traceable.
     *
     * @param lineageIds the lineage ids to add; null and negative ids are ignored
     */
    public void addDescentLineages(Collection<Long> lineageIds) {
        if (lineageIds == null || lineageIds.isEmpty()) {
            return;
        }
        TreeSet<Long> merged = new TreeSet<>(descentLineageIds);
        for (Long id : lineageIds) {
            if (id != null && id >= 0L) {
                merged.add(id);
            }
        }
        while (merged.size() > DESCENT_LINEAGE_CAP) {
            merged.pollLast();
        }
        this.descentLineageIds = merged.isEmpty()
                ? Collections.<Long>emptySet()
                : Collections.unmodifiableSet(merged);
    }

    /**
     * Returns the descent marks plus this chromosome's own injection lineage when set.
     *
     * @return the full set of lineages whose material this chromosome carries
     */
    public Set<Long> effectiveLineages() {
        if (injectionLineageId < 0L) {
            return descentLineageIds;
        }
        if (descentLineageIds.isEmpty()) {
            return Collections.singleton(injectionLineageId);
        }
        TreeSet<Long> all = new TreeSet<>(descentLineageIds);
        all.add(injectionLineageId);
        return Collections.unmodifiableSet(all);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Create a deep copy of the chromosome.
     */
    @Override
    public TestChromosome clone() {
        TestChromosome c = new TestChromosome();
        c.test = test.clone();
        c.setFitnessValues(getFitnessValues());
        c.setPreviousFitnessValues(getPreviousFitnessValues());
        c.copyCachedResults(this);
        c.setChanged(isChanged());
        c.setLocalSearchApplied(hasLocalSearchBeenApplied());
        c.setNumberOfMutations(this.getNumberOfMutations());
        c.setNumberOfEvaluations(this.getNumberOfEvaluations());
        c.setKineticEnergy(getKineticEnergy());
        c.setNumCollisions(getNumCollisions());

        return c;
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.ExecutableChromosome#copyCachedResults(org.evosuite.testcase.ExecutableChromosome)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void copyCachedResults(TestChromosome other) {
        if (test == null) {
            throw new RuntimeException("Test is null!");
        }

        if (other.lastExecutionResult != null) {
            this.lastExecutionResult = other.lastExecutionResult.clone();
            this.lastExecutionResult.setTest(this.test);
        }

        if (other.lastMutationResult != null) {
            for (Mutation mutation : other.lastMutationResult.keySet()) {
                MutationExecutionResult copy = other.lastMutationResult.get(mutation); //.clone();
                //copy.test = test;
                this.lastMutationResult.put(mutation, copy);
            }
        }
    }

    /**
     * Sets the test case encoded in this chromosome.
     *
     * @param testCase a {@link org.evosuite.testcase.TestCase} object.
     */
    public void setTestCase(TestCase testCase) {
        test = Objects.requireNonNull(testCase, "TestChromosome.test must not be null");
        clearCachedResults();
        clearCachedMutationResults();
        setChanged(true);
    }

    /**
     * Returns the test case encoded in this chromosome.
     *
     * @return a {@link org.evosuite.testcase.TestCase} object.
     */
    public TestCase getTestCase() {
        return test;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Single point cross over.
     */
    @Override
    public void crossOver(TestChromosome other, int position1, int position2)
            throws ConstructionFailedException {
        logger.debug("Crossover starting");
        TestFactory testFactory = TestFactory.getInstance();

        // Clone the full test case first, then chop to the crossover point.
        // This avoids out-of-bounds errors when statements contain forward
        // variable references (e.g., AssignmentStatement targeting a later variable).
        TestCase offspringTest = test.clone();
        offspringTest.chop(position1);

        for (int i = position2; i < other.size(); i++) {
            try {
                testFactory.appendStatement(offspringTest,
                        other.test.getStatement(i));
            } catch (RuntimeException e) {
                Statement st = other.test.getStatement(i);
                AtMostOnceLogger.warn(logger,
                        "Skipping statement during crossover due to runtime failure: "
                                + st.getClass().getSimpleName() + " at position " + i
                                + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
        }
        if (!Properties.CHECK_MAX_LENGTH
                || offspringTest.size() <= Properties.CHROMOSOME_LENGTH) {
            test = offspringTest;
            setChanged(true);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two chromosomes are equal if their tests are equal.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        TestChromosome other = (TestChromosome) obj;
        if (test == null) {
            return other.test == null;
        } else  {
            return test.equals(other.test);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return test.hashCode();
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.Chromosome#localSearch()
     */

    /**
     * {@inheritDoc}
     *
     * @param objective the objective.
     * @return true if the local search was successful.
     */
    @Override
    public boolean localSearch(LocalSearchObjective<TestChromosome> objective) {
        TestCaseLocalSearch<TestChromosome> localSearch =
                TestCaseLocalSearch.selectTestCaseLocalSearch();
        return localSearch.doSearch(this, objective);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each statement is mutated with probability 1/l.
     */
    @Override
    public void mutate() {
        boolean changed = false;

        if (mockChange()) {
            changed = true;
        }

        if (Properties.CHOP_MAX_LENGTH && size() >= Properties.CHROMOSOME_LENGTH) {
            int lastPosition = getLastMutatableStatement();
            test.chop(lastPosition + 1);
        }

        // Delete
        if (Randomness.nextDouble() <= Properties.P_TEST_DELETE) {
            logger.debug("Mutation: delete");
            if (mutationDelete()) {
                changed = true;
            }
        }

        // Change
        if (Randomness.nextDouble() <= Properties.P_TEST_CHANGE) {
            logger.debug("Mutation: change");
            if (mutationChange()) {
                changed = true;
            }
        }

        // Insert
        if (Randomness.nextDouble() <= Properties.P_TEST_INSERT) {
            logger.debug("Mutation: insert");
            if (mutationInsert()) {
                changed = true;
            }
        }

        if (changed) {
            this.increaseNumberOfMutations();
            setChanged(true);
            test.clearCoveredGoals();
        }

        test.forEach(Statement::isValid);
    }

    /**
     * Updates the stubs of the functional mocks from the last execution. A stub added in one
     * pass can make the code under test reach calls that no earlier execution observed (for
     * instance a mock returned by a new stub is itself called), so the test is re-executed and
     * updated again, up to {@link Properties#FUNCTIONAL_MOCK_COMPLETION_ROUNDS} passes in
     * total, while the previous pass added stubs and each further pass is drawn with
     * {@link Properties#FUNCTIONAL_MOCK_COMPLETION_CONTINUE_PROBABILITY}.
     */
    boolean mockChange() {
        return mockChange(this::mockChangePass, this::anyMockNeedsUpdate);
    }

    /**
     * Whether a pass of {@link #mockChangePass(boolean[])} has anything to do, which it has
     * only if some functional mock observed calls its stubs do not yet cover. This only reads
     * the state of the mocks, so it is cheap and does not change the test.
     */
    private boolean anyMockNeedsUpdate() {
        for (Statement statement : test) {
            if (statement instanceof FunctionalMockStatement
                    && ((FunctionalMockStatement) statement).doesNeedToUpdateInputs()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs the completion passes of {@link #mockChange()} with the given pass, which updates the
     * stubs, reports through its argument whether it added any, and returns whether it changed
     * anything.
     */
    boolean mockChange(Predicate<boolean[]> pass) {
        return mockChange(pass, () -> true);
    }

    /**
     * Runs the completion passes with the given pass. From the second pass on, a pass is only
     * run, and the test only copied so that the pass can be undone, if {@code mayChange} reports
     * that the pass may change the test; the last pass of a chain, which finds nothing to
     * complete, therefore costs no copy.
     */
    boolean mockChange(Predicate<boolean[]> pass, BooleanSupplier mayChange) {
        boolean changed = false;
        final int rounds = Math.max(1, Properties.FUNCTIONAL_MOCK_COMPLETION_ROUNDS);
        for (int round = 1; round <= rounds; round++) {
            // Everything a later pass adds can be undone: a pass that fills a stub with values
            // the test cannot construct must not replace the test of the previous pass, which
            // may already run to completion.
            if (round > 1 && !mayChange.getAsBoolean()) {
                break;
            }
            final TestCase backup = round > 1 ? test.clone() : null;
            final Set<Statement> existing = round > 1 ? identitySetOf(test) : null;
            final ExecutionResult resultBefore = lastExecutionResult;
            boolean[] addedStubs = new boolean[1];
            if (pass.test(addedStubs)) {
                changed = true;
            }
            if (!addedStubs[0] || rounds == 1) {
                break;
            }
            // Each further pass happens only with some probability, so that some mutations keep
            // the shorter chain: that test is evaluated as it is, and may already reach code a
            // longer chain no longer reaches when its stub values fail to build.
            boolean another = round < rounds
                    && Randomness.nextDouble() < Properties.FUNCTIONAL_MOCK_COMPLETION_CONTINUE_PROBABILITY;
            if (round == 1 && !another) {
                break;
            }
            try {
                ExecutionResult result = TestCaseExecutor.runTest(test);
                if (round > 1 && throwsInNewStatement(result, existing)) {
                    // The values this pass added to the stubs fail to build, which aborts the
                    // test before the code under test runs. Keep the test of the previous
                    // pass: it works with the stubs completed so far, and the search completes
                    // the remaining ones in a later mutation, as it does with a single round.
                    test = backup;
                    if (resultBefore != null) {
                        // The result describes the test before the pass, which the pass has
                        // changed in place; that test is now the restored copy.
                        resultBefore.setTest(backup);
                    }
                    this.lastExecutionResult = resultBefore;
                    break;
                }
                this.lastExecutionResult = result;
            } catch (Throwable t) {
                logger.debug("Re-execution after mock completion failed: {}", t.toString());
                break;
            }
            if (!another) {
                break;
            }
        }
        return changed;
    }

    private static Set<Statement> identitySetOf(TestCase testCase) {
        Set<Statement> statements = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Statement statement : testCase) {
            statements.add(statement);
        }
        return statements;
    }

    /**
     * Whether the execution was aborted by a statement that did not exist before, that is by a
     * statement a mock completion pass has just inserted to build a stub value.
     */
    private boolean throwsInNewStatement(ExecutionResult result, Set<Statement> existing) {
        Integer position = result.getFirstPositionOfThrownException();
        return position != null && position >= 0 && position < test.size()
                && !existing.contains(test.getStatement(position));
    }

    boolean mockChangePass(boolean[] addedStubs) {

        /*
            Be sure to update the mocked values if there has been any change in
            behavior in the last execution.

            Note: mock "expansion" cannot be done after a test has been mutated and executed,
            as the expansion itself might have side effects. Therefore, it has to be done
            before a test is evaluated.
         */

        boolean changed = false;

        for (int i = 0; i < test.size(); i++) {
            Statement st = test.getStatement(i);
            if (!(st instanceof FunctionalMockStatement)) {
                continue;
            }

            FunctionalMockStatement fms = (FunctionalMockStatement) st;
            if (!fms.doesNeedToUpdateInputs()) {
                continue;
            }

            int preLength = test.size();

            try {
                List<VariableReference> added = fms.completeStubs(missing -> {
                    int pos = st.getPosition();
                    logger.debug("Generating parameters for mock call");
                    try {
                        // Use depth=1 because we are resolving dependencies of an existing
                        // mock, not generating a top-level object.
                        return TestFactory.getInstance().satisfyParameters(test, null, missing,
                                null, pos, 1, true, false, true);
                    } catch (Exception e) {
                        // Concrete generators may fail (e.g. recursion depth exceeded).
                        // Fall back to directly creating functional mocks for each type.
                        logger.debug("satisfyParameters failed for mock of {}, creating mock fallbacks",
                                fms.getReturnType());
                        return createMockFallbacks(test, missing, st.getPosition());
                    }
                });
                if (!added.isEmpty()) {
                    addedStubs[0] = true;
                }
            } catch (Exception e) {
                // the calls without a value are answered with null
                String msg = "Functional mock problem for " + fms.getReturnType()
                        + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
                AtMostOnceLogger.warn(logger, msg);
                // Only this mock is beyond saving. Returning abandoned every functional mock
                // after it in the same test, leaving those with their inputs unset for a
                // failure that had nothing to do with them.
                i += test.size() - preLength;
                continue;
            }
            changed = true;

            int increase = test.size() - preLength;
            i += increase;
        }

        return changed;
    }

    /**
     * Creates mock fallbacks for missing return types when satisfyParameters fails. For each
     * missing type, tries to create a functional mock. If the type cannot be mocked, creates a null
     * reference instead.
     */
    private List<VariableReference> createMockFallbacks(
            TestCase test, List<Type> missing, int position) throws ConstructionFailedException {
        List<VariableReference> refs = new ArrayList<>();
        TestFactory tf = TestFactory.getInstance();
        GenerationContext context = GenerationContext.fromDepth(1);
        for (Type type : missing) {
            VariableReference ref;
            if (FunctionalMockStatement.canBeFunctionalMocked(type)) {
                ref = tf.addFunctionalMock(test, type, position, context);
                position = ref.getStPosition() + 1;
            } else {
                ref = tf.createNull(test, type, position, context);
                position++;
            }
            refs.add(ref);
        }
        return refs;
    }

    /**
     * In the test case encoded by this chromosome, returns the position of the last statement that
     * can be mutated. If an exception occurred during the last execution of the test case, the
     * method returns the position of the last valid statement, i.e., the position of the statement
     * that directly precedes the exception-causing statement.
     *
     * @return the position of the last valid statement that can be mutated
     */
    private int getLastMutatableStatement() {
        final ExecutionResult result = getLastExecutionResult();
        final int size = test.size();

        if (result != null && !result.noThrownExceptions()) {
            // If an exception was thrown during execution, the test case is only valid up to the
            // point right before where the exception occurred.
            final int pos = result.getFirstPositionOfThrownException();

            // It may happen that pos > size() after statements have been deleted.
            return pos >= size ? size - 1 : pos;
        } else {
            return test.size() - 1;
        }
    }

    /**
     * Returns the insertion anchor (last known valid statement) for insertion mutations.
     *
     * <p>If the last execution threw at statement {@code pos}, insertion must happen before that
     * statement, so this method returns {@code pos - 1}. This still allows change/delete mutations
     * to target the throwing statement through {@link #getLastMutatableStatement()}.
     */
    private int getLastInsertionAnchor() {
        final ExecutionResult result = getLastExecutionResult();
        final int size = test.size();

        if (result != null && !result.noThrownExceptions()) {
            final int pos = result.getFirstPositionOfThrownException();
            if (pos >= size) {
                return size - 1;
            }
            return pos - 1;
        }
        return test.size() - 1;
    }

    /**
     * Each statement is deleted with probability 1/length.
     *
     * @return true if the chromosome was changed.
     */
    private boolean mutationDelete() {

        if (test.isEmpty()) {
            return false; //nothing to delete
        }

        boolean changed = false;
        int lastMutableStatement = getLastMutatableStatement();
        double pl = 1d / (lastMutableStatement + 1);
        TestFactory testFactory = TestFactory.getInstance();

        for (int num = lastMutableStatement; num >= 0; num--) {

            if (num >= test.size()) {
                continue; //in case the delete remove more than one statement
            }

            // Each statement is deleted with probability 1/l
            if (Randomness.nextDouble() <= pl) {
                changed |= deleteStatement(testFactory, num);
            }
        }

        return changed;
    }

    protected boolean deleteStatement(TestFactory testFactory, int num) {

        try {

            TestCase copy = test.clone();

            boolean modified = testFactory.deleteStatementGracefully(copy, num);

            test = copy;
            return modified;

        } catch (ConstructionFailedException e) {
            logger.warn("Deletion of statement failed: {}", test.getStatement(num).getCode());
            logger.warn(test.toCode());
            return false; //modifications were on copy
        } catch (AssertionError e) {
            // Defense-in-depth: a chromosome with orphaned VariableReferences
            // (e.g., from an LLM-parsed test that slipped past upstream gates)
            // throws here from VariableReferenceImpl.getStPosition during
            // clone(). Surfacing the AssertionError would kill the entire
            // search; swallowing it lets the search continue minus this
            // mutation. The chromosome remains in the population — upstream
            // validation in TestParser/TestRepairLoop/AbstractMOSA should
            // prevent this from ever firing, so any hit here is a leak worth
            // chasing in the log.
            logger.warn("deleteStatement aborted (chromosome has unresolved variable references): {}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * Each statement is replaced with probability 1/length.
     *
     * @return true if the chromosome was changed.
     */
    private boolean mutationChange() {
        boolean changed = false;
        int lastMutatableStatement = getLastMutatableStatement();
        double pl = 1d / (lastMutatableStatement + 1);
        TestFactory testFactory = TestFactory.getInstance();

        // EvoObj: with the slot bias on, half the time tune one of the primitive template
        // slots in place instead of mutating uniformly. Falls through to the uniform pass if there
        // are no slots or the slot mutation does not change anything.
        if (Randomness.nextDouble() < 0.5) {
            changed = mutateObjectRuleSlot(testFactory, lastMutatableStatement);
        }

        if (!changed) {
            for (int position = 0; position <= lastMutatableStatement; position++) {
                if (Randomness.nextDouble() <= pl) {
                    assert (test.isValid());

                    Statement statement = test.getStatement(position);

                    if (statement.isReflectionStatement()) {
                        continue;
                    }

                    int oldDistance = statement.getReturnValue().getDistance();

                    //constraints are handled directly in the statement mutations
                    if (statement.mutate(test, testFactory)) {
                        changed = true;
                        assert (test.isValid());

                    } else if (!statement.isAssignmentStatement()) {

                        int pos = statement.getPosition();
                        if (testFactory.changeRandomCall(test, statement)) {
                            changed = true;
                        }
                        assert (test.isValid());
                    }

                    statement.getReturnValue().setDistance(oldDistance);
                    position = statement.getPosition(); // Might have changed due to mutation
                }
            }
        }

        return changed;
    }

    /**
     * EvoObj: mutate a randomly chosen primitive template slot in place. Returns whether a slot was
     * found and successfully mutated. In-place primitive mutation preserves the slot flag; a
     * mutation that replaces the statement with a different type naturally loses it.
     */
    private boolean mutateObjectRuleSlot(TestFactory testFactory, int lastMutatableStatement) {
        List<Integer> slotPositions = new ArrayList<>();
        for (int position = 0; position <= lastMutatableStatement; position++) {
            Statement statement = test.getStatement(position);
            if (statement instanceof PrimitiveStatement && ((PrimitiveStatement<?>) statement).isObjectRuleSlot()) {
                slotPositions.add(position);
            }
        }
        if (slotPositions.isEmpty()) {
            return false;
        }

        Statement statement = test.getStatement(Randomness.choice(slotPositions));
        int oldDistance = statement.getReturnValue().getDistance();
        boolean changed = statement.mutate(test, testFactory);
        statement.getReturnValue().setDistance(oldDistance);
        return changed;
    }

    /**
     * With exponentially decreasing probability, insert statements at random position.
     *
     * @return true if the chromosome was changed.
     */
    public boolean mutationInsert() {
        boolean changed = false;
        final double ALPHA = Properties.P_STATEMENT_INSERTION; //0.5;
        int count = 0;
        TestFactory testFactory = TestFactory.getInstance();

        while (Randomness.nextDouble() <= Math.pow(ALPHA, count)
                && (!Properties.CHECK_MAX_LENGTH || size() < Properties.CHROMOSOME_LENGTH)) {

            count++;
            // Insert at position as during initialization (i.e., using helper sequences)
            int position = testFactory.insertRandomStatement(test, getLastInsertionAnchor());

            if (position >= 0 && position < test.size()) {
                changed = true;
            }
        }
        return changed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The size of a chromosome is the length of its test case.
     */
    @Override
    public int size() {
        return test.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(TestChromosome o) {
        int result = super.compareTo(o);
        if (result != 0) {
            return result;
        }
        // make this deliberately not 0
        // because then ordering of results will be random
        // among tests of equal fitness
        String code = test.toCode();
        String otherCode = o.test.toCode();
        // The mocks of both tests then get the stubs their code shows: the suite minimizer sorts
        // the tests that cover a goal and goes on to execute and minimize the first of them.
        FunctionalMockStatement.completePendingStubsWithNulls(test);
        FunctionalMockStatement.completePendingStubsWithNulls(o.test);
        return code.compareTo(otherCode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return test.toCode();
    }

    /**
     * hasException.
     *
     * @return true if an exception was thrown.
     */
    public boolean hasException() {
        return lastExecutionResult != null && !lastExecutionResult.noThrownExceptions();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExecutionResult executeForFitnessFunction(
            TestSuiteFitnessFunction testSuiteFitnessFunction) {
        return testSuiteFitnessFunction.runTest(this.test);
    }

    @Override
    public int compareSecondaryObjective(TestChromosome o) {
        int objective = 0;
        int c = 0;

        while (c == 0 && objective < secondaryObjectives.size()) {

            SecondaryObjective<TestChromosome> so = secondaryObjectives.get(objective++);
            if (so == null) {
                break;
            }
            c = so.compareChromosomes(this.self(), o);
        }
        return c;
    }

    /**
     * Add an additional secondary objective to the end of the list of objectives.
     *
     * @param objective a {@link org.evosuite.ga.SecondaryObjective} object.
     */
    public static void addSecondaryObjective(SecondaryObjective<TestChromosome> objective) {
        secondaryObjectives.add(objective);
    }

    /**
     * Getter for the field <code>secondaryObjectives</code>.
     *
     * @return a {@link java.util.List} object.
     */
    public static List<SecondaryObjective<TestChromosome>> getSecondaryObjectives() {
        return secondaryObjectives;
    }

    /**
     * Clear the list of secondary objectives.
     *
     * <p>This method should be called to avoid state pollution when running unit tests involving
     * this class.
     */
    public static void clearSecondaryObjectives() {
        secondaryObjectives.clear();
    }

    /**
     * Converts this chromosome to a test suite.
     *
     * @return a {@link org.evosuite.testsuite.TestSuiteChromosome} object.
     */
    public TestSuiteChromosome toSuite() {
        return Stream.of(this).collect(toTestSuiteCollector);
    }

    public static final TestChromosomeCollector toTestSuiteCollector = new TestChromosomeCollector();

    public static class TestChromosomeCollector
            implements Collector<TestChromosome, TestSuiteChromosome, TestSuiteChromosome> {
        private static final Set<Characteristics> characteristics =
                Collections.unmodifiableSet(new HashSet<>(Arrays.asList(Characteristics.CONCURRENT,
                        Characteristics.IDENTITY_FINISH, Characteristics.UNORDERED)));

        @Override
        public Supplier<TestSuiteChromosome> supplier() {
            return TestSuiteChromosome::new;
        }

        @Override
        public BiConsumer<TestSuiteChromosome, TestChromosome> accumulator() {
            return AbstractTestSuiteChromosome::addTest;
        }

        @Override
        public BinaryOperator<TestSuiteChromosome> combiner() {
            return (suite1, suite2) -> {
                suite1.addTestChromosomes(suite2.getTestChromosomes());
                return suite1;
            };
        }

        @Override
        public Function<TestSuiteChromosome, TestSuiteChromosome> finisher() {
            return suite -> suite;
        }

        @Override
        public Set<Characteristics> characteristics() {
            return characteristics;
        }
    }

}
