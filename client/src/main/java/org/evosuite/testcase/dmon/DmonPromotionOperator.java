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
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.GenerationContext;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.reflection.PrivateFieldStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Option 1 integration point: consume promotion plans at fitness level.
 */
public final class DmonPromotionOperator {

    private static final Logger logger = LoggerFactory.getLogger(DmonPromotionOperator.class);

    private static final class PromotionAttempt {
        private final ExecutionResult effectiveResult;
        private final boolean applied;
        private final String reason;

        private PromotionAttempt(ExecutionResult effectiveResult, boolean applied, String reason) {
            this.effectiveResult = effectiveResult;
            this.applied = applied;
            this.reason = reason;
        }
    }

    private interface ValidationStaticRestore {
        void restore() throws IllegalAccessException;
    }

    private static final class FieldValidationRestore implements ValidationStaticRestore {
        private final Field field;
        private final Object oldValue;

        private FieldValidationRestore(Field field, Object oldValue) {
            this.field = field;
            this.oldValue = oldValue;
        }

        @Override
        public void restore() throws IllegalAccessException {
            field.setAccessible(true);
            field.set(null, oldValue);
        }
    }

    private static final class InstanceFieldValidationRestore implements ValidationStaticRestore {
        private final Field field;
        private final Object receiver;
        private final Object oldValue;

        private InstanceFieldValidationRestore(Field field, Object receiver, Object oldValue) {
            this.field = field;
            this.receiver = receiver;
            this.oldValue = oldValue;
        }

        @Override
        public void restore() throws IllegalAccessException {
            field.setAccessible(true);
            field.set(receiver, oldValue);
        }
    }

    private DmonPromotionOperator() {
    }

    /**
     * Apply DMoN promotion if needed.
     *
     * @param individual The chromosome to evaluate.
     * @param result The current execution result.
     * @return The updated execution result.
     */
    public static ExecutionResult applyIfNeeded(TestChromosome individual, ExecutionResult result) {
        if (!Properties.DMON_ENABLED || !Properties.DMON_PROMOTE_IN_PLACE || Properties.NO_RUNTIME_DEPENDENCY) {
            return result;
        }
        if (individual == null || result == null) {
            return result;
        }
        logger.debug("DMoN promotion: evaluate [hasPlan={}, consumed={}, contaminated={}]",
                result.getDmonPromotionPlan() != null,
                result.isDmonPromotionConsumed(),
                result.isDmonContaminated());
        if (!result.hasUnconsumedDmonPromotionPlan()) {
            logger.trace("DMoN promotion: skip [reason=NO_UNCONSUMED_PLAN]");
            return result;
        }
        if (result.isDmonContaminated()) {
            DmonPromotionPlan contaminatedPlan = result.getDmonPromotionPlan();
            if (contaminatedPlan != null && contaminatedPlan.getFailureSite() != null) {
                logger.debug("Consumed DMoN promotion plan at {}.{}:{} [reason=CONTAMINATED_EXECUTION]",
                        contaminatedPlan.getFailureSite().getOwnerClass(),
                        contaminatedPlan.getFailureSite().getMethodName(),
                        contaminatedPlan.getFailureSite().getLineNumber());
            }
            result.markDmonPromotionConsumed();
            return result;
        }

        DmonPromotionPlan plan = result.getDmonPromotionPlan();
        registerOwnerForStaticReset(plan);
        PromotionAttempt attempt = tryPromoteInPlace(individual, plan, result);
        logger.debug("{} DMoN promotion plan at {}.{}:{} [reason={}]",
                attempt.applied ? "Applied" : "Consumed",
                plan.getFailureSite().getOwnerClass(),
                plan.getFailureSite().getMethodName(),
                plan.getFailureSite().getLineNumber(),
                attempt.reason);
        result.markDmonPromotionConsumed();
        return attempt.effectiveResult;
    }

    private static PromotionAttempt tryPromoteInPlace(TestChromosome individual,
                                                      DmonPromotionPlan plan,
                                                      ExecutionResult originalResult) {
        if (individual == null || plan == null) {
            return new PromotionAttempt(originalResult, false, "INVALID_INPUT");
        }
        if (!(individual.getTestCase() instanceof DefaultTestCase)) {
            return new PromotionAttempt(originalResult, false, "NON_DEFAULT_TESTCASE");
        }

        ClassLoader sutLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        Class<?> ownerClass = resolveOwnerClass(plan, sutLoader);
        if (ownerClass == null) {
            return new PromotionAttempt(originalResult, false, "OWNER_CLASS_NOT_FOUND");
        }

        Class<?> mockType = resolveMockType(plan, ownerClass, sutLoader);
        if (mockType == null) {
            return new PromotionAttempt(originalResult, false, "MOCK_TYPE_UNRESOLVED");
        }

        String fieldHint = DmonInjectionDiscovery.resolveFieldName(plan);
        Method staticTargetMethod = DmonInjectionDiscovery.findStaticSetter(ownerClass, mockType, fieldHint);
        Field staticTargetField = staticTargetMethod == null && Properties.DMON_ALLOW_REFLECTION_FALLBACK
                ? findStaticInjectionField(ownerClass, mockType, fieldHint)
                : null;
        Method instanceAccessor = null;
        Method instanceSetter = null;
        Field instanceField = null;
        if (staticTargetMethod == null && staticTargetField == null) {
            instanceAccessor = DmonInjectionDiscovery.findStaticInstanceAccessor(ownerClass);
            if (instanceAccessor != null) {
                instanceSetter = findInstanceSetter(ownerClass, mockType, fieldHint);
                if (instanceSetter == null && Properties.DMON_ALLOW_REFLECTION_FALLBACK) {
                    instanceField = findInstanceInjectionField(ownerClass, mockType, fieldHint);
                }
            }
        }
        if (staticTargetMethod == null && staticTargetField == null
                && instanceSetter == null && instanceField == null) {
            return new PromotionAttempt(originalResult, false, "NO_INJECTION_TARGET");
        }

        TestCase test = individual.getTestCase();

        // Guard: skip if the test already contains a DMoN-promoted FunctionalMock.
        // GA mutations (crossover, insertion) can shift statement positions, so we
        // scan all statements rather than checking only position 0.
        for (int i = 0; i < test.size(); i++) {
            Statement stmt = test.getStatement(i);
            if (stmt instanceof FunctionalMockStatement
                    && ((FunctionalMockStatement) stmt).isDmonPromotion()) {
                return new PromotionAttempt(originalResult, false, "ALREADY_PROMOTED");
            }
        }

        TestCase backup = test.clone();
        TestFactory factory = TestFactory.getInstance();
        try {
            VariableReference mockRef = factory.addFunctionalMock(test, mockType, 0, new GenerationContext());
            // Enable lenient default answer on the DMoN-created mock so that
            // unstubbed methods return sensible non-null values (matching the
            // ephemeral mock used during search).  Without this, the Mockito
            // default returns null for object types, causing secondary NPEs.
            Statement mockStmt = test.getStatement(mockRef.getStPosition());
            if (mockStmt instanceof FunctionalMockStatement) {
                ((FunctionalMockStatement) mockStmt).setUseLenientDefaultAnswer(true);
                ((FunctionalMockStatement) mockStmt).setDmonPromotion(true);
            }
            List<Statement> newStatements = new ArrayList<>();
            if (staticTargetMethod != null) {
                newStatements.add(buildStaticMethodStatement(test, staticTargetMethod, mockRef));
            } else if (staticTargetField != null) {
                newStatements.add(buildStaticFieldAssignment(test, staticTargetField, mockRef));
            } else {
                if (instanceAccessor == null) {
                    return new PromotionAttempt(originalResult, false, "NO_INSTANCE_ACCESSOR");
                }
                MethodStatement accessorStatement = buildStaticAccessorCall(test, instanceAccessor);
                VariableReference receiver = accessorStatement.getReturnValue();
                if (instanceSetter != null) {
                    newStatements.add(accessorStatement);
                    newStatements.add(buildInstanceMethodStatement(test, instanceSetter, receiver, mockRef));
                } else if (instanceField != null) {
                    newStatements.add(accessorStatement);
                    newStatements.add(buildInstanceFieldAssignment(test, instanceField, receiver, mockRef));
                } else {
                    return new PromotionAttempt(originalResult, false, "NO_INSTANCE_INJECTION_TARGET");
                }
            }
            int insertionIndex = 1;
            for (Statement statement : newStatements) {
                test.addStatement(statement, insertionIndex++);
            }
            if (!Properties.DMON_VALIDATE_PROMOTED_ONCE) {
                individual.setChanged(true);
                return new PromotionAttempt(originalResult, true, "PROMOTED_PENDING_REEVALUATION");
            }
            ValidationStaticRestore restore = buildValidationRestore(ownerClass, staticTargetMethod, staticTargetField,
                    instanceAccessor, instanceSetter, instanceField, fieldHint);
            return validateOrRollback(individual, backup, originalResult, restore, plan);
        } catch (ConstructionFailedException | RuntimeException e) {
            logger.debug("DMoN promotion failed: {}", e.getMessage(), e);
            return new PromotionAttempt(originalResult, false, "CONSTRUCTION_FAILED");
        } catch (IllegalAccessException e) {
            logger.debug("DMoN validation restore snapshot failed: {}", e.getMessage(), e);
            return new PromotionAttempt(originalResult, false, "VALIDATION_SNAPSHOT_FAILED");
        }
    }

    private static PromotionAttempt validateOrRollback(TestChromosome individual,
                                                       TestCase backup,
                                                       ExecutionResult originalResult,
                                                       ValidationStaticRestore restore,
                                                       DmonPromotionPlan plan) {
        boolean oldDmonEnabled = Properties.DMON_ENABLED;
        boolean restoreOk = true;
        ExecutionResult validated = null;
        boolean validatedSuccess = false;
        boolean progressAccepted = false;
        try {
            Properties.DMON_ENABLED = false;
            validated = TestCaseExecutor.runTest(individual.getTestCase());
            progressAccepted = isValidationProgressAccepted(validated, plan);
            if (progressAccepted) {
                boolean mockMaterialized = materializeFunctionalMockInvocations(individual);
                individual.setLastExecutionResult(validated);
                individual.setChanged(mockMaterialized);
                validatedSuccess = true;
            }
        } finally {
            if (restore != null) {
                try {
                    restore.restore();
                } catch (IllegalAccessException e) {
                    restoreOk = false;
                    logger.warn("DMoN validation static-state restore failed: {}", e.getMessage());
                }
            }
            Properties.DMON_ENABLED = oldDmonEnabled;
        }
        if (validatedSuccess) {
            ExecutionResult effective = markContaminatedIfNeeded(validated, !restoreOk);
            if (validated != null && validated.noThrownExceptions()) {
                return new PromotionAttempt(effective, true,
                        restoreOk ? "VALIDATION_PASSED" : "VALIDATION_PASSED_WITH_RESTORE_WARNING");
            }
            return new PromotionAttempt(effective, true,
                    restoreOk ? "VALIDATION_PROGRESS_ACCEPTED" : "VALIDATION_PROGRESS_ACCEPTED_WITH_RESTORE_WARNING");
        }
        individual.setTestCase(backup);
        ExecutionResult restored = originalResult.clone();
        restored.setTest(individual.getTestCase());
        // Mark the clone's plan as consumed so subsequent evaluations do not
        // re-attempt promotion on this already-failed plan.
        restored.markDmonPromotionConsumed();
        individual.setLastExecutionResult(restored);
        individual.setChanged(false);
        ExecutionResult effective = markContaminatedIfNeeded(restored, !restoreOk);
        return new PromotionAttempt(effective, false,
                restoreOk ? "VALIDATION_FAILED_ROLLED_BACK" : "VALIDATION_FAILED_AND_RESTORE_WARNING");
    }

    private static boolean isValidationProgressAccepted(ExecutionResult validated, DmonPromotionPlan plan) {
        if (validated == null) {
            return false;
        }
        if (validated.noThrownExceptions()) {
            return true;
        }
        DmonFailureSite original = plan == null ? null : plan.getFailureSite();
        if (original == null) {
            return false;
        }
        for (Throwable throwable : validated.getAllThrownExceptions()) {
            if (matchesFailureSite(throwable, original)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesFailureSite(Throwable throwable, DmonFailureSite target) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            StackTraceElement[] trace = current.getStackTrace();
            if (trace == null) {
                continue;
            }
            for (StackTraceElement frame : trace) {
                if (frame == null) {
                    continue;
                }
                if (!target.getOwnerClass().equals(frame.getClassName())) {
                    continue;
                }
                if (!target.getMethodName().equals(frame.getMethodName())) {
                    continue;
                }
                int targetLine = target.getLineNumber();
                if (targetLine > 0 && frame.getLineNumber() != targetLine) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean materializeFunctionalMockInvocations(TestChromosome individual) {
        if (individual == null || individual.getTestCase() == null) {
            return false;
        }
        boolean changed = false;
        TestCase test = individual.getTestCase();
        TestFactory factory = TestFactory.getInstance();
        for (int i = 0; i < test.size(); i++) {
            Statement st = test.getStatement(i);
            if (!(st instanceof FunctionalMockStatement)) {
                continue;
            }
            FunctionalMockStatement fms = (FunctionalMockStatement) st;
            if (!fms.doesNeedToUpdateInputs()) {
                continue;
            }
            try {
                List<Type> missing = fms.updateMockedMethods();
                List<VariableReference> refs;
                try {
                    refs = factory.satisfyParameters(test, null, missing, null,
                            st.getPosition(), 1, false, false, true);
                } catch (Exception e) {
                    // Keep the mock structurally valid even if value synthesis fails.
                    fms.fillWithNullRefs();
                    logger.debug("DMoN mock materialization fallback to null refs for {}: {}",
                            fms.getTargetClass(), e.getMessage());
                    changed = true;
                    continue;
                }
                fms.addMissingInputs(refs);
                changed = true;
            } catch (Exception e) {
                logger.debug("DMoN mock materialization failed for {}: {}",
                        fms.getTargetClass(), e.getMessage(), e);
                fms.fillWithNullRefs();
                changed = true;
            }
        }
        return changed;
    }

    private static ExecutionResult markContaminatedIfNeeded(ExecutionResult result, boolean contaminated) {
        if (result != null && contaminated) {
            result.setDmonContaminated(true);
        }
        return result;
    }

    private static Class<?> resolveOwnerClass(DmonPromotionPlan plan, ClassLoader sutLoader) {
        if (plan == null) {
            return null;
        }
        String owner = plan.getFailureSite() == null ? null : plan.getFailureSite().getOwnerClass();
        Class<?> resolved = tryLoadClass(owner, sutLoader);
        if (resolved != null) {
            return resolved;
        }
        if (plan.getOwnerToken().isPresent()) {
            resolved = tryLoadClass(plan.getOwnerToken().get(), sutLoader);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static void registerOwnerForStaticReset(DmonPromotionPlan plan) {
        if (plan == null || plan.getFailureSite() == null) {
            return;
        }
        String ownerClass = plan.getFailureSite().getOwnerClass();
        if (ownerClass == null || ownerClass.trim().isEmpty()) {
            return;
        }
        ClassReInitializer.getInstance().addInitializedClasses(Collections.singletonList(ownerClass));
    }

    private static Class<?> resolveMockType(DmonPromotionPlan plan, Class<?> ownerClass, ClassLoader sutLoader) {
        if (plan.getInferredMissingTypeName().isPresent()) {
            try {
                return Class.forName(plan.getInferredMissingTypeName().get(), false, sutLoader);
            } catch (ClassNotFoundException ignored) {
                // Fallback to field-based inference
            }
        }
        if (plan.getMemberToken().isPresent()) {
            Class<?> fromMember = tryResolveTypeFromMemberToken(plan.getMemberToken().get(), sutLoader);
            if (fromMember != null) {
                return fromMember;
            }
        }

        String fieldName = DmonInjectionDiscovery.resolveFieldName(plan);
        if (fieldName == null) {
            return null;
        }

        Field field = DmonInjectionDiscovery.findStaticField(ownerClass, fieldName);
        if (field == null) {
            field = findFieldInHierarchy(ownerClass, fieldName);
        }
        return field != null ? field.getType() : null;
    }

    private static Class<?> tryResolveTypeFromMemberToken(String memberToken, ClassLoader loader) {
        if (memberToken == null) {
            return null;
        }
        String token = memberToken.trim();
        if (token.isEmpty()) {
            return null;
        }
        int paren = token.indexOf('(');
        if (paren > 0) {
            token = token.substring(0, paren);
        }
        int lastDot = token.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String ownerTypeName = token.substring(0, lastDot);
        return tryLoadClass(ownerTypeName, loader);
    }

    private static Class<?> tryLoadClass(String className, ClassLoader loader) {
        if (className == null || className.trim().isEmpty()) {
            return null;
        }
        try {
            return Class.forName(className.trim(), false, loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Field findStaticInjectionField(Class<?> ownerClass, Class<?> mockType, String preferredField) {
        Field preferred = DmonInjectionDiscovery.findStaticField(ownerClass, preferredField);
        if (preferred != null && preferred.getType().isAssignableFrom(mockType)) {
            return preferred;
        }
        for (Class<?> c = ownerClass; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(mods) || java.lang.reflect.Modifier.isFinal(mods)) {
                    continue;
                }
                if (f.getType().isAssignableFrom(mockType)) {
                    return f;
                }
            }
        }
        return null;
    }

    private static Field findInstanceInjectionField(Class<?> ownerClass, Class<?> mockType, String preferredField) {
        Field preferred = DmonInjectionDiscovery.findInstanceField(ownerClass, preferredField);
        if (preferred != null && preferred.getType().isAssignableFrom(mockType)) {
            return preferred;
        }
        for (Class<?> c = ownerClass; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isFinal(mods)) {
                    continue;
                }
                if (f.getType().isAssignableFrom(mockType)) {
                    return f;
                }
            }
        }
        return null;
    }

    private static Statement buildStaticMethodStatement(TestCase test, Method method, VariableReference mockRef) {
        GenericMethod gm = new GenericMethod(method, method.getDeclaringClass());
        return new MethodStatement(test, gm, null, Collections.singletonList(mockRef));
    }

    private static Statement buildStaticFieldAssignment(TestCase test, Field field, VariableReference mockRef) {
        if (Modifier.isPrivate(field.getModifiers())) {
            try {
                return new PrivateFieldStatement(test, field.getDeclaringClass(), field.getName(), null, mockRef);
            } catch (NoSuchFieldException | ConstructionFailedException e) {
                throw new RuntimeException("Failed to create private static field assignment for DMoN promotion", e);
            }
        }
        GenericField gf = new GenericField(field, field.getDeclaringClass());
        FieldReference ref = new FieldReference(test, gf, (VariableReference) null);
        return new AssignmentStatement(test, ref, mockRef);
    }

    private static MethodStatement buildStaticAccessorCall(TestCase test, Method method) {
        GenericMethod gm = new GenericMethod(method, method.getDeclaringClass());
        return new MethodStatement(test, gm, null, Collections.emptyList());
    }

    private static Statement buildInstanceMethodStatement(TestCase test,
                                                          Method method,
                                                          VariableReference receiver,
                                                          VariableReference mockRef) {
        GenericMethod gm = new GenericMethod(method, method.getDeclaringClass());
        return new MethodStatement(test, gm, receiver, Collections.singletonList(mockRef));
    }

    private static Statement buildInstanceFieldAssignment(TestCase test,
                                                          Field field,
                                                          VariableReference receiver,
                                                          VariableReference mockRef) {
        if (Modifier.isPrivate(field.getModifiers())) {
            try {
                return new PrivateFieldStatement(test, field.getDeclaringClass(), field.getName(), receiver, mockRef);
            } catch (NoSuchFieldException | ConstructionFailedException e) {
                throw new RuntimeException("Failed to create private instance field assignment for DMoN promotion", e);
            }
        }
        GenericField gf = new GenericField(field, field.getDeclaringClass());
        FieldReference ref = new FieldReference(test, gf, receiver);
        return new AssignmentStatement(test, ref, mockRef);
    }

    private static ValidationStaticRestore buildValidationRestore(Class<?> ownerClass,
                                                                  Method staticTargetMethod,
                                                                  Field staticTargetField,
                                                                  Method instanceAccessor,
                                                                  Method instanceTargetMethod,
                                                                  Field instanceTargetField,
                                                                  String fieldHint) throws IllegalAccessException {
        if (staticTargetMethod != null || staticTargetField != null) {
            Field rollbackField = staticTargetField;
            if (rollbackField == null && staticTargetMethod != null) {
                rollbackField = DmonInjectionDiscovery.resolveRollbackFieldForSetter(
                        ownerClass, staticTargetMethod, fieldHint);
            }
            if (rollbackField == null) {
                return null;
            }
            rollbackField.setAccessible(true);
            Object oldValue = rollbackField.get(null);
            return new FieldValidationRestore(rollbackField, oldValue);
        }

        if (instanceAccessor == null) {
            return null;
        }
        Object receiver;
        try {
            instanceAccessor.setAccessible(true);
            receiver = instanceAccessor.invoke(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
        if (receiver == null) {
            return null;
        }
        Field rollbackField = instanceTargetField;
        if (rollbackField == null && instanceTargetMethod != null) {
            rollbackField = DmonInjectionDiscovery.resolveRollbackFieldForInstanceSetter(
                    ownerClass, instanceTargetMethod, fieldHint);
        }
        if (rollbackField == null) {
            return null;
        }
        rollbackField.setAccessible(true);
        Object oldValue = rollbackField.get(receiver);
        return new InstanceFieldValidationRestore(rollbackField, receiver, oldValue);
    }

    private static Field findFieldInHierarchy(Class<?> type, String fieldName) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException expected) {
            }
        }
        return null;
    }

    private static Method findInstanceSetter(Class<?> ownerClass, Class<?> dependencyType, String fieldNameHint) {
        if (ownerClass == null || dependencyType == null) {
            return null;
        }
        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        String normalizedFieldHint = fieldNameHint == null ? "" : fieldNameHint.toLowerCase();
        for (Method method : ownerClass.getMethods()) {
            int mods = method.getModifiers();
            if (Modifier.isStatic(mods) || !Modifier.isPublic(mods)) {
                continue;
            }
            if (method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (!param.isAssignableFrom(dependencyType) && !dependencyType.isAssignableFrom(param)) {
                continue;
            }
            String methodName = method.getName().toLowerCase();
            if (!methodName.startsWith("set")) {
                continue;
            }
            int score = 20;
            if (!normalizedFieldHint.isEmpty() && methodName.contains(normalizedFieldHint)) {
                score += 30;
            }
            if (param.equals(dependencyType)) {
                score += 5;
            }
            if (best == null || score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        return best;
    }
}
