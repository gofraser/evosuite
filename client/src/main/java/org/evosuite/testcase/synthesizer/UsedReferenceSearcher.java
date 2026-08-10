package org.evosuite.testcase.synthesizer;

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

public class UsedReferenceSearcher {
    public VariableReference searchRelevantFieldReadingReferenceInTest(
            TestCase test, Field field, VariableReference targetObject) {
        List<VariableReference> relevantRefs = new ArrayList<VariableReference>();
        List<VariableReference> backuprelevantRefs = new ArrayList<>();

        if (targetObject != null) {
            for (int i = 0; i < test.size(); i++) {
                Statement stat = test.getStatement(i);
                if (stat instanceof MethodStatement) {
                    MethodStatement mStat = (MethodStatement) stat;
                    VariableReference ref = mStat.getCallee();
                    if (sameReference(test, ref, targetObject)) {
                        boolean isValidGetter =
                                DataDependencyUtil.isFieldGetter(
                                        mStat.getMethod().getMethod(), field);
                        if (isValidGetter) {
                            relevantRefs.add(mStat.getReturnValue());
                        } else {
                            VariableReference usedFieldInTest =
                                    searchRelevantFieldWritingReferenceInTest(
                                            test, field, targetObject);
                            backuprelevantRefs.add(usedFieldInTest);
                        }
                    }
                } else if (stat instanceof ConstructorStatement) {
                    ConstructorStatement constructorStat = (ConstructorStatement) stat;
                    if (sameReference(test, constructorStat.getReturnValue(), targetObject)) {
                        List<VariableReference> params = constructorStat.getParameterReferences();
                        String className = constructorStat.getDeclaringClassName();
                        String methodName =
                                constructorStat.getMethodName() + constructorStat.getDescriptor();
                        if (!params.isEmpty()) {

                            //                            List<VariableReference> paramRefs =
                            // searchRelevantParameterOfSetterInTest(params, className, methodName,
                            // field);

                            ParameterMatch result =
                                    searchRelevantParameterOfSetterInTest(
                                            className, methodName, field);
                            List<VariableReference> paramRefs = new ArrayList<>();
                            for (Integer index : result.parameterPoisitions) {
                                paramRefs.add(params.get(index));
                            }

                            relevantRefs.addAll(paramRefs);
                        }
                    }
                }
            }
        }

        /** FIXME, ziheng: we need to provide some priority for those parameters here. */
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
     * @param test
     * @param field
     * @param targetObject
     * @return
     */
    public VariableReference searchRelevantFieldWritingReferenceInTest(
            TestCase test, Field field, VariableReference targetObject) {

        List<VariableReference> relevantRefs = new ArrayList<VariableReference>();

        Map<VariableReference, Integer> actionPositions = new IdentityHashMap<>();

        if (targetObject != null) {

            /**
             * check the variables passed as parameters to the constructor of targetObject, which
             * are data-flow relevant to writing the field @{code field}
             */
            int pos = ConstructionReferenceUtil.definitionPosition(test, targetObject);
            if (pos < 0) {
                return null;
            }
            Statement s = test.getStatement(pos);
            if (s instanceof NullStatement) {
                TestFactory testFactory = TestFactory.getInstance();
                boolean isSuccess = testFactory.changeNullStatement(test, s);
                if (isSuccess) {
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
                    ParameterMatch result =
                            searchRelevantParameterOfSetterInTest(className, methodName, field);
                    List<VariableReference> paramRefs = new ArrayList<>();
                    for (Integer index : result.parameterPoisitions) {
                        if (index >= 0 && index < params.size()) {
                            paramRefs.add(params.get(index));
                        }
                    }
                    //                    List<VariableReference> paramRefs =
                    // searchRelevantParameterOfSetterInTest(params, className, methodName, field);
                    relevantRefs.addAll(paramRefs);

                    for (VariableReference vRef : paramRefs) {
                        actionPositions.put(vRef, pos);
                    }
                    //                    System.currentTimeMillis();
                }
            }

            /**
             * check the variables passed as parameters to the method invocation from targetObject,
             * which are data-flow relevant to writing the field @{code field}
             */
            for (int i = 0; i < test.size(); i++) {
                Statement stat = test.getStatement(i);
                if (stat instanceof MethodStatement) {
                    MethodStatement mStat = (MethodStatement) stat;
                    VariableReference ref = mStat.getCallee();
                    if (sameReference(test, ref, targetObject)) {
                        List<VariableReference> params = mStat.getParameterReferences();
                        String className = mStat.getDeclaringClassName();
                        String methodName = mStat.getMethodName() + mStat.getDescriptor();

                        ParameterMatch result =
                                searchRelevantParameterOfSetterInTest(className, methodName, field);
                        List<VariableReference> paramRefs = new ArrayList<>();
                        for (Integer index : result.parameterPoisitions) {
                            if (index >= 0 && index < params.size()) {
                                paramRefs.add(params.get(index));
                            }
                        }
                        //                        List<VariableReference> paramRefs =
                        // searchRelevantParameterOfSetterInTest(params, className, methodName,
                        //                                field);

                        for (VariableReference pRef : paramRefs) {
                            if (!containsIdentity(relevantRefs, pRef)) {
                                relevantRefs.add(pRef);
                                actionPositions.put(pRef, i);
                            }
                        }
                    }
                } else if (stat instanceof AssignmentStatement) {
                    // obj.name = string;
                    AssignmentStatement aStat = (AssignmentStatement) stat;
                    String returnName = aStat.getReturnValue().getName();
                    if (returnName.contains(".")) {
                        String fieldName = returnName.substring(returnName.lastIndexOf(".") + 1);
                        if (fieldName.equals(field.getName())
                                && sameReference(
                                        test,
                                        aStat.getReturnValue().getAdditionalVariableReference(),
                                        targetObject)) {
                            VariableReference ref = aStat.getValue();
                            if (!containsIdentity(relevantRefs, ref)) {
                                relevantRefs.add(ref);
                                actionPositions.put(ref, i);
                            }
                        }
                    }
                }
            }
        }

        /** get the var reference with more compatible types */
        if (relevantRefs.isEmpty()) return null;

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

    private static boolean containsIdentity(
            List<VariableReference> references, VariableReference candidate) {
        for (VariableReference reference : references) {
            if (reference == candidate) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameReference(
            TestCase test, VariableReference left, VariableReference right) {
        if (left == null || right == null) {
            return false;
        }
        if (left == right) {
            return true;
        }
        int leftPosition = ConstructionReferenceUtil.definitionPosition(test, left);
        int rightPosition = ConstructionReferenceUtil.definitionPosition(test, right);
        return leftPosition >= 0 && leftPosition == rightPosition;
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
     * opcode should always be "getfield"
     *
     * @param statement
     * @param params
     * @param opcode
     * @return
     */
    public ParameterMatch searchRelevantParameterOfSetterInTest(
            String className, String methodName, Field field) {

        /**
         * get all the field setter bytecode instructions in the method. TODO: the field setter can
         * be taken from callee method of @code{methodName}.
         */
        List<BytecodeInstruction> cascadingCallRelations = new ArrayList<>();
        List<ValueSettings> setter = new ArrayList<>();
        DataDependencyUtil.analyzeFieldSetter(
                className, methodName, field, 5, cascadingCallRelations, setter);
        //        List<VariableReference> validParams = new ArrayList<VariableReference>();

        Set<Integer> validParams = new HashSet<>();
        if (setter.isEmpty()) {
            return new ParameterMatch(false, validParams);
        }

        for (ValueSettings setting : setter) {
            //            BytecodeInstruction setterIns = entry.getKey();
            //            List<BytecodeInstruction> callList = entry.getValue();

            //            Set<Integer> validParamPos =
            // DataDependencyUtil.checkValidParameterPositions(setterIns, className, methodName,
            // callList);
            //            for (Integer val : validParamPos) {
            //                if (val >= 0) {
            //                    validParams.add(val);
            //                }
            //            }
            //            System.currentTimeMillis();
            return new ParameterMatch(!setting.releventPrams.isEmpty(), setting.releventPrams);
        }

        return new ParameterMatch(false, validParams);
    }
}
