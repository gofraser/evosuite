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
package org.evosuite.statistics;

import org.evosuite.ClientProcess;
import org.evosuite.Properties;
import org.evosuite.coverage.ambiguity.AmbiguityCoverageSuiteFitness;
import org.evosuite.coverage.branch.BranchCoverageSuiteFitness;
import org.evosuite.coverage.branch.OnlyBranchCoverageSuiteFitness;
import org.evosuite.coverage.cbranch.CBranchSuiteFitness;
import org.evosuite.coverage.exception.ExceptionCoverageSuiteFitness;
import org.evosuite.coverage.io.input.InputCoverageSuiteFitness;
import org.evosuite.coverage.io.output.OutputCoverageSuiteFitness;
import org.evosuite.coverage.line.LineCoverageSuiteFitness;
import org.evosuite.coverage.method.MethodCoverageSuiteFitness;
import org.evosuite.coverage.method.MethodNoExceptionCoverageSuiteFitness;
import org.evosuite.coverage.method.MethodTraceCoverageSuiteFitness;
import org.evosuite.coverage.mutation.OnlyMutationSuiteFitness;
import org.evosuite.coverage.mutation.WeakMutationSuiteFitness;
import org.evosuite.coverage.rho.RhoCoverageSuiteFitness;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.result.TestGenerationResult;
import org.evosuite.rmi.MasterServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.rmi.service.ClientStateInformation;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.evosuite.statistics.backend.CSVStatisticsBackend;
import org.evosuite.statistics.backend.StatisticsBackend;
import org.evosuite.statistics.backend.StatisticsBackendFactory;
import org.evosuite.symbolic.dse.DSEStatistics;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.Listener;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;


/**
 * A singleton of SearchStatistics collects all the data values reported by a single client node.
 *
 * @author gordon
 */
public class SearchStatistics implements Listener<ClientStateInformation> {

    private static final long serialVersionUID = -1859683466333302151L;

    /**
     * Singleton instance.
     */
    private static final Map<String, SearchStatistics> instances = new LinkedHashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(SearchStatistics.class);

    /**
     * Map of client id to best individual received from that client so far.
     */
    private TestSuiteChromosome bestIndividual = null;

    /**
     * Backend used to output the data.
     */
    private StatisticsBackend backend = null;

    /**
     * Output variables and their values.
     */
    private final Map<String, OutputVariable<?>> outputVariables = new TreeMap<>();

    /**
     * Variable factories to extract output variables from chromosomes.
     */
    private final Map<String, ChromosomeOutputVariableFactory<?>> variableFactories = new TreeMap<>();

    /**
     * Variable factories to extract sequence variables.
     */
    private final Map<String, SequenceOutputVariableFactory<?>> sequenceOutputVariableFactories = new TreeMap<>();

    /**
     * Keep track of how far EvoSuite progressed.
     */
    private ClientState currentState = ClientState.INITIALIZATION;

    private long currentStateStarted = System.currentTimeMillis();

    private long searchStartTime = 0L;

    private final long startTime = System.currentTimeMillis();

    private final List<List<TestGenerationResult>> results = new ArrayList<>();

    private SearchStatistics() {
        backend = StatisticsBackendFactory.getStatisticsBackend(Properties.STATISTICS_BACKEND);

        initFactories();

        setOutputVariable(RuntimeVariable.Random_Seed, Randomness.getSeed());
        sequenceOutputVariableFactories.put(RuntimeVariable.CoverageTimeline.name(),
                new CoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.FitnessTimeline.name(),
                new FitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.SizeTimeline.name(),
                new SizeSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.LengthTimeline.name(),
                new LengthSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.TotalExceptionsTimeline.name(),
                DirectSequenceOutputVariableFactory.getInteger(RuntimeVariable.TotalExceptionsTimeline));
        sequenceOutputVariableFactories.put(RuntimeVariable.IBranchGoalsTimeline.name(),
                new IBranchGoalsSequenceOutputVariableFactory());

        sequenceOutputVariableFactories.put(RuntimeVariable.BranchCoverageTimeline.name(),
                new BranchCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.OnlyBranchFitnessTimeline.name(),
                new OnlyBranchFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.OnlyBranchCoverageTimeline.name(),
                new OnlyBranchCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.CBranchFitnessTimeline.name(),
                new CBranchFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.CBranchCoverageTimeline.name(),
                new CBranchCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.MethodTraceFitnessTimeline.name(),
                new MethodTraceFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.MethodTraceCoverageTimeline.name(),
                new MethodTraceCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.MethodFitnessTimeline.name(),
                new MethodFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.MethodCoverageTimeline.name(),
                new MethodCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.MethodNoExceptionFitnessTimeline.name(),
                new MethodNoExceptionFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.MethodNoExceptionCoverageTimeline.name(),
                new MethodNoExceptionCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.RhoScoreTimeline.name(),
                new RhoFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.AmbiguityScoreTimeline.name(),
                new AmbiguityFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.LineFitnessTimeline.name(),
                new LineFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.LineCoverageTimeline.name(),
                new LineCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.OutputFitnessTimeline.name(),
                new OutputFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.OutputCoverageTimeline.name(),
                new OutputCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.InputFitnessTimeline.name(),
                new InputFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.InputCoverageTimeline.name(),
                new InputCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.ExceptionFitnessTimeline.name(),
                new ExceptionFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.ExceptionCoverageTimeline.name(),
                new ExceptionCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.WeakMutationCoverageTimeline.name(),
                new WeakMutationCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.OnlyMutationFitnessTimeline.name(),
                new OnlyMutationFitnessSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.OnlyMutationCoverageTimeline.name(),
                new OnlyMutationCoverageSequenceOutputVariableFactory());
        sequenceOutputVariableFactories.put(RuntimeVariable.DiversityTimeline.name(),
                DirectSequenceOutputVariableFactory.getDouble(RuntimeVariable.DiversityTimeline));

        sequenceOutputVariableFactories.put(RuntimeVariable.DensityTimeline.name(),
                DirectSequenceOutputVariableFactory.getDouble(RuntimeVariable.DensityTimeline));

        sequenceOutputVariableFactories.put(RuntimeVariable.FeaturesFound.name(),
                DirectSequenceOutputVariableFactory.getInteger(RuntimeVariable.FeaturesFound));

        // sequenceOutputVariableFactories.put("Generation_History", new GenerationSequenceOutputVariableFactory());
        if (MasterServices.getInstance().getMasterNode() != null) {
            MasterServices.getInstance().getMasterNode().addListener(this);
        }
    }

    /**
     * Returns the singleton instance for the default client.
     *
     * @return the singleton instance
     */
    public static SearchStatistics getInstance() {
        return getInstance(ClientProcess.DEFAULT_CLIENT_NAME);
    }

    /**
     * Returns the singleton instance for the given client identifier.
     *
     * @param rmiClientIdentifier the client identifier
     * @return the singleton instance
     */
    public static SearchStatistics getInstance(String rmiClientIdentifier) {
        if (rmiClientIdentifier == null || rmiClientIdentifier.isEmpty()) {
            rmiClientIdentifier = ClientProcess.DEFAULT_CLIENT_NAME;
        }
        SearchStatistics instance = instances.get(rmiClientIdentifier);
        if (instance == null) {
            instance = new SearchStatistics();
            instances.put(rmiClientIdentifier, instance);
        }
        return instance;
    }

    /**
     * Clears the singleton instance for the default client.
     */
    public static void clearInstance() {
        clearInstance(ClientProcess.DEFAULT_CLIENT_NAME);
    }

    /**
     * Clears the singleton instance for the given client identifier.
     *
     * @param rmiClientIdentifier the client identifier
     */
    public static void clearInstance(String rmiClientIdentifier) {
        instances.remove(rmiClientIdentifier);
    }

    /**
     * Clears all cached instances.
     * Useful in system tests to avoid cross-test contamination.
     */
    public static void clearAllInstances() {
        instances.clear();
    }

    /**
     * This method is called when a new individual is sent from a client.
     * The individual represents the best individual of the current generation.
     *
     * @param individual best individual of current generation
     */
    public void currentIndividual(Chromosome<?> individual) {
        if (backend == null) {
            return;
        }

        if (!(individual instanceof TestSuiteChromosome)) {
            AtMostOnceLogger.warn(logger, "searchStatistics expected a TestSuiteChromosome");
            return;
        }

        logger.debug("Received individual");
        bestIndividual = (TestSuiteChromosome) individual;
        for (ChromosomeOutputVariableFactory<?> v : variableFactories.values()) {
            setOutputVariable(v.getVariable((TestSuiteChromosome) individual));
        }
        for (SequenceOutputVariableFactory<?> v : sequenceOutputVariableFactories.values()) {
            v.update((TestSuiteChromosome) individual);
        }
    }

    /**
     * Set an output variable to a value directly.
     *
     * @param variable the variable to set
     * @param value the value to set
     */
    public void setOutputVariable(RuntimeVariable variable, Object value) {
        setOutputVariable(new OutputVariable<>(variable.toString(), value));
    }

    /**
     * Sets an output variable.
     *
     * @param variable the variable to set
     */
    public void setOutputVariable(OutputVariable<?> variable) {
        /*
         * if the output variable is contained in sequenceOutputVariableFactories,
         * then it must be a DirectSequenceOutputVariableFactory, hence we set its
         * value so that it can be used to produce the next timeline variable.
         */
        if (sequenceOutputVariableFactories.containsKey(variable.getName())) {
            DirectSequenceOutputVariableFactory<?> v = (DirectSequenceOutputVariableFactory<?>)
                    sequenceOutputVariableFactories.get(variable.getName());
            v.setValue(variable.getValue());
        } else {
            outputVariables.put(variable.getName(), variable);
        }
    }

    /**
     * Adds a test generation result.
     *
     * @param result the result to add
     */
    public void addTestGenerationResult(List<TestGenerationResult> result) {
        results.add(result);
    }

    /**
     * Returns the test generation results.
     *
     * @return the list of results
     */
    public List<List<TestGenerationResult>> getTestGenerationResults() {
        return results;
    }

    /**
     * Returns the output variables.
     *
     * @return the map of output variables
     */
    public Map<String, OutputVariable<?>> getOutputVariables() {
        return this.outputVariables;
    }

    /**
     * Returns true if the essential output variables are present.
     *
     * @return true if essential variables are present
     */
    public boolean hasEssentialOutputVariables() {
        return outputVariables.containsKey(RuntimeVariable.Coverage.toString())
                && outputVariables.containsKey(RuntimeVariable.Total_Goals.toString())
                && outputVariables.containsKey(RuntimeVariable.Covered_Goals.toString());
    }

    /**
     * Retrieve list of possible variables.
     *
     * @return list of variable names
     */
    private List<String> getAllOutputVariableNames() {

        String[] essentials = new String[]{  //TODO maybe add some more
                "TARGET_CLASS", "criterion",
                RuntimeVariable.Coverage.toString(),
                //TODO: why is this fixed?
                //RuntimeVariable.BranchCoverage.toString(),
                RuntimeVariable.Total_Goals.toString(),
                RuntimeVariable.Covered_Goals.toString()
        };

        List<String> variableNames = new ArrayList<>(Arrays.asList(essentials));

        /* Fix for DSE as we want to save the output vars in this case */
        if (Properties.isDSEStrategySelected()) {
            variableNames.addAll(DSEStatistics.dseRuntimeVariables);
        }

        /* cannot use what we received, as due to possible bugs/errors those might not be constant
        variableNames.addAll(outputVariables.keySet());
        variableNames.addAll(variableFactories.keySet());
        variableNames.addAll(sequenceOutputVariableFactories.keySet());
        */
        return variableNames;
    }

    /**
     * Retrieve list of output variables that the user will get to see.
     * If output_variables is not set, then all variables will be returned.
     *
     * @return collection of variable names
     */
    private Collection<String> getOutputVariableNames() {
        List<String> variableNames = new ArrayList<>();
        if (Properties.OUTPUT_VARIABLES == null) {
            variableNames.addAll(getAllOutputVariableNames());
        } else {
            for (String entry : Properties.OUTPUT_VARIABLES.split(",")) {
                variableNames.add(entry.trim());
            }
        }
        return variableNames;
    }

    /**
     * Shorthand for getOutputVariables(individual, false).
     */
    private Map<String, OutputVariable<?>> getOutputVariables(TestSuiteChromosome individual) {
        return getOutputVariables(individual, false);
    }

    /**
     * Extract output variables from input <code>individual</code>.
     * Add also all the other needed search-level variables.
     *
     * @param individual the individual
     * @param skipMissing whether or not to skip missing output variables
     * @return <code>null</code> if some data is missing
     */
    private Map<String, OutputVariable<?>> getOutputVariables(TestSuiteChromosome individual, boolean skipMissing) {
        Map<String, OutputVariable<?>> variables = new LinkedHashMap<>();

        for (String variableName : getOutputVariableNames()) {
            if (outputVariables.containsKey(variableName)) {
                //values directly sent by the client
                variables.put(variableName, outputVariables.get(variableName));
            } else if (Properties.getParameters().contains(variableName)) {
                // values used to define the search, ie the -D given as input to EvoSuite
                variables.put(variableName, new PropertyOutputVariableFactory(variableName).getVariable());
            } else if (variableFactories.containsKey(variableName)) {
                //values extracted from the individual
                variables.put(variableName, variableFactories.get(variableName).getVariable(individual));
            } else if (sequenceOutputVariableFactories.containsKey(variableName)) {
                /*
                 * time related values, which will be expanded in a list of values
                 * through time
                 */
                for (OutputVariable<?> var : sequenceOutputVariableFactories.get(variableName).getOutputVariables()) {
                    variables.put(var.getName(), var);
                }
            } else if (skipMissing || Properties.IS_RUNNING_A_SYSTEM_TEST) {
                // if variable doesn't exist, return an empty value instead
                variables.put(variableName, new OutputVariable<>(variableName, ""));
            } else {
                logger.error("No obtained value for output variable: " + variableName);
                return null;
            }
        }

        return variables;
    }

    /**
     * Write result to disk using selected backend.
     *
     * @return true if the writing was successful
     */
    public boolean writeStatistics() {
        logger.info("Writing statistics");
        if (backend == null) {
            return false;
        }

        outputVariables.put(RuntimeVariable.Total_Time.name(),
                new OutputVariable<Object>(RuntimeVariable.Total_Time.name(), System.currentTimeMillis() - startTime));

        if (bestIndividual == null) {
            // Give a short grace period for delayed statistics to arrive from clients.
            int attempts = 0;
            while (bestIndividual == null && attempts < 5) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // ignored
                }
                attempts++;
            }
        }

        if (bestIndividual == null && !results.isEmpty()) {
            // Fallback: derive best individual from test generation results if statistics arrived late.
            for (List<TestGenerationResult> group : results) {
                for (TestGenerationResult result : group) {
                    try {
                        GeneticAlgorithm<?> ga = result.getGeneticAlgorithm();
                        if (ga != null && ga.getBestIndividual() instanceof TestSuiteChromosome) {
                            bestIndividual = (TestSuiteChromosome) ga.getBestIndividual();
                            break;
                        }
                    } catch (Throwable ignored) {
                        // ignore and continue searching
                    }
                }
                if (bestIndividual != null) {
                    break;
                }
            }
        }

        TestSuiteChromosome individual = bestIndividual != null ? bestIndividual : new TestSuiteChromosome();
        Map<String, OutputVariable<?>> map = bestIndividual == null
                ? getOutputVariables(individual, true)
                : getOutputVariables(individual);
        if (map == null) {

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // ignored
            }

            boolean couldBeFine = MasterServices.getInstance().getMasterNode().getCurrentState().stream()
                    .anyMatch(s -> s.equals(ClientState.DONE) || s.equals(ClientState.FINISHED));


            if (couldBeFine && bestIndividual != null) {
                //maybe data just didn't arrive yet

                int counter = 0;

                while (map == null && counter < 5) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // ignored
                    }

                    //retry
                    map = getOutputVariables(individual);
                    counter++;
                }
            }

            if (map == null && Properties.IGNORE_MISSING_STATISTICS) {
                map = getOutputVariables(individual, true);
            }

            if (map == null) {
                logger.error("Not going to write down statistics data, as some are missing");
                return false;
            }
        }

        Double coverageValue = normalizeGoalsAndCoverage(map, individual);
        normalizeCriterionCoverage(map, coverageValue);

        if (bestIndividual == null) {
            logger.warn("No best individual available; writing partial statistics for failed generation");
            setCoverageUnavailableIfMissing(map);
        }

        boolean valid = StatisticsValidator.validateRuntimeVariables(map);
        if (!valid) {
            logger.error("Not going to write down statistics data, as some data is invalid");
            return false;
        } else {
            backend.writeData(individual, map);
            return true;
        }
    }

    /**
     * Write result to disk using selected backend.
     *
     * @return true if the writing was successful
     */
    public boolean writeStatisticsForAnalysis() {
        logger.info("Writing statistics");
        if (backend == null) {
            LoggingUtils.getEvoLogger().info("Backend is null");
            return false;
        }

        outputVariables.put(RuntimeVariable.Total_Time.name(),
                new OutputVariable<Object>(RuntimeVariable.Total_Time.name(), System.currentTimeMillis() - startTime));

        TestSuiteChromosome individual = new TestSuiteChromosome();
        Map<String, OutputVariable<?>> map = getOutputVariables(individual);
        if (map == null) {
            map = getOutputVariables(individual, true);
        }
        if (map == null) {
            logger.error("Not going to write down statistics data, as some are missing");
            return false;
        }

        Double coverageValue = normalizeGoalsAndCoverage(map, individual);
        normalizeCriterionCoverage(map, coverageValue);
        if (coverageValue == null || coverageValue == 0.0) {
            OutputVariable<?> lineCoverageVar = map.get(RuntimeVariable.LineCoverage.name());
            if (lineCoverageVar == null) {
                lineCoverageVar = outputVariables.get(RuntimeVariable.LineCoverage.name());
            }
            OutputVariable<?> criterionVar = map.get("criterion");
            Double lineCoverage = lineCoverageVar == null ? null : toDouble(lineCoverageVar.getValue());
            String criterion = criterionVar == null ? "" : String.valueOf(criterionVar.getValue()).trim();
            boolean isLineCriterion = criterion.equalsIgnoreCase("LINE")
                    || criterion.equalsIgnoreCase("ONLYLINE")
                    || criterion.isEmpty();
            OutputVariable<?> totalGoalsOut = map.get(RuntimeVariable.Total_Goals.toString());
            OutputVariable<?> coveredGoalsOut = map.get(RuntimeVariable.Covered_Goals.toString());
            boolean missingTotals =
                    totalGoalsOut == null || coveredGoalsOut == null
                            || toDouble(totalGoalsOut.getValue()) == null
                            || toDouble(coveredGoalsOut.getValue()) == null;
            if (lineCoverage != null && isLineCriterion && missingTotals) {
                map.put(RuntimeVariable.Coverage.toString(),
                        new OutputVariable<>(RuntimeVariable.Coverage.toString(), lineCoverage));
            }
        }

        setCoverageUnavailableIfMissing(map);

        boolean valid = StatisticsValidator.validateRuntimeVariables(map);
        if (!valid) {
            logger.error("Not going to write down statistics data, as some data is invalid");
            return false;
        } else {
            backend.writeData(individual, map);
            return true;
        }
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            String str = ((String) value).trim();
            if (str.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Ensure goal counters are present and derive aggregate coverage from them.
     *
     * @return the computed coverage value if available, otherwise null
     */
    private Double normalizeGoalsAndCoverage(Map<String, OutputVariable<?>> map, TestSuiteChromosome individual) {
        OutputVariable<?> coverageVar = map.get(RuntimeVariable.Coverage.toString());
        if (coverageVar == null) {
            coverageVar = outputVariables.get(RuntimeVariable.Coverage.toString());
        }
        Double existingCoverage = coverageVar == null ? null : toDouble(coverageVar.getValue());

        OutputVariable<?> totalGoalsVar = map.get(RuntimeVariable.Total_Goals.toString());
        OutputVariable<?> coveredGoalsVar = map.get(RuntimeVariable.Covered_Goals.toString());
        if (totalGoalsVar == null) {
            totalGoalsVar = outputVariables.get(RuntimeVariable.Total_Goals.toString());
        }
        if (coveredGoalsVar == null) {
            coveredGoalsVar = outputVariables.get(RuntimeVariable.Covered_Goals.toString());
        }

        Double totalGoals = totalGoalsVar == null ? null : toDouble(totalGoalsVar.getValue());
        Double coveredGoals = coveredGoalsVar == null ? null : toDouble(coveredGoalsVar.getValue());

        // Only normalize missing counters to 0/0. Do not derive from chromosome internals,
        // as that can conflict with criterion-specific coverage semantics.
        if (totalGoals == null && coveredGoals == null) {
            totalGoals = 0.0;
            coveredGoals = 0.0;
            map.put(RuntimeVariable.Covered_Goals.toString(),
                    new OutputVariable<>(RuntimeVariable.Covered_Goals.toString(), 0));
            map.put(RuntimeVariable.Total_Goals.toString(),
                    new OutputVariable<>(RuntimeVariable.Total_Goals.toString(), 0));
        }

        if (totalGoals != null && coveredGoals != null) {
            if (coveredGoals > totalGoals) {
                logger.warn("Sanitizing inconsistent goal counts before writing statistics: covered {} > total {}",
                        coveredGoals, totalGoals);
                totalGoals = coveredGoals;
                map.put(RuntimeVariable.Total_Goals.toString(),
                        new OutputVariable<>(RuntimeVariable.Total_Goals.toString(), totalGoals.intValue()));
            }
            // Preserve already computed aggregate coverage (e.g., averages across multiple criteria).
            if (existingCoverage != null) {
                if (totalGoals == 0.0 && coveredGoals == 0.0 && existingCoverage == 0.0) {
                    map.put(RuntimeVariable.Coverage.toString(),
                            new OutputVariable<>(RuntimeVariable.Coverage.toString(), 1.0));
                    return 1.0;
                }
                return existingCoverage;
            }

            Double coverageValue = totalGoals == 0.0 ? 1.0 : coveredGoals / totalGoals;
            map.put(RuntimeVariable.Coverage.toString(),
                    new OutputVariable<>(RuntimeVariable.Coverage.toString(), coverageValue));
            return coverageValue;
        }
        return existingCoverage;
    }

    /**
     * Fill criterion-specific coverage variables when they are missing.
     */
    private static void normalizeCriterionCoverage(Map<String, OutputVariable<?>> map, Double coverageValue) {
        if (coverageValue == null) {
            return;
        }
        OutputVariable<?> criterionVar = map.get("criterion");
        String criterion = criterionVar == null ? "" : String.valueOf(criterionVar.getValue()).trim();
        if (criterion.equalsIgnoreCase("BRANCH")) {
            OutputVariable<?> branchCoverageVar = map.get(RuntimeVariable.BranchCoverage.toString());
            Double branchCoverage = branchCoverageVar == null ? null : toDouble(branchCoverageVar.getValue());
            if (branchCoverage == null) {
                map.put(RuntimeVariable.BranchCoverage.toString(),
                        new OutputVariable<>(RuntimeVariable.BranchCoverage.toString(), coverageValue));
            }
        }
    }

    private void setCoverageUnavailableIfMissing(Map<String, OutputVariable<?>> map) {
        OutputVariable<?> coverageVar = map.get(RuntimeVariable.Coverage.toString());
        if (coverageVar == null || toDouble(coverageVar.getValue()) == null) {
            if (backend instanceof CSVStatisticsBackend) {
                map.put(RuntimeVariable.Coverage.toString(),
                        new OutputVariable<>(RuntimeVariable.Coverage.toString(), "N/A"));
            } else {
                map.put(RuntimeVariable.Coverage.toString(),
                        new OutputVariable<>(RuntimeVariable.Coverage.toString(), 0.0));
            }
        }
    }

    /**
     * Process status update event received from client.
     */
    @Override
    public void receiveEvent(ClientStateInformation information) {
        if (information.getState() != currentState) {
            logger.info("Received status update: " + information);
            if (information.getState() == ClientState.SEARCH) {
                searchStartTime = System.currentTimeMillis();
                for (SequenceOutputVariableFactory<?> factory : sequenceOutputVariableFactories.values()) {
                    factory.setStartTime(searchStartTime);
                }
            }
            OutputVariable<Long> time = new OutputVariable<>("Time_" + currentState.getName(),
                    System.currentTimeMillis() - currentStateStarted);
            outputVariables.put(time.getName(), time);
            currentState = information.getState();
            currentStateStarted = System.currentTimeMillis();
        }

    }

    /**
     * Create default factories.
     */
    private void initFactories() {
        variableFactories.put(RuntimeVariable.Length.name(), new ChromosomeLengthOutputVariableFactory());
        variableFactories.put(RuntimeVariable.Size.name(), new ChromosomeSizeOutputVariableFactory());
        variableFactories.put(RuntimeVariable.Coverage.name(), new ChromosomeCoverageOutputVariableFactory());
        variableFactories.put(RuntimeVariable.Fitness.name(), new ChromosomeFitnessOutputVariableFactory());
    }

    /**
     * Total length of a test suite.
     */
    private static class ChromosomeLengthOutputVariableFactory extends ChromosomeOutputVariableFactory<Integer> {
        public ChromosomeLengthOutputVariableFactory() {
            super(RuntimeVariable.Length);
        }

        @Override
        protected Integer getData(TestSuiteChromosome individual) {
            return individual.totalLengthOfTestCases();
        }
    }

    /**
     * Number of tests in a test suite.
     */
    private static class ChromosomeSizeOutputVariableFactory extends ChromosomeOutputVariableFactory<Integer> {
        public ChromosomeSizeOutputVariableFactory() {
            super(RuntimeVariable.Size);
        }

        @Override
        protected Integer getData(TestSuiteChromosome individual) {
            return individual.size();
        }
    }

    /**
     * Fitness value of a test suite.
     */
    private static class ChromosomeFitnessOutputVariableFactory extends ChromosomeOutputVariableFactory<Double> {
        public ChromosomeFitnessOutputVariableFactory() {
            super(RuntimeVariable.Fitness);
        }

        @Override
        protected Double getData(TestSuiteChromosome individual) {
            return individual.getFitness();
        }
    }

    /**
     * Coverage value of a test suite.
     */
    private static class ChromosomeCoverageOutputVariableFactory extends ChromosomeOutputVariableFactory<Double> {
        public ChromosomeCoverageOutputVariableFactory() {
            super(RuntimeVariable.Coverage);
        }

        @Override
        protected Double getData(TestSuiteChromosome individual) {
            return individual.getCoverage();
        }
    }

    /**
     * Sequence variable for fitness values.
     */
    private static class FitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public FitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.FitnessTimeline);
        }

        @Override
        protected Double getValue(TestSuiteChromosome individual) {
            return individual.getFitness();
        }
    }

    /**
     * Sequence variable for coverage values.
     */
    private static class CoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public CoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.CoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverage();
        }
    }

    /**
     * Sequence variable for number of tests.
     */
    private static class SizeSequenceOutputVariableFactory extends IntegerSequenceOutputVariableFactory {

        public SizeSequenceOutputVariableFactory() {
            super(RuntimeVariable.SizeTimeline);
        }

        @Override
        public Integer getValue(TestSuiteChromosome individual) {
            return individual.size();
        }
    }

    /**
     * Sequence variable for total length of tests.
     */
    private static class LengthSequenceOutputVariableFactory extends IntegerSequenceOutputVariableFactory {

        public LengthSequenceOutputVariableFactory() {
            super(RuntimeVariable.LengthTimeline);
        }

        @Override
        public Integer getValue(TestSuiteChromosome individual) {
            return individual.totalLengthOfTestCases();
        }
    }

    /**
     * Sequence variable for coverage values.
     */
    private static class IBranchGoalsSequenceOutputVariableFactory extends IntegerSequenceOutputVariableFactory {

        public IBranchGoalsSequenceOutputVariableFactory() {
            super(RuntimeVariable.IBranchGoalsTimeline);
        }

        @Override
        public Integer getValue(TestSuiteChromosome individual) {
            return individual.getNumOfNotCoveredGoals();
        }
    }

    private static class BranchCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public BranchCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.BranchCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(BranchCoverageSuiteFitness.class);
        }
    }

    private static class OnlyBranchFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public OnlyBranchFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.OnlyBranchFitnessTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(OnlyBranchCoverageSuiteFitness.class);
        }
    }

    private static class OnlyBranchCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public OnlyBranchCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.OnlyBranchCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(OnlyBranchCoverageSuiteFitness.class);
        }
    }

    private static class CBranchFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public CBranchFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.CBranchFitnessTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(CBranchSuiteFitness.class);
        }
    }

    private static class CBranchCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public CBranchCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.CBranchCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(CBranchSuiteFitness.class);
        }
    }

    private static class MethodTraceFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public MethodTraceFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.MethodTraceFitnessTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(MethodTraceCoverageSuiteFitness.class);
        }
    }

    private static class MethodTraceCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public MethodTraceCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.MethodTraceCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(MethodTraceCoverageSuiteFitness.class);
        }
    }

    private static class MethodFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public MethodFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.MethodFitnessTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(MethodCoverageSuiteFitness.class);
        }
    }

    private static class MethodCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public MethodCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.MethodCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(MethodCoverageSuiteFitness.class);
        }
    }

    private static class MethodNoExceptionFitnessSequenceOutputVariableFactory
            extends DoubleSequenceOutputVariableFactory {

        public MethodNoExceptionFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.MethodNoExceptionFitnessTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(MethodNoExceptionCoverageSuiteFitness.class);
        }
    }

    private static class MethodNoExceptionCoverageSequenceOutputVariableFactory
            extends DoubleSequenceOutputVariableFactory {

        public MethodNoExceptionCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.MethodNoExceptionCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(MethodNoExceptionCoverageSuiteFitness.class);
        }
    }

    private static class RhoFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public RhoFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.RhoScoreTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            Double d = individual.getFitnessInstanceOf(RhoCoverageSuiteFitness.class);
            return d > 1.0 ? 0.0 : d;
        }
    }

    private static class AmbiguityFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public AmbiguityFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.AmbiguityScoreTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(AmbiguityCoverageSuiteFitness.class);
        }
    }

    private static class LineFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public LineFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.LineFitnessTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(LineCoverageSuiteFitness.class);
        }
    }

    private static class LineCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public LineCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.LineCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(LineCoverageSuiteFitness.class);
        }
    }

    private static class OutputFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public OutputFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.OutputFitnessTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(OutputCoverageSuiteFitness.class);
        }
    }

    private static class OutputCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public OutputCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.OutputCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(OutputCoverageSuiteFitness.class);
        }
    }

    private static class InputFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public InputFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.InputFitnessTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(InputCoverageSuiteFitness.class);
        }
    }

    private static class InputCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public InputCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.InputCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(InputCoverageSuiteFitness.class);
        }
    }

    private static class ExceptionFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public ExceptionFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.ExceptionFitnessTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(ExceptionCoverageSuiteFitness.class);
        }
    }

    private static class ExceptionCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public ExceptionCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.ExceptionCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(ExceptionCoverageSuiteFitness.class);
        }
    }

    private static class WeakMutationCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public WeakMutationCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.WeakMutationCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(WeakMutationSuiteFitness.class);
        }
    }

    private static class OnlyMutationFitnessSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public OnlyMutationFitnessSequenceOutputVariableFactory() {
            super(RuntimeVariable.OnlyMutationFitnessTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getFitnessInstanceOf(OnlyMutationSuiteFitness.class);
        }
    }

    private static class OnlyMutationCoverageSequenceOutputVariableFactory extends DoubleSequenceOutputVariableFactory {

        public OnlyMutationCoverageSequenceOutputVariableFactory() {
            super(RuntimeVariable.OnlyMutationCoverageTimeline);
        }

        @Override
        public Double getValue(TestSuiteChromosome individual) {
            return individual.getCoverageInstanceOf(OnlyMutationSuiteFitness.class);
        }
    }
}
