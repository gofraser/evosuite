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
import org.evosuite.runtime.UnmockableTypes;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * The transient part of DMoN during one test execution: when a statement fails with a
 * NullPointerException that DMoN can attribute to a missing dependency, this injects a
 * mock for it, re-executes the statement, and records every write it made so that
 * {@link #rollback(ExecutionResult)} can undo them once the execution ends.
 * <p>
 * One instance serves one execution; the rollback log is not shared.
 */
public class DmonEphemeralAssist {

    private static final Logger logger = LoggerFactory.getLogger(DmonEphemeralAssist.class);

    private final Scope scope;

    private final Deque<DmonRollbackAction> dmonRollbackLog = new ArrayDeque<>();

    public DmonEphemeralAssist(Scope scope) {
        this.scope = scope;
    }

    private interface DmonRollbackAction {

        void rollback() throws Exception;

        String describe();
    }

    private static final class DmonStaticWrite implements DmonRollbackAction {
        private final Field field;
        private final Object oldValue;

        private DmonStaticWrite(Field field, Object oldValue) {
            this.field = field;
            this.oldValue = oldValue;
        }

        @Override
        public void rollback() throws IllegalAccessException {
            field.setAccessible(true);
            field.set(null, oldValue);
        }

        @Override
        public String describe() {
            return field.getDeclaringClass().getName() + "." + field.getName();
        }
    }

    private static final class DmonSetterRollback implements DmonRollbackAction {
        private final Method setter;
        private final Object oldValue;

        private DmonSetterRollback(Method setter, Object oldValue) {
            this.setter = setter;
            this.oldValue = oldValue;
        }

        @Override
        public void rollback() throws Exception {
            setter.setAccessible(true);
            setter.invoke(null, oldValue);
        }

        @Override
        public String describe() {
            return setter.getDeclaringClass().getName() + "." + setter.getName() + "(...)";
        }
    }

    private static final class DmonInstanceWrite implements DmonRollbackAction {
        private final Field field;
        private final Object target;
        private final Object oldValue;

        private DmonInstanceWrite(Field field, Object target, Object oldValue) {
            this.field = field;
            this.target = target;
            this.oldValue = oldValue;
        }

        @Override
        public void rollback() throws IllegalAccessException {
            field.setAccessible(true);
            field.set(target, oldValue);
        }

        @Override
        public String describe() {
            return field.getDeclaringClass().getName() + "." + field.getName() + " (instance)";
        }
    }

    private static final class DmonInstanceSetterRollback implements DmonRollbackAction {
        private final Method setter;
        private final Object receiver;
        private final Object oldValue;

        private DmonInstanceSetterRollback(Method setter, Object receiver, Object oldValue) {
            this.setter = setter;
            this.receiver = receiver;
            this.oldValue = oldValue;
        }

        @Override
        public void rollback() throws Exception {
            setter.setAccessible(true);
            setter.invoke(receiver, oldValue);
        }

        @Override
        public String describe() {
            return setter.getDeclaringClass().getName() + "." + setter.getName() + "(...) (instance)";
        }
    }


    /**
     * Remembers the current value of the field a field assignment is about to overwrite, so
     * that the write is undone by {@link #rollback(ExecutionResult)}.
     */
    public void captureFieldAssignment(Statement statement) {
        if (!(statement instanceof AssignmentStatement)) {
            return;
        }
        VariableReference target = statement.getReturnValue();
        if (!(target instanceof FieldReference)) {
            return;
        }
        FieldReference fieldRef = (FieldReference) target;
        Field field = fieldRef.getField().getField();
        if (Modifier.isFinal(field.getModifiers())) {
            return;
        }
        try {
            field.setAccessible(true);
            if (fieldRef.getField().isStatic()) {
                Object oldValue = field.get(null);
                dmonRollbackLog.push(new DmonStaticWrite(field, oldValue));
                return;
            }
            VariableReference sourceRef = fieldRef.getSource();
            if (sourceRef == null) {
                return;
            }
            Object receiver = sourceRef.getObject(scope);
            if (receiver == null) {
                return;
            }
            Object oldValue = field.get(receiver);
            dmonRollbackLog.push(new DmonInstanceWrite(field, receiver, oldValue));
        } catch (Throwable t) {
            logger.debug("Could not snapshot field assignment for rollback: {}", t.getMessage());
        }
    }

    /**
     * Retries a statement that failed with a NullPointerException after injecting mocks for
     * the dependencies DMoN blames, and records the failure as a promotion candidate.
     *
     * @return null if a retry succeeded, otherwise the exception the statement ended with
     */
    public Throwable assist(ExecutionResult result,
                     Statement statement,
                     Throwable throwable,
                     PrintStream out)
            throws InvocationTargetException, IllegalAccessException, InstantiationException {
        if (!Properties.DMON_ENABLED || Properties.NO_RUNTIME_DEPENDENCY) {
            return throwable;
        }
        if (!(statement instanceof ConstructorStatement) && !(statement instanceof MethodStatement)) {
            return throwable;
        }

        Throwable lastThrown = throwable;
        NullPointerException currentNpe = extractNpe(throwable);
        if (currentNpe == null) {
            return throwable;
        }
        int maxRetries = Math.max(1, Properties.DMON_MAX_EPHEMERAL_RETRIES);

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            maybeRecordDmonCandidate(result, statement, currentNpe);
            Optional<DmonPromotionPlan> analyzedPlan = analyzeDmonFailure(statement, currentNpe);
            if (!analyzedPlan.isPresent()) {
                // For non-target-class constructors/methods, try relaxed analysis for
                // ephemeral assist only (not recorded for promotion).
                analyzedPlan = analyzeDmonEphemeralAssist(statement, currentNpe);
            }
            if (!analyzedPlan.isPresent()) {
                logger.debug("DMoN runtime: cascading assist stopped [attempt={}, reason=NO_PLAN]",
                        attempt + 1);
                return lastThrown;
            }
            boolean injected;
            try {
                injected = tryEphemeralInjection(statement, analyzedPlan.get());
            } catch (LinkageError e) {
                // Reflection over a SUT class links the types it mentions; a missing one
                // only makes this assist impossible.
                logger.debug("DMoN runtime: ephemeral injection abandoned, SUT class cannot be linked: {}",
                        e.toString());
                injected = false;
            }
            if (!injected) {
                logger.debug("DMoN runtime: cascading assist stopped [attempt={}, reason=INJECTION_FAILED]",
                        attempt + 1);
                return lastThrown;
            }
            logger.debug("DMoN runtime: cascading assist applied [attempt={}, maxRetries={}]",
                    attempt + 1, maxRetries);
            Throwable retried = statement.execute(scope, out);
            if (retried == null) {
                result.addDmonAssistedPosition(statement.getPosition());
                return null;
            }
            lastThrown = retried;
            currentNpe = extractNpe(retried);
            if (currentNpe == null) {
                return retried;
            }
        }
        logger.debug("DMoN runtime: cascading assist exhausted retry budget [maxRetries={}]", maxRetries);
        return lastThrown;
    }

    /**
     * Undoes the injected writes in reverse order. A write that cannot be undone marks the
     * result as contaminated.
     *
     * @return false if any write could not be undone
     */
    public boolean rollback(ExecutionResult result) {
        boolean ok = true;
        while (!dmonRollbackLog.isEmpty()) {
            DmonRollbackAction write = dmonRollbackLog.pop();
            try {
                write.rollback();
            } catch (Throwable t) {
                ok = false;
                logDmonRollbackFailure(write.describe(), t);
            }
        }
        if (!ok && result != null) {
            result.setDmonContaminated(true);
        }
        return ok;
    }

    /**
     * Dispatches to the DmonCoordinator's constructor- or method-failure analysis, whichever
     * matches the statement kind. DMoN's repair is driven by the NPE's stack trace, not by the
     * kind of statement that triggered it — see DmonNpeAnalyzer.
     */
    private static Optional<DmonPromotionPlan> analyzeDmonFailure(Statement statement, Throwable throwable) {
        if (statement instanceof ConstructorStatement) {
            return DmonCoordinator.getInstance()
                    .analyzeConstructorFailure((ConstructorStatement) statement, throwable);
        }
        if (statement instanceof MethodStatement) {
            return DmonCoordinator.getInstance()
                    .analyzeMethodFailure((MethodStatement) statement, throwable);
        }
        return Optional.empty();
    }

    /**
     * Dispatches to the DmonCoordinator's relaxed (no target-class restriction) analysis used
     * for cascading ephemeral assist. See {@link #analyzeDmonFailure(Statement, Throwable)}.
     */
    private static Optional<DmonPromotionPlan> analyzeDmonEphemeralAssist(Statement statement, Throwable throwable) {
        if (statement instanceof ConstructorStatement) {
            return DmonCoordinator.getInstance()
                    .analyzeForEphemeralAssist((ConstructorStatement) statement, throwable);
        }
        if (statement instanceof MethodStatement) {
            return DmonCoordinator.getInstance()
                    .analyzeForEphemeralAssist((MethodStatement) statement, throwable);
        }
        return Optional.empty();
    }

    private static void maybeRecordDmonCandidate(ExecutionResult result, Statement statement, Throwable throwable) {
        if (!Properties.DMON_ENABLED || Properties.NO_RUNTIME_DEPENDENCY) {
            return;
        }
        if (!(statement instanceof ConstructorStatement) && !(statement instanceof MethodStatement)) {
            return;
        }
        if (!(throwable instanceof NullPointerException)) {
            return;
        }
        if (result.getDmonPromotionPlan() != null) {
            logger.trace("DMoN runtime: candidate already recorded for this execution");
            return;
        }
        Optional<DmonPromotionPlan> plan = analyzeDmonFailure(statement, throwable);
        if (plan.isPresent()) {
            result.setDmonPromotionPlan(plan.get());
            DmonFailureSite fs = plan.get().getFailureSite();
            logger.debug("DMoN runtime: candidate recorded [owner={}, method={}, line={}, member={}, inferredType={}]",
                    fs.getOwnerClass(), fs.getMethodName(), fs.getLineNumber(),
                    plan.get().getMemberToken().orElse(""),
                    plan.get().getInferredMissingTypeName().orElse(""));
        } else {
            logger.trace("DMoN runtime: analyzer returned no candidate");
        }
    }

    private static NullPointerException extractNpe(Throwable throwable) {
        if (throwable instanceof NullPointerException) {
            return (NullPointerException) throwable;
        }
        if (throwable instanceof CodeUnderTestException && throwable.getCause() instanceof NullPointerException) {
            return (NullPointerException) throwable.getCause();
        }
        return null;
    }

    private boolean tryEphemeralInjection(Statement statement, DmonPromotionPlan plan) {
        Class<?> ownerClass = resolveOwnerClassForEphemeral(statement, plan);
        if (ownerClass == null) {
            return false;
        }

        String fieldName = DmonInjectionDiscovery.resolveFieldName(plan);
        Field field = DmonInjectionDiscovery.findStaticField(ownerClass, fieldName);
        Field instanceField = field == null ? DmonInjectionDiscovery.findInstanceField(ownerClass, fieldName) : null;

        // If the field is not found on the failure-site owner, try the class extracted
        // from the null expression (e.g., "jaw.Main" from "jaw.Main.janelaPrincipal").
        if (field == null && instanceField == null) {
            String exprOwner = DmonInjectionDiscovery.resolveFieldOwnerClassName(plan);
            if (exprOwner != null) {
                ClassLoader sutLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
                try {
                    Class<?> exprClass = Class.forName(exprOwner, false, sutLoader);
                    if (!exprClass.equals(ownerClass)) {
                        field = DmonInjectionDiscovery.findStaticField(exprClass, fieldName);
                        if (field != null) {
                            ownerClass = exprClass;
                        } else {
                            instanceField = DmonInjectionDiscovery.findInstanceField(exprClass, fieldName);
                            if (instanceField != null) {
                                ownerClass = exprClass;
                            }
                        }
                    }
                } catch (ClassNotFoundException expected) {
                }
            }
        }

        Class<?> dependencyType = null;
        if (field != null) {
            dependencyType = field.getType();
        } else if (instanceField != null) {
            dependencyType = instanceField.getType();
        } else if (plan.getInferredMissingTypeName().isPresent()) {
            try {
                dependencyType = Class.forName(plan.getInferredMissingTypeName().get(),
                        false, ownerClass.getClassLoader());
            } catch (ClassNotFoundException expected) {
                dependencyType = null;
            }
        }
        if (dependencyType == null || dependencyType.isPrimitive()) {
            return false;
        }

        if (tryEphemeralSetterInjection(ownerClass, dependencyType, fieldName)) {
            return true;
        }
        if (tryEphemeralFieldInjection(field, dependencyType)) {
            return true;
        }
        return tryEphemeralInstanceInjection(ownerClass, instanceField, dependencyType, fieldName);
    }

    private static Class<?> resolveOwnerClassForEphemeral(Statement statement, DmonPromotionPlan plan) {
        String ownerName = plan.getFailureSite() == null ? null : plan.getFailureSite().getOwnerClass();
        ClassLoader sutLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        if (ownerName != null && !ownerName.trim().isEmpty()) {
            try {
                return Class.forName(ownerName, false, sutLoader);
            } catch (ClassNotFoundException expected) {
            }
        }
        if (statement instanceof ConstructorStatement) {
            return ((ConstructorStatement) statement).getConstructor().getConstructor().getDeclaringClass();
        }
        if (statement instanceof MethodStatement) {
            return ((MethodStatement) statement).getMethod().getMethod().getDeclaringClass();
        }
        return null;
    }

    private boolean tryEphemeralSetterInjection(Class<?> ownerClass, Class<?> dependencyType, String fieldName) {
        Method setter = DmonInjectionDiscovery.findStaticSetter(ownerClass, dependencyType, fieldName);
        if (setter == null) {
            return false;
        }
        Field rollbackField = DmonInjectionDiscovery.resolveRollbackFieldForSetter(ownerClass, setter, fieldName);
        if (rollbackField == null) {
            return false;
        }
        try {
            rollbackField.setAccessible(true);
            Object oldValue = rollbackField.get(null);
            Class<?> setterType = setter.getParameterTypes()[0];
            Object mock = createEphemeralMock(setterType);
            setter.setAccessible(true);
            setter.invoke(null, mock);
            dmonRollbackLog.push(new DmonSetterRollback(setter, oldValue));
            return true;
        } catch (Throwable t) {
            logger.debug("DMoN ephemeral setter injection failed: {}", t.getMessage(), t);
            return false;
        }
    }

    private boolean tryEphemeralInstanceInjection(Class<?> ownerClass,
                                                  Field field,
                                                  Class<?> dependencyType,
                                                  String fieldName) {
        if (ownerClass == null || dependencyType == null || dependencyType.isPrimitive()) {
            return false;
        }
        Method accessor = DmonInjectionDiscovery.findStaticInstanceAccessor(ownerClass);
        if (accessor == null) {
            return false;
        }
        try {
            accessor.setAccessible(true);
            Object receiver = accessor.invoke(null);
            if (receiver == null) {
                return false;
            }
            if (tryEphemeralInstanceSetterInjection(ownerClass, receiver, dependencyType, fieldName)) {
                return true;
            }
            return tryEphemeralInstanceFieldInjection(ownerClass, receiver, field, dependencyType, fieldName);
        } catch (Throwable t) {
            logger.debug("DMoN ephemeral instance accessor injection failed: {}", t.getMessage(), t);
            return false;
        }
    }

    private boolean tryEphemeralInstanceSetterInjection(Class<?> ownerClass,
                                                        Object receiver,
                                                        Class<?> dependencyType,
                                                        String fieldName) {
        Method setter = DmonInjectionDiscovery.findInstanceSetter(ownerClass, dependencyType, fieldName);
        if (setter == null) {
            return false;
        }
        Field rollbackField = DmonInjectionDiscovery
                .resolveRollbackFieldForInstanceSetter(ownerClass, setter, fieldName);
        if (rollbackField == null) {
            return false;
        }
        try {
            rollbackField.setAccessible(true);
            Object oldValue = rollbackField.get(receiver);
            Class<?> setterType = setter.getParameterTypes()[0];
            Object mock = createEphemeralMock(setterType);
            setter.setAccessible(true);
            setter.invoke(receiver, mock);
            dmonRollbackLog.push(new DmonInstanceSetterRollback(setter, receiver, oldValue));
            return true;
        } catch (Throwable t) {
            logger.debug("DMoN ephemeral instance setter injection failed: {}", t.getMessage(), t);
            return false;
        }
    }

    private boolean tryEphemeralInstanceFieldInjection(Class<?> ownerClass,
                                                       Object receiver,
                                                       Field field,
                                                       Class<?> dependencyType,
                                                       String fieldName) {
        Field targetField = field != null ? field : DmonInjectionDiscovery.findInstanceField(ownerClass, fieldName);
        if (targetField == null) {
            return false;
        }
        try {
            targetField.setAccessible(true);
            Object oldValue = targetField.get(receiver);
            Object mock = createEphemeralMock(dependencyType);
            targetField.set(receiver, mock);
            dmonRollbackLog.push(new DmonInstanceWrite(targetField, receiver, oldValue));
            return true;
        } catch (Throwable t) {
            logger.debug("DMoN ephemeral instance field injection failed: {}", t.getMessage(), t);
            return false;
        }
    }

    private boolean tryEphemeralFieldInjection(Field field, Class<?> dependencyType) {
        if (field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            Object oldValue = field.get(null);
            Object mock = createEphemeralMock(dependencyType);
            field.set(null, mock);
            dmonRollbackLog.push(new DmonStaticWrite(field, oldValue));
            return true;
        } catch (Throwable t) {
            logger.debug("DMoN ephemeral field injection failed: {}", t.getMessage(), t);
            return false;
        }
    }

    /** Number of rollback failures per site that are reported at WARN before demoting to DEBUG. */
    static final int DMON_ROLLBACK_WARNINGS_PER_SITE = 3;

    private static final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.atomic.AtomicInteger>
            DMON_ROLLBACK_FAILURES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A rollback that keeps failing at one site fails on every execution of every test that
     * injects there (hundreds of thousands of times in a run), so only the first few failures per
     * site are warnings; later ones are counted and logged at DEBUG.
     */
    static boolean shouldWarnAboutDmonRollbackFailure(String site) {
        return DMON_ROLLBACK_FAILURES.computeIfAbsent(site,
                k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet()
                <= DMON_ROLLBACK_WARNINGS_PER_SITE;
    }

    static void resetDmonRollbackFailureCounts() {
        DMON_ROLLBACK_FAILURES.clear();
    }

    private static void logDmonRollbackFailure(String site, Throwable t) {
        if (shouldWarnAboutDmonRollbackFailure(site)) {
            logger.warn("DMoN rollback failed for {}: {}{}", site, describeFailure(t),
                    DMON_ROLLBACK_FAILURES.get(site).get() == DMON_ROLLBACK_WARNINGS_PER_SITE
                            ? " (further failures at this site are logged at DEBUG)" : "");
        } else if (logger.isDebugEnabled()) {
            logger.debug("DMoN rollback failed for {}: {}", site, describeFailure(t));
        }
    }

    private static String describeFailure(Throwable t) {
        return t.getClass().getName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    private static Object createEphemeralMock(Class<?> type) {
        if (UnmockableTypes.isUnmockable(type)) {
            // every injection site treats an exception as "cannot inject"
            throw new IllegalArgumentException("Cannot create an ephemeral mock of " + type.getName());
        }
        return Mockito.mock(type, invocation -> createEphemeralReturnValue(invocation.getMethod().getReturnType()));
    }

    private static Object createEphemeralReturnValue(Class<?> returnType) {
        return createEphemeralReturnValue(returnType, 0);
    }

    private static Object createEphemeralReturnValue(Class<?> returnType, int chain) {
        if (returnType == null || returnType.equals(Void.TYPE)) {
            return null;
        }
        if (returnType.equals(String.class)
                || returnType.equals(Object.class)
                || returnType.equals(CharSequence.class)) {
            return "";
        }
        if (returnType.isPrimitive()) {
            return defaultPrimitiveValue(returnType);
        }
        if (returnType.isArray()) {
            return Array.newInstance(returnType.getComponentType(), 0);
        }
        if (returnType.isEnum()) {
            Object[] values = returnType.getEnumConstants();
            return values != null && values.length > 0 ? values[0] : null;
        }
        if (Modifier.isFinal(returnType.getModifiers())
                && returnType.getName().startsWith("java.lang.")) {
            return null;
        }
        if (UnmockableTypes.isUnmockable(returnType)) {
            return null;
        }
        // An accessor like getParent() hands out a fresh mock on every call, so bound how many
        // mocks may hang off one another or a loop up that chain never ends.
        if (chain >= org.evosuite.runtime.LenientMockAnswer.MAX_CHAIN) {
            return null;
        }
        try {
            return Mockito.mock(returnType, invocation ->
                    createEphemeralReturnValue(invocation.getMethod().getReturnType(), chain + 1));
        } catch (Throwable expected) {
            return null;
        }
    }

    private static Object defaultPrimitiveValue(Class<?> type) {
        if (type.equals(Boolean.TYPE)) {
            return false;
        }
        if (type.equals(Character.TYPE)) {
            return '\0';
        }
        if (type.equals(Byte.TYPE)) {
            return (byte) 0;
        }
        if (type.equals(Short.TYPE)) {
            return (short) 0;
        }
        if (type.equals(Integer.TYPE)) {
            return 0;
        }
        if (type.equals(Long.TYPE)) {
            return 0L;
        }
        if (type.equals(Float.TYPE)) {
            return 0.0f;
        }
        if (type.equals(Double.TYPE)) {
            return 0.0d;
        }
        return null;
    }
}
