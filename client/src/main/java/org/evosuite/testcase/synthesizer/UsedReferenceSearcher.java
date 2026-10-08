package org.evosuite.testcase.synthesizer;

import org.evosuite.TestGenerationContext;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Searches a test for the variables that read or write a given field of a target object.
 */
public class UsedReferenceSearcher {

    /**
     * Whether the whole-class EvoObj synthesis attempt currently in progress has exceeded its
     * wall-clock budget (see {@code Properties#OBJECT_RULE_SYNTHESIS_TIMEOUT}). Checked in the
     * loops below so a pathological field/setter search does not keep iterating the test once the
     * attempt has already been given up on.
     */
    private static boolean synthesisBudgetExceeded() {
        return TestGenerationContext.getInstance().getObjectRuleRuntime().synthesisAttemptTimedOut();
    }

    /**
     * Finds a variable in the test that reads the given field of the target object.
     *
     * @param test the test to search
     * @param field the field to be read
     * @param targetObject the object owning the field
     * @return a reference reading the field, a fallback writing reference, or null if none is found
     */
    public VariableReference searchRelevantFieldReadingReferenceInTest(
            TestCase test, Field field, VariableReference targetObject) {
        List<VariableReference> relevantRefs = new ArrayList<VariableReference>();
        List<VariableReference> backuprelevantRefs = new ArrayList<>();

        if (targetObject != null) {
            for (int i = 0; i < test.size(); i++) {
                if (synthesisBudgetExceeded()) {
                    break;
                }
                Statement stat = test.getStatement(i);
                if (stat instanceof MethodStatement) {
                    MethodStatement methodStat = (MethodStatement) stat;
                    VariableReference ref = methodStat.getCallee();
                    if (ConstructionReferenceUtil.sameReference(test, ref, targetObject)) {
                        boolean isValidGetter =
                                DataDependencyUtil.isFieldGetter(
                                        methodStat.getMethod().getMethod(), field);
                        if (isValidGetter) {
                            relevantRefs.add(methodStat.getReturnValue());
                        } else {
                            VariableReference usedFieldInTest =
                                    searchRelevantFieldWritingReferenceInTest(
                                            test, field, targetObject);
                            backuprelevantRefs.add(usedFieldInTest);
                        }
                    }
                } else if (stat instanceof ConstructorStatement) {
                    ConstructorStatement constructorStat = (ConstructorStatement) stat;
                    if (ConstructionReferenceUtil.sameReference(test, constructorStat.getReturnValue(), targetObject)) {
                        List<VariableReference> params = constructorStat.getParameterReferences();
                        String className = constructorStat.getDeclaringClassName();
                        String methodName =
                                constructorStat.getMethodName() + constructorStat.getDescriptor();
                        if (!params.isEmpty()) {
                            relevantRefs.addAll(
                                    relevantSetterArguments(params, className, methodName, field));
                        }
                    }
                }
            }
        }

        // FIXME, ziheng: we need to provide some priority for those parameters here.
        if (relevantRefs.isEmpty()) {
            if (backuprelevantRefs.isEmpty()) {
                return null;
            } else {
                return Randomness.choice(backuprelevantRefs);
            }
        }

        VariableReference ref = Randomness.choice(relevantRefs);
        return ref;
    }

    /**
     * Given a field, finds which method call or constructor sets the field.
     *
     * <p>The cascade call is checked to find such a method. The more controllable a variable is,
     * the more likely it is returned.
     *
     * @param test the test to search
     * @param field the field to be written
     * @param targetObject the object owning the field
     * @return a reference whose value is written to the field, or null if none is found
     */
    public VariableReference searchRelevantFieldWritingReferenceInTest(
            TestCase test, Field field, VariableReference targetObject) {

        List<VariableReference> relevantRefs = new ArrayList<VariableReference>();

        Map<VariableReference, Integer> actionPositions = new IdentityHashMap<>();

        if (targetObject != null) {

            // check the variables passed as parameters to the constructor of targetObject, which
            // are data-flow relevant to writing the field

            int pos = ConstructionReferenceUtil.definitionPosition(test, targetObject);
            if (pos < 0) {
                return null;
            }
            Statement s = test.getStatement(pos);
            if (s instanceof NullStatement) {
                TestFactory testFactory = TestFactory.getInstance();
                VariableReference replacement = testFactory.replaceNullStatement(test, s);
                if (replacement != null) {
                    // The null is gone and its uses now point at the replacement, which may sit
                    // after helper statements rather than at the null's old position.
                    targetObject = replacement;
                    pos = replacement.getStPosition();
                    s = test.getStatement(pos);
                }
            }

            if (s instanceof ConstructorStatement) {
                ConstructorStatement constructorStat = (ConstructorStatement) s;
                List<VariableReference> params = constructorStat.getParameterReferences();
                String className = constructorStat.getDeclaringClassName();
                String methodName =
                        constructorStat.getMethodName() + constructorStat.getDescriptor();
                if (!params.isEmpty()) {
                    List<VariableReference> paramRefs =
                            relevantSetterArguments(params, className, methodName, field);
                    relevantRefs.addAll(paramRefs);

                    for (VariableReference paramRef : paramRefs) {
                        actionPositions.put(paramRef, pos);
                    }
                }
            }

            // check the variables passed as parameters to the method invocation from targetObject,
            // which are data-flow relevant to writing the field

            for (int i = 0; i < test.size(); i++) {
                if (synthesisBudgetExceeded()) {
                    break;
                }
                Statement stat = test.getStatement(i);
                if (stat instanceof MethodStatement) {
                    MethodStatement methodStat = (MethodStatement) stat;
                    VariableReference ref = methodStat.getCallee();
                    if (ConstructionReferenceUtil.sameReference(test, ref, targetObject)) {
                        List<VariableReference> params = methodStat.getParameterReferences();
                        String className = methodStat.getDeclaringClassName();
                        String methodName = methodStat.getMethodName() + methodStat.getDescriptor();

                        List<VariableReference> paramRefs =
                                relevantSetterArguments(params, className, methodName, field);
                        for (VariableReference paramRef2 : paramRefs) {
                            if (!ConstructionReferenceUtil.containsIdentity(relevantRefs, paramRef2)) {
                                relevantRefs.add(paramRef2);
                                actionPositions.put(paramRef2, i);
                            }
                        }
                    }
                } else if (stat instanceof AssignmentStatement) {
                    // obj.name = string;
                    AssignmentStatement assignStat = (AssignmentStatement) stat;
                    String returnName = assignStat.getReturnValue().getName();
                    if (returnName.contains(".")) {
                        String fieldName = returnName.substring(returnName.lastIndexOf(".") + 1);
                        if (fieldName.equals(field.getName())
                                && ConstructionReferenceUtil.sameReference(
                                        test,
                                        assignStat.getReturnValue().getAdditionalVariableReference(),
                                        targetObject)) {
                            VariableReference ref = assignStat.getValue();
                            if (!ConstructionReferenceUtil.containsIdentity(relevantRefs, ref)) {
                                relevantRefs.add(ref);
                                actionPositions.put(ref, i);
                            }
                        }
                    }
                }
            }
        }

        // get the var reference with more compatible types
        if (relevantRefs.isEmpty()) {
            return null;
        }

        List<VariableReference> compatibles = getCompatibleRef(relevantRefs, field);
        VariableReference ref = null;
        if (!compatibles.isEmpty()) {
            ref = getLast(compatibles, actionPositions);
        } else {
            ref = getLast(relevantRefs, actionPositions);
        }

        return ref;
    }

    private VariableReference getLast(
            List<VariableReference> compatibles, Map<VariableReference, Integer> actionPositions) {
        VariableReference result = null;
        int lastActionPosition = Integer.MIN_VALUE;
        for (VariableReference reference : compatibles) {
            Integer actionPosition = actionPositions.get(reference);
            if (actionPosition != null && actionPosition >= lastActionPosition) {
                lastActionPosition = actionPosition;
                result = reference;
            }
        }
        return result;
    }

    private List<VariableReference> getCompatibleRef(
            List<VariableReference> relevantRefs, Field field) {
        List<VariableReference> list = new ArrayList<>();
        for (VariableReference ref : relevantRefs) {
            String refType = ref.getType().getTypeName();

            if (refType.contains("<")) {
                refType = refType.substring(0, refType.indexOf("<"));
            }

            String fieldType = field.getType().getName();
            if (refType.equals(fieldType)) {
                list.add(ref);
            }
        }
        return list;
    }

    /**
     * Determines which parameters of a method influence or directly set the given field.
     *
     * @param className the declaring class of the method
     * @param methodName the method name followed by its descriptor
     * @param field the field that is set
     * @return the match, holding the positions of the relevant parameters
     */
    public ParameterMatch searchRelevantParameterOfSetterInTest(
            String className, String methodName, Field field) {

        Set<Integer> emptyParams = new HashSet<>();
        if (synthesisBudgetExceeded()) {
            return new ParameterMatch(false, emptyParams);
        }

        // get all the field setter bytecode instructions in the method. TODO: the field setter can
        // be taken from callee method of methodName.
        List<BytecodeInstruction> cascadingCallRelations = new ArrayList<>();
        List<ValueSettings> setter = new ArrayList<>();
        DataDependencyUtil.analyzeFieldSetter(
                className, methodName, field, 5, cascadingCallRelations, setter);
        if (setter.isEmpty()) {
            return new ParameterMatch(false, emptyParams);
        }
        ValueSettings setting = setter.get(0);
        return new ParameterMatch(!setting.releventPrams.isEmpty(), setting.releventPrams);
    }

    /**
     * Returns the arguments of a call to the given method or constructor that flow into the field.
     *
     * @param params the arguments of the call
     * @param className the declaring class of the method
     * @param methodName the method name followed by its descriptor
     * @param field the field that is set
     * @return the arguments at the relevant parameter positions, in position order of the match
     */
    public List<VariableReference> relevantSetterArguments(
            List<VariableReference> params, String className, String methodName, Field field) {
        ParameterMatch result = searchRelevantParameterOfSetterInTest(className, methodName, field);
        List<VariableReference> paramRefs = new ArrayList<>();
        for (Integer index : result.parameterPoisitions) {
            if (index >= 0 && index < params.size()) {
                paramRefs.add(params.get(index));
            }
        }
        return paramRefs;
    }
}
