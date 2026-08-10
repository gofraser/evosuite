/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.testcase.synthesizer;

import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.GenerationContext;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.VariableResolutionConfig;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement.MockInvocationObservation;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime-guided materialization and reuse of functional-mock return-value stubs. */
public final class FunctionalMockSetupCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(FunctionalMockSetupCoordinator.class);

    private static final Map<String, CachedRecipe> SUCCESSFUL_RECIPES =
            new ConcurrentHashMap<>();
    private static final Map<String, Integer> FAILED_RECIPES =
            new ConcurrentHashMap<>();

    private static int opportunities;
    private static int methodsObserved;
    private static int stubsAdded;
    private static int attempts;
    private static int successes;
    private static int proactiveUses;
    private static int extraExecutions;
    private static int negativeCacheHits;
    private static int blacklistedRecipes;

    private FunctionalMockSetupCoordinator() {
        // utility class
    }

    private static final class CachedRecipe {
        private final List<MockInvocationObservation> observations;
        private final boolean preferNonDefaultBooleans;
        private final Number numericHint;

        private CachedRecipe(List<MockInvocationObservation> observations,
                             boolean preferNonDefaultBooleans, Number numericHint) {
            this.observations = observations;
            this.preferNonDefaultBooleans = preferNonDefaultBooleans;
            this.numericHint = numericHint;
        }
    }

    /** The recipes materialized into one reactive candidate. */
    public static final class RepairToken {
        private final Map<String, List<MockInvocationObservation>> recipes;
        private final List<String> failureKeys;
        private final int addedStubs;
        private final boolean preferNonDefaultBooleans;
        private final Number numericHint;

        private RepairToken(Map<String, List<MockInvocationObservation>> recipes,
                            List<String> failureKeys, int addedStubs,
                            boolean preferNonDefaultBooleans, Number numericHint) {
            this.recipes = recipes;
            this.failureKeys = failureKeys;
            this.addedStubs = addedStubs;
            this.preferNonDefaultBooleans = preferNonDefaultBooleans;
            this.numericHint = numericHint;
        }

        public int getAddedStubs() {
            return addedStubs;
        }
    }

    /** A cloned template carrying one candidate set of functional-mock stubs. */
    public static final class RepairProposal {
        private final TestCase test;
        private final RepairToken token;

        private RepairProposal(TestCase test, RepairToken token) {
            this.test = test;
            this.token = token;
        }

        public TestCase getTest() {
            return test;
        }
    }

    /** Applies cached stubs to matching mocks before their template's normal first execution. */
    public static synchronized boolean applyCachedSetup(TestCase test) {
        if (!enabled() || test == null || SUCCESSFUL_RECIPES.isEmpty()) {
            return false;
        }
        boolean changed = false;
        Map<String, Integer> ordinals = new HashMap<>();
        List<FunctionalMockStatement> mocks = functionalMocks(test);
        for (FunctionalMockStatement mock : mocks) {
            String key = recipeKey(test, mock,
                    nextOrdinal(ordinals, mock.getTargetClass().getName()));
            CachedRecipe recipe = SUCCESSFUL_RECIPES.get(key);
            if (recipe == null || recipe.observations.isEmpty()
                    || hasCompatibleStub(mock, recipe.observations)) {
                continue;
            }
            int originalSize = test.size();
            int insertionStart = mock.getPosition();
            try {
                int added = materializeCachedObservations(test, mock, recipe.observations,
                        recipe.preferNonDefaultBooleans, recipe.numericHint);
                if (added > 0) {
                    stubsAdded += added;
                    changed = true;
                }
            } catch (ConstructionFailedException | RuntimeException failure) {
                // Cached setup is best-effort. Leave this mock untouched and let execution
                // rediscover the requirement through its invocation listener.
                while (test.size() > originalSize
                        && insertionStart >= 0 && insertionStart < test.size()) {
                    test.remove(insertionStart);
                }
            }
        }
        if (changed) {
            proactiveUses++;
        }
        return changed;
    }

    /**
     * Materializes every currently observed missing stub into an already-cloned candidate. The
     * clone retains the read-only invocation listener from the preceding execution.
     */
    public static RepairToken applyObservedSetup(TestCase candidate) {
        return applyObservedSetup(candidate, true, null);
    }

    /**
     * Materializes observed stubs, optionally seeding boolean returns with the non-default
     * direction. Functional mocks and Java primitives otherwise overwhelmingly produce false,
     * making a missing true branch effectively invisible to setup repair.
     */
    public static synchronized RepairToken applyObservedSetup(TestCase candidate,
                                                 boolean preferNonDefaultBooleans) {
        return applyObservedSetup(candidate, preferNonDefaultBooleans, null);
    }

    /**
     * Materializes observed stubs with an optional value inferred from the active branch
     * comparison. Without a hint, numeric returns use small deterministic values rather than
     * unrestricted constant-pool values such as {@link Integer#MAX_VALUE}.
     */
    public static RepairToken applyObservedSetup(TestCase candidate,
                                                 boolean preferNonDefaultBooleans,
                                                 Number numericHint) {
        if (!enabled() || candidate == null) {
            return null;
        }
        Map<String, Integer> ordinals = new HashMap<>();
        Map<String, List<MockInvocationObservation>> recipes = new LinkedHashMap<>();
        List<String> failureKeys = new ArrayList<>();
        int observed = 0;
        int added = 0;
        boolean opportunityRecorded = false;
        boolean negativeCacheHit = false;

        for (FunctionalMockStatement mock : functionalMocks(candidate)) {
            int ordinal = nextOrdinal(ordinals, mock.getTargetClass().getName());
            List<MockInvocationObservation> observations =
                    mock.getInvocationObservationsNeedingStubs();
            if (observations.isEmpty()) {
                continue;
            }
            if (!opportunityRecorded) {
                opportunities++;
                opportunityRecorded = true;
            }
            observed += observations.size();
            methodsObserved += observations.size();
            String key = recipeKey(candidate, mock, ordinal);
            String failureKey = failureKey(
                    key, observations, preferNonDefaultBooleans, numericHint);
            if (FAILED_RECIPES.getOrDefault(failureKey, 0)
                    >= ObjectRulePolicy.MAX_FAILED_ATTEMPTS) {
                negativeCacheHit = true;
                continue;
            }
            recipes.put(key, copyObservations(observations));
            failureKeys.add(failureKey);
            try {
                List<Type> missingTypes = mock.updateMockedMethods();
                List<VariableReference> values = synthesizeReturnValues(
                        candidate, mock, missingTypes,
                        preferNonDefaultBooleans, numericHint);
                mock.addMissingInputs(values);
                added += values.size();
            } catch (ConstructionFailedException | RuntimeException failure) {
                logger.debug("Could not materialize functional-mock setup for {}", key, failure);
                return null;
            }
        }

        if (negativeCacheHit) {
            negativeCacheHits++;
        }

        if (observed == 0 || added == 0 || recipes.isEmpty()) {
            return null;
        }
        stubsAdded += added;
        attempts++;
        return new RepairToken(recipes, Collections.unmodifiableList(failureKeys), added,
                preferNonDefaultBooleans, numericHint);
    }

    /**
     * Creates bounded repair variants from observations made during the template's first execution.
     * Boolean returns try the non-default direction first and, when the shared setup budget
     * permits, the default direction second.
     */
    public static List<RepairProposal> proposeRepairs(
            TestCase original, ExecutionResult result, Number numericHint) {
        boolean booleanVariant = hasObservedBooleanSetup(original);
        boolean observed = hasObservedSetup(original);
        if (ObjectRulePolicy.MAX_EXTRA_EXECUTIONS <= 0
                || original == null
                || result == null
                || result.hasTimeout()
                || (result.getFirstPositionOfThrownException() == null && !observed)) {
            return Collections.emptyList();
        }

        int limit = ObjectRulePolicy.MAX_EXTRA_EXECUTIONS;
        List<RepairProposal> proposals = new ArrayList<>();
        addProposal(proposals, original, true, numericHint);
        if (booleanVariant && limit > 1) {
            addProposal(proposals, original, false, numericHint);
        }
        return proposals;
    }

    public static RepairProposal proposeRepair(TestCase original, ExecutionResult result) {
        List<RepairProposal> proposals = proposeRepairs(original, result, null);
        return proposals.isEmpty() ? null : proposals.get(0);
    }

    public static List<RepairProposal> proposeRepairs(
            TestCase original, ExecutionResult result) {
        return proposeRepairs(original, result, null);
    }

    private static void addProposal(List<RepairProposal> proposals, TestCase original,
                                    boolean preferNonDefaultBooleans, Number numericHint) {
        TestCase candidate = original.clone();
        RepairToken token = applyObservedSetup(
                candidate, preferNonDefaultBooleans, numericHint);
        if (token != null) {
            proposals.add(new RepairProposal(candidate, token));
        }
    }

    public static void recordExtraExecution(RepairProposal proposal) {
        if (proposal != null) {
            recordExtraExecution();
        }
    }

    public static void recordSuccess(
            RepairProposal proposal, boolean cacheForProactiveUse) {
        if (proposal != null) {
            recordSuccess(proposal.token, cacheForProactiveUse);
        }
    }

    public static void recordFailure(RepairProposal proposal) {
        if (proposal != null) {
            recordFailure(proposal.token);
        }
    }

    /** Returns whether the preceding execution exposed at least one missing mock return value. */
    public static boolean hasObservedSetup(TestCase test) {
        if (!enabled() || test == null) {
            return false;
        }
        for (FunctionalMockStatement mock : functionalMocks(test)) {
            if (!mock.getInvocationObservationsNeedingStubs().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static void recordSuccess(RepairToken token) {
        recordSuccess(token, true);
    }

    /**
     * Records an improving proposal, but only makes it proactive when the caller observed an
     * immediate working-goal payoff. A setup which merely throws later or lowers fitness is still
     * useful as the current GA individual; caching it for every later synthesis can otherwise
     * suppress branch negative caching and reproduce the same non-covering template indefinitely.
     */
    public static synchronized void recordSuccess(RepairToken token, boolean cacheForProactiveUse) {
        if (token == null) {
            return;
        }
        successes++;
        for (String failureKey : token.failureKeys) {
            FAILED_RECIPES.remove(failureKey);
        }
        if (!cacheForProactiveUse) {
            return;
        }
        for (Map.Entry<String, List<MockInvocationObservation>> entry : token.recipes.entrySet()) {
            SUCCESSFUL_RECIPES.put(entry.getKey(), new CachedRecipe(
                    copyObservations(entry.getValue()), token.preferNonDefaultBooleans,
                    token.numericHint));
        }
    }

    /** Records an executed mock-setup proposal which did not improve the template. */
    public static synchronized void recordFailure(RepairToken token) {
        if (token == null) {
            return;
        }
        for (String failureKey : token.failureKeys) {
            int previous = FAILED_RECIPES.getOrDefault(failureKey, 0);
            int updated = previous + 1;
            FAILED_RECIPES.put(failureKey, updated);
            if (previous < ObjectRulePolicy.MAX_FAILED_ATTEMPTS
                    && updated >= ObjectRulePolicy.MAX_FAILED_ATTEMPTS) {
                blacklistedRecipes++;
            }
        }
    }

    public static synchronized void recordExtraExecution() {
        extraExecutions++;
    }

    private static int materializeCachedObservations(TestCase test, FunctionalMockStatement mock,
                                                     List<MockInvocationObservation> observations,
                                                     boolean preferNonDefaultBooleans,
                                                     Number numericHint)
            throws ConstructionFailedException {
        int added = 0;
        List<List<VariableReference>> valuesByObservation = new ArrayList<>();
        for (MockInvocationObservation observation : observations) {
            List<Type> types = new ArrayList<>();
            for (int i = 0; i < observation.getMissingReturnValues(); i++) {
                types.add(observation.getReturnType());
            }
            List<VariableReference> values = synthesizeReturnValues(test, mock, types,
                    preferNonDefaultBooleans, numericHint);
            valuesByObservation.add(values);
            added += values.size();
        }
        for (int i = 0; i < observations.size(); i++) {
            mock.addMethodStubbing(
                    observations.get(i).getDescriptor(), valuesByObservation.get(i));
        }
        return added;
    }

    private static List<VariableReference> synthesizeReturnValues(
            TestCase test, FunctionalMockStatement boundaryMock, List<Type> returnTypes,
            boolean preferNonDefaultBooleans, Number numericHint)
            throws ConstructionFailedException {
        if (returnTypes.isEmpty()) {
            return Collections.emptyList();
        }
        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withAllowNull(false)
                .withCanUseMocks(true)
                .withCanReuseExistingVariables(true)
                .withPreferConcreteGenerators(true)
                .build();
        List<VariableReference> values = new ArrayList<>();
        for (Type returnType : returnTypes) {
            int insertion = boundaryMock == null ? test.size() : boundaryMock.getPosition();
            VariableReference primitive = createStablePrimitiveReturn(
                    test, returnType, insertion, numericHint);
            values.add(primitive != null
                    ? primitive
                    : createMockReturnValue(test, returnType, insertion, config));
        }
        for (int i = 0; i < returnTypes.size() && i < values.size(); i++) {
            Type type = returnTypes.get(i);
            if (type == boolean.class || type == Boolean.class) {
                int definition = definitionPosition(test, values.get(i));
                if (definition >= 0
                        && test.getStatement(definition) instanceof BooleanPrimitiveStatement) {
                    ((BooleanPrimitiveStatement) test.getStatement(definition))
                            .setValue(preferNonDefaultBooleans);
                }
            }
            materializeReturnedContainer(test, values.get(i), type, boundaryMock, config);
        }
        return values;
    }

    private static VariableReference createMockReturnValue(TestCase test, Type returnType,
            int position, VariableResolutionConfig config) throws ConstructionFailedException {
        if (returnType instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) returnType;
            if (parameterized.getRawType() instanceof Class<?>) {
                Class<?> raw = (Class<?>) parameterized.getRawType();
                Class<?> concrete = Collection.class.isAssignableFrom(raw)
                        ? java.util.ArrayList.class
                        : (Map.class.isAssignableFrom(raw) ? LinkedHashMap.class : null);
                if (concrete != null) {
                    try {
                        Type exactType = new ParameterizedTypeImpl(concrete,
                                parameterized.getActualTypeArguments(), null);
                        GenericConstructor constructor = new GenericConstructor(
                                concrete.getConstructor(), exactType);
                        return TestFactory.getInstance().addConstructor(test, constructor,
                                exactType, position, GenerationContext.fromDepth(1));
                    } catch (NoSuchMethodException failure) {
                        throw new ConstructionFailedException(failure.toString());
                    }
                }
            }
        }
        List<VariableReference> value = TestFactory.getInstance().satisfyParameters(
                test, null, Collections.singletonList(returnType), null, position,
                GenerationContext.fromDepth(1), config);
        if (value.isEmpty()) {
            throw new ConstructionFailedException("No return value for " + returnType);
        }
        return value.get(0);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static VariableReference createStablePrimitiveReturn(
            TestCase test, Type returnType, int position, Number numericHint) {
        if (!(returnType instanceof Class<?>)) {
            return null;
        }
        Class<?> raw = (Class<?>) returnType;
        if (!raw.isPrimitive() || raw == boolean.class || raw == char.class) {
            return null;
        }
        Number source = numericHint == null ? Integer.valueOf(1) : numericHint;
        Object value = convertNumber(source, raw);
        PrimitiveStatement statement = PrimitiveStatement.getPrimitiveStatement(
                test, GenericClassFactory.get(raw));
        statement.setValue(value);
        return test.addStatement(statement, position);
    }

    private static Object convertNumber(Number value, Class<?> primitive) {
        if (primitive == byte.class) return value.byteValue();
        if (primitive == short.class) return value.shortValue();
        if (primitive == int.class) return value.intValue();
        if (primitive == long.class) return value.longValue();
        if (primitive == float.class) return value.floatValue();
        if (primitive == double.class) return value.doubleValue();
        throw new IllegalArgumentException("Unsupported numeric primitive " + primitive);
    }

    /**
     * Seed one generic element into collection/map mock returns so a repaired execution can
     * distinguish an iteration-dependent path from Mockito's empty-container default.
     */
    private static void materializeReturnedContainer(
            TestCase test,
            VariableReference container,
            Type returnType,
            FunctionalMockStatement boundaryMock,
            VariableResolutionConfig config)
            throws ConstructionFailedException {
        if (!(returnType instanceof ParameterizedType) || container == null) {
            return;
        }
        ParameterizedType parameterized = (ParameterizedType) returnType;
        if (!(parameterized.getRawType() instanceof Class<?>)) {
            return;
        }
        Class<?> raw = (Class<?>) parameterized.getRawType();
        Type[] arguments = parameterized.getActualTypeArguments();
        int insertion = boundaryMock == null ? test.size() : boundaryMock.getPosition();
        try {
            if (Collection.class.isAssignableFrom(raw) && arguments.length == 1) {
                VariableReference element = createRequiredValue(
                        test, arguments[0], insertion, config);
                insertion = boundaryMock == null ? test.size() : boundaryMock.getPosition();
                java.lang.reflect.Method method = Collection.class.getMethod("add", Object.class);
                GenericMethod add = new GenericMethod(method, method.getDeclaringClass())
                        .copyWithNewOwner(container.getGenericClass());
                test.addStatement(new MethodStatement(test, add, container,
                        Collections.singletonList(element)), insertion);
            } else if (Map.class.isAssignableFrom(raw) && arguments.length == 2) {
                VariableReference key = createRequiredValue(test, arguments[0], insertion, config);
                insertion = boundaryMock == null ? test.size() : boundaryMock.getPosition();
                VariableReference value = createRequiredValue(test, arguments[1], insertion, config);
                insertion = boundaryMock == null ? test.size() : boundaryMock.getPosition();
                java.lang.reflect.Method method = Map.class.getMethod(
                        "put", Object.class, Object.class);
                GenericMethod put = new GenericMethod(method, method.getDeclaringClass())
                        .copyWithNewOwner(container.getGenericClass());
                test.addStatement(new MethodStatement(test, put, container,
                        java.util.Arrays.asList(key, value)), insertion);
            }
        } catch (ConstructionFailedException failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new ConstructionFailedException(failure.toString());
        }
    }

    private static VariableReference createRequiredValue(TestCase test, Type type, int position,
            VariableResolutionConfig config) throws ConstructionFailedException {
        if (FunctionalMockStatement.canBeFunctionalMocked(type)) {
            return TestFactory.getInstance().addFunctionalMock(
                    test, type, position, GenerationContext.fromDepth(2));
        }
        return TestFactory.getInstance().createObject(
                test, type, position, GenerationContext.fromDepth(2), config);
    }

    private static int definitionPosition(TestCase test, VariableReference reference) {
        for (int position = 0; position < test.size(); position++) {
            if (test.getStatement(position).getReturnValue() == reference) {
                return position;
            }
        }
        return -1;
    }

    public static boolean hasObservedBooleanSetup(TestCase test) {
        if (!enabled() || test == null) {
            return false;
        }
        for (FunctionalMockStatement mock : functionalMocks(test)) {
            for (MockInvocationObservation observation
                    : mock.getInvocationObservationsNeedingStubs()) {
                Type type = observation.getReturnType();
                if (type == boolean.class || type == Boolean.class) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<FunctionalMockStatement> functionalMocks(TestCase test) {
        List<FunctionalMockStatement> mocks = new ArrayList<>();
        for (int position = 0; position < test.size(); position++) {
            Statement statement = test.getStatement(position);
            if (statement instanceof FunctionalMockStatement) {
                mocks.add((FunctionalMockStatement) statement);
            }
        }
        return mocks;
    }

    private static boolean hasCompatibleStub(
            FunctionalMockStatement mock, List<MockInvocationObservation> observations) {
        if (mock.getMockedMethods().isEmpty()) {
            return false;
        }
        for (MockInvocationObservation observation : observations) {
            boolean found = false;
            for (MethodDescriptor stub : mock.getMockedMethods()) {
                if (stub.getID().equals(observation.getDescriptor().getID())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static int nextOrdinal(Map<String, Integer> ordinals, String type) {
        Integer previous = ordinals.get(type);
        int ordinal = previous == null ? 0 : previous;
        ordinals.put(type, ordinal + 1);
        return ordinal;
    }

    private static String recipeKey(TestCase test, FunctionalMockStatement mock, int ordinal) {
        return Properties.TARGET_CLASS + "|" + mock.getTargetClass().getName() + "|" + ordinal
                + "|context=" + mockContext(test, mock);
    }

    /**
     * A mock ordinal is not stable across whole-class branches. Include the statements which
     * consume this particular mock so a recipe learned from one use cannot be replayed at an
     * unrelated use of the same mock type.
     */
    private static String mockContext(TestCase test, FunctionalMockStatement mock) {
        StringBuilder context = new StringBuilder();
        VariableReference returnValue = mock.getReturnValue();
        for (int position = mock.getPosition() + 1; position < test.size(); position++) {
            Statement statement = test.getStatement(position);
            if (statement.getVariableReferences().contains(returnValue)) {
                context.append(position - mock.getPosition()).append(':')
                        .append(statement).append(';');
            }
        }
        return context.length() == 0 ? "none" : context.toString();
    }

    private static String failureKey(String recipeKey,
                                     List<MockInvocationObservation> observations,
                                     boolean preferNonDefaultBooleans,
                                     Number numericHint) {
        List<String> signatures = new ArrayList<>();
        for (MockInvocationObservation observation : observations) {
            signatures.add(observation.getDescriptor().getID()
                    + "->" + observation.getReturnType().getTypeName()
                    + "x" + observation.getMissingReturnValues());
        }
        Collections.sort(signatures);
        return recipeKey + "|boolean=" + preferNonDefaultBooleans
                + "|numeric=" + numericHint
                + "|" + String.join(",", signatures);
    }

    private static List<MockInvocationObservation> copyObservations(
            List<MockInvocationObservation> observations) {
        // Observation instances are immutable and defensively copy their MethodDescriptor getter.
        return Collections.unmodifiableList(new ArrayList<>(observations));
    }

    private static boolean enabled() {
        return true;
    }

    public static synchronized void reset() {
        SUCCESSFUL_RECIPES.clear();
        FAILED_RECIPES.clear();
        opportunities = 0;
        methodsObserved = 0;
        stubsAdded = 0;
        attempts = 0;
        successes = 0;
        proactiveUses = 0;
        extraExecutions = 0;
        negativeCacheHits = 0;
        blacklistedRecipes = 0;
    }

    public static synchronized int getOpportunities() { return opportunities; }
    public static synchronized int getMethodsObserved() { return methodsObserved; }
    public static synchronized int getStubsAdded() { return stubsAdded; }
    public static synchronized int getAttempts() { return attempts; }
    public static synchronized int getSuccesses() { return successes; }
    public static synchronized int getProactiveUses() { return proactiveUses; }
    public static synchronized int getExtraExecutions() { return extraExecutions; }
    public static synchronized int getNegativeCacheHits() { return negativeCacheHits; }
    public static synchronized int getBlacklistedRecipes() { return blacklistedRecipes; }
}
