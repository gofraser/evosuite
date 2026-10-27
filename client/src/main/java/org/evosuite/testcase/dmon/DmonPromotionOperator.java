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
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.GenerationContext;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.dmon.DmonInjectionDiscovery.InjectionTarget;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.reflection.PrivateFieldStatement;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

    /** Puts a field back to the value it had before the promotion; the receiver is null for a static field. */
    private static final class FieldValidationRestore implements ValidationStaticRestore {
        private final Field field;
        private final Object receiver;
        private final Object oldValue;

        private FieldValidationRestore(Field field, Object receiver, Object oldValue) {
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
        if (!result.hasUnconsumedDmonPromotionPlan()) {
            return result;
        }
        logger.debug("DMoN promotion: evaluate [hasPlan={}, consumed={}, contaminated={}]",
                result.getDmonPromotionPlan() != null,
                result.isDmonPromotionConsumed(),
                result.isDmonContaminated());
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
        PromotionAttempt attempt;
        try {
            attempt = tryPromoteInPlace(individual, plan, result);
        } catch (RuntimeException e) {
            // Only failures before the test case is touched can reach this handler (target
            // resolution, which is read-only reflection); everything from the backup clone
            // onwards runs inside tryPromoteInPlace's rollback boundary.
            logger.warn("DMoN promotion aborted: {}", e.toString());
            attempt = new PromotionAttempt(result, false, "UNEXPECTED_FAILURE");
        } catch (LinkageError e) {
            // Reflecting over a SUT class (getMethods, getDeclaredField, ...) links the
            // types it mentions; one that is absent from the classpath raises
            // NoClassDefFoundError here, before the test case is touched. Post-mutation
            // linkage errors are rolled back in tryPromoteInPlace. That makes this promotion impossible, nothing more:
            // left to propagate it ends the whole client run.
            logger.debug("DMoN promotion abandoned, SUT class cannot be linked: {}", e.toString());
            attempt = new PromotionAttempt(result, false, "LINKAGE_ERROR");
        }
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
        InjectionTarget target = DmonInjectionDiscovery.resolveTarget(
                ownerClass, mockType, fieldHint, Properties.DMON_ALLOW_REFLECTION_FALLBACK);
        // If no injection target found on the failure-site owner, try the class from
        // the null expression (e.g., "jaw.Main" from "jaw.Main.janelaPrincipal").
        if (target.isEmpty()) {
            String exprOwner = DmonInjectionDiscovery.resolveFieldOwnerClassName(plan);
            if (exprOwner != null) {
                Class<?> exprClass = DmonInjectionDiscovery.loadClass(exprOwner, sutLoader);
                if (exprClass != null && !exprClass.equals(ownerClass)) {
                    ownerClass = exprClass;
                    mockType = resolveMockType(plan, ownerClass, sutLoader);
                    if (mockType == null) {
                        return new PromotionAttempt(originalResult, false, "MOCK_TYPE_UNRESOLVED");
                    }
                    target = DmonInjectionDiscovery.resolveTarget(
                            ownerClass, mockType, fieldHint, Properties.DMON_ALLOW_REFLECTION_FALLBACK);
                }
            }
        }
        if (target.isEmpty()) {
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
            if (target.staticSetter != null) {
                newStatements.add(buildStaticMethodStatement(test, target.staticSetter, mockRef));
            } else if (target.staticField != null) {
                newStatements.add(buildStaticFieldAssignment(test, target.staticField, mockRef));
            } else {
                // resolveTarget leaves an accessor, and a setter or a field, together
                MethodStatement accessorStatement = buildStaticAccessorCall(test, target.instanceAccessor);
                VariableReference receiver = accessorStatement.getReturnValue();
                newStatements.add(accessorStatement);
                newStatements.add(target.instanceSetter != null
                        ? buildInstanceMethodStatement(test, target.instanceSetter, receiver, mockRef)
                        : buildInstanceFieldAssignment(test, target.instanceField, receiver, mockRef));
            }
            int insertionIndex = 1;
            for (Statement statement : newStatements) {
                test.addStatement(statement, insertionIndex++);
            }
            ValidationStaticRestore restore = buildValidationRestore(ownerClass, target, fieldHint);
            return validateOrRollback(individual, backup, originalResult, restore, plan);
        } catch (ConstructionFailedException | RuntimeException e) {
            // Covers both a failed mock/statement construction and a statement loop
            // that added some but not all of newStatements before throwing: either
            // way the live TestCase was mutated in place, and rollbackMutatedTestCase
            // discards the whole object (partial statements included) in favour of
            // the pre-mutation backup, so no separate partial-add cleanup is needed.
            logger.debug("DMoN promotion failed: {}", e.getMessage(), e);
            return new PromotionAttempt(
                    rollbackMutatedTestCase(individual, backup, originalResult), false, "CONSTRUCTION_FAILED");
        } catch (IllegalAccessException e) {
            logger.debug("DMoN validation restore snapshot failed: {}", e.getMessage(), e);
            return new PromotionAttempt(
                    rollbackMutatedTestCase(individual, backup, originalResult), false, "VALIDATION_SNAPSHOT_FAILED");
        } catch (LinkageError e) {
            // Includes ExceptionInInitializerError and NoClassDefFoundError raised by a SUT
            // static initializer while the validation snapshot reads a field. The test case
            // is already mutated here, so it must be rolled back like any other failure.
            logger.debug("DMoN promotion abandoned, SUT class cannot be linked: {}", e.toString());
            return new PromotionAttempt(
                    rollbackMutatedTestCase(individual, backup, originalResult), false, "LINKAGE_ERROR");
        }
    }

    /**
     * Undo an in-place mutation of {@code individual}'s live {@link TestCase} after a
     * promotion attempt failed partway through. {@code factory.addFunctionalMock} and
     * {@code test.addStatement} both mutate the chromosome's TestCase object directly
     * (there is no copy-on-write), and {@code originalResult} holds that very same
     * TestCase reference rather than a snapshot, so a failure that merely returns
     * {@code originalResult} silently leaves the mutated test in the chromosome while
     * reporting no change -- exactly the stale-fitness hazard this method exists to
     * avoid.
     *
     * <p>This mirrors the restore idiom in {@link #validateOrRollback}'s own failure
     * path: swap the chromosome back onto the pre-mutation {@code backup}, rebuild an
     * ExecutionResult around it, mark it so this plan is not retried, and tell the GA
     * the chromosome is unchanged (because, after the swap, it truly is -- it is back
     * to exactly what {@code originalResult} was last evaluated against). These are
     * construction-time failures (no injection target, a failed mock/statement build,
     * or a failed validation-restore snapshot), not runtime assertion failures, so the
     * same owner/mock/field resolution will fail again on retry; marking the plan
     * consumed here prevents the operator from repeatedly re-attempting a plan that
     * cannot work, the same reasoning validateOrRollback already applies to its own
     * failed-validation case.
     */
    private static ExecutionResult rollbackMutatedTestCase(TestChromosome individual,
                                                            TestCase backup,
                                                            ExecutionResult originalResult) {
        individual.setTestCase(backup);
        ExecutionResult restored = originalResult.clone();
        restored.setTest(individual.getTestCase());
        restored.markDmonPromotionConsumed();
        individual.setLastExecutionResult(restored);
        individual.setChanged(false);
        return restored;
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
        boolean keptUnchanged = false;
        boolean keptInconclusive = false;
        try {
            Properties.DMON_ENABLED = false;
            validated = TestCaseExecutor.runTest(individual.getTestCase());
            int insertedStatements = individual.getTestCase().size() - backup.size();
            boolean progress = isValidationProgressAccepted(validated, plan);
            keptInconclusive = failsAtAssistedStatement(validated, originalResult, insertedStatements);
            progressAccepted = progress && !keptInconclusive
                    && !failsEarlierThanOriginal(validated, originalResult, insertedStatements);
            keptUnchanged = !keptInconclusive && !progress
                    && failsExactlyAsOriginal(validated, originalResult, insertedStatements);
            if (keptUnchanged || keptInconclusive) {
                // as without validation: keep the promotion and let the GA re-evaluate it
                individual.setChanged(true);
            } else if (progressAccepted) {
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
        if (keptUnchanged) {
            return new PromotionAttempt(markContaminatedIfNeeded(originalResult, !restoreOk), true,
                    restoreOk ? "VALIDATION_UNCHANGED_KEPT" : "VALIDATION_UNCHANGED_KEPT_WITH_RESTORE_WARNING");
        }
        if (keptInconclusive) {
            return new PromotionAttempt(markContaminatedIfNeeded(originalResult, !restoreOk), true,
                    restoreOk ? "VALIDATION_INCONCLUSIVE_KEPT" : "VALIDATION_INCONCLUSIVE_KEPT_WITH_RESTORE_WARNING");
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

    /**
     * The validation re-runs the test with DMoN switched off, and with it the ephemeral assist
     * that repaired other nulls of the original execution while it ran. A statement the assist
     * got the original past throws in the re-run, before the null the promotion is about, so the
     * re-run says nothing about the promotion: it neither failed earlier because of the
     * promotion nor failed at the null site. Positions are compared after shifting the
     * original ones by the number of statements the promotion inserted.
     */
    private static boolean failsAtAssistedStatement(ExecutionResult validated, ExecutionResult original,
                                                    int insertedStatements) {
        Integer validatedPosition = validated == null ? null : validated.getFirstPositionOfThrownException();
        if (validatedPosition == null || original == null) {
            return false;
        }
        return original.getDmonAssistedPositions().contains(validatedPosition - insertedStatements);
    }

    /**
     * A promotion that makes the test throw at an earlier statement than before has not
     * moved past the null: it cut off the part of the test that already ran, and with it the
     * coverage the original execution had reached. Positions are compared after shifting the
     * original one by the number of statements the promotion inserted.
     */
    private static boolean failsEarlierThanOriginal(ExecutionResult validated, ExecutionResult original,
                                                    int insertedStatements) {
        Integer validatedPosition = validated == null ? null : validated.getFirstPositionOfThrownException();
        Integer originalPosition = original == null ? null : original.getFirstPositionOfThrownException();
        if (validatedPosition == null || originalPosition == null) {
            return false;
        }
        return validatedPosition < originalPosition + insertedStatements;
    }

    /**
     * A promotion whose re-run still hits the null and throws the same exception at the same
     * statement as the original execution (after shifting by the inserted statements) changed
     * nothing up to the failure: the null does not come from state the injection reaches, but
     * typically from a null argument or a null return of a mocked call. Rolling such a promotion
     * back gains nothing and discards its lenient mock of the type the null needed, which the
     * search reuses where the null does come from, for example as the constructor argument.
     * Such a promotion is kept, as it was before promotions were validated.
     */
    private static boolean failsExactlyAsOriginal(ExecutionResult validated, ExecutionResult original,
                                                  int insertedStatements) {
        Integer validatedPosition = validated == null ? null : validated.getFirstPositionOfThrownException();
        Integer originalPosition = original == null ? null : original.getFirstPositionOfThrownException();
        if (validatedPosition == null || originalPosition == null
                || validatedPosition != originalPosition + insertedStatements) {
            return false;
        }
        Throwable validatedException = validated.getExceptionThrownAtPosition(validatedPosition);
        Throwable originalException = original.getExceptionThrownAtPosition(originalPosition);
        return validatedException != null && originalException != null
                && validatedException.getClass().equals(originalException.getClass());
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
                fms.completeStubs(missing -> factory.satisfyParameters(test, null, missing, null,
                        st.getPosition(), 1, false, false, true));
            } catch (Exception e) {
                // The mock stays structurally valid: the calls without a value are answered
                // with null.
                logger.debug("DMoN mock materialization fallback to null refs for {}: {}",
                        fms.getTargetClass(), e.getMessage(), e);
            }
            changed = true;
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
        Class<?> resolved = DmonInjectionDiscovery.loadClass(owner, sutLoader);
        if (resolved != null) {
            return resolved;
        }
        if (plan.getOwnerToken().isPresent()) {
            resolved = DmonInjectionDiscovery.loadClass(plan.getOwnerToken().get(), sutLoader);
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

        Field field = DmonInjectionDiscovery.findField(ownerClass, fieldName, true);
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
        return DmonInjectionDiscovery.loadClass(ownerTypeName, loader);
    }

    private static Statement buildStaticMethodStatement(TestCase test, Method method, VariableReference mockRef) {
        GenericMethod gm = new GenericMethod(method, method.getDeclaringClass());
        return new MethodStatement(test, gm, null, Collections.singletonList(mockRef));
    }

    private static Statement buildStaticFieldAssignment(TestCase test, Field field, VariableReference mockRef) {
        if (!TestUsageChecker.isCallableFromTestPackage(field)) {
            try {
                return new PrivateFieldStatement(test, field.getDeclaringClass(), field.getName(),
                        new ConstantValue(test, field.getDeclaringClass()), mockRef);
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
        if (!TestUsageChecker.isCallableFromTestPackage(field)) {
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
                                                                  InjectionTarget target,
                                                                  String fieldHint) throws IllegalAccessException {
        if (target.staticSetter != null || target.staticField != null) {
            Field rollbackField = target.staticField;
            if (rollbackField == null) {
                rollbackField = DmonInjectionDiscovery.resolveRollbackField(
                        ownerClass, target.staticSetter, fieldHint, true);
            }
            if (rollbackField == null) {
                return null;
            }
            if (!trySetAccessible(rollbackField)) {
                return null;
            }
            Object oldValue = rollbackField.get(null);
            return new FieldValidationRestore(rollbackField, null, oldValue);
        }

        Object receiver;
        try {
            if (!trySetAccessible(target.instanceAccessor)) {
                return null;
            }
            receiver = target.instanceAccessor.invoke(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
        if (receiver == null) {
            return null;
        }
        Field rollbackField = target.instanceField;
        if (rollbackField == null) {
            rollbackField = DmonInjectionDiscovery.resolveRollbackField(
                    ownerClass, target.instanceSetter, fieldHint, false);
        }
        if (rollbackField == null) {
            return null;
        }
        if (!trySetAccessible(rollbackField)) {
            return null;
        }
        Object oldValue = rollbackField.get(receiver);
        return new FieldValidationRestore(rollbackField, receiver, oldValue);
    }

    private static Field findFieldInHierarchy(Class<?> type, String fieldName) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                if (trySetAccessible(f)) {
                    return f;
                }
            } catch (NoSuchFieldException expected) {
            }
        }
        return null;
    }

    /**
     * Make a member accessible, reporting failure instead of throwing. On JDK 9+ a
     * member of a JDK class in a package its module does not open throws
     * InaccessibleObjectException (a RuntimeException, unavailable at this module's
     * Java 8 source level), and a SecurityManager may throw SecurityException. Such a
     * member is not a usable injection or rollback target.
     */
    private static boolean trySetAccessible(java.lang.reflect.AccessibleObject member) {
        try {
            member.setAccessible(true);
            return true;
        } catch (RuntimeException e) {
            logger.debug("DMoN cannot make {} accessible: {}", member, e.toString());
            return false;
        }
    }
}
