package org.evosuite.testcase.synthesizer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.variable.VariableReference;
import org.objectweb.asm.Type;

/** Per-attempt, diagnostic-only trace of graph-to-code candidate actions and validation issues. */
public final class ObjectConstructionActionTrace {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private ObjectConstructionActionTrace() {
        // utility class
    }

    public static void begin() {
        if (Properties.OBJECT_RULE_GRAPH_DIAGNOSTICS) {
            CURRENT.set(new State());
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void recordCandidates(Field field, String receiverType,
            Map<Executable, List<ValueSettings>> candidates, double[] scores,
            double[] cumulativeProbabilities, int selectedIndex,
            Map<Executable, String> rejectionReasons) {
        State state = CURRENT.get();
        if (state == null || candidates == null) {
            return;
        }
        int index = 0;
        double previous = 0.0;
        for (Map.Entry<Executable, List<ValueSettings>> entry : candidates.entrySet()) {
            Executable executable = entry.getKey();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("candidate_id", state.nextCandidateId++);
            record.put("field_owner", field == null ? null : field.getDeclaringClass().getName());
            record.put("field_name", field == null ? null : field.getName());
            record.put("receiver_type", receiverType);
            record.put("kind", executable instanceof Constructor ? "constructor" : "method");
            record.put("executable", signature(executable));
            record.put("relevant_parameter_indices", relevantParameters(entry.getValue()));
            record.put("score", scores != null && index < scores.length
                    ? finiteOrNull(scores[index]) : null);
            double rawCumulative = cumulativeProbabilities != null
                    && index < cumulativeProbabilities.length
                    ? cumulativeProbabilities[index] : previous;
            double cumulative = Double.isNaN(rawCumulative) || Double.isInfinite(rawCumulative)
                    ? previous : rawCumulative;
            record.put("selection_probability", finiteOrNull(Math.max(0.0, cumulative - previous)));
            String rejectionReason = rejectionReasons == null ? null : rejectionReasons.get(executable);
            record.put("eligible", rejectionReason == null);
            record.put("rejection_reason", rejectionReason);
            boolean selected = index == selectedIndex;
            record.put("selected", selected);
            record.put("outcome", selected ? "selected_pending" : "not_selected");
            state.candidates.add(record);
            if (selected) {
                state.selectedKeys.put(candidateKey(field, executable), record);
            }
            previous = cumulative;
            index++;
        }
        if (candidates.isEmpty()) {
            issue("no_candidate_action", null,
                    field == null ? null : field.getDeclaringClass().getName() + "." + field.getName(),
                    "No constructor or method candidate was discovered for the field");
        }
    }

    public static void recordSelectedOutcome(Field field, Executable executable, String outcome,
            TestCase test, VariableReference result, VariableReference receiver) {
        State state = CURRENT.get();
        if (state == null) {
            return;
        }
        Map<String, Object> record = state.selectedKeys.get(candidateKey(field, executable));
        if (record == null) {
            return;
        }
        record.put("outcome", outcome);
        record.put("result_definition_position", definitionPosition(test, result));
        int receiverPosition = definitionPosition(test, receiver);
        record.put("receiver_definition_position", receiverPosition);
        if (executable instanceof Method
                && !Modifier.isStatic(executable.getModifiers()) && receiverPosition < 0
                && outcome != null && !outcome.contains("failed")) {
            issue("selected_method_has_no_receiver", null, signature(executable),
                    "The selected instance method did not act on a test-defined receiver");
        }
    }

    public static void recordExistingAction(Field field, Executable executable,
            Set<Integer> relevantParameters, TestCase test, VariableReference receiver,
            VariableReference result) {
        State state = CURRENT.get();
        if (state == null || executable == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("candidate_id", state.nextCandidateId++);
        record.put("field_owner", field == null ? null : field.getDeclaringClass().getName());
        record.put("field_name", field == null ? null : field.getName());
        record.put("receiver_type", receiver == null ? null : receiver.getClassName());
        record.put("kind", executable instanceof Constructor ? "constructor" : "method");
        record.put("executable", signature(executable));
        record.put("relevant_parameter_indices", relevantParameters == null
                ? Collections.emptyList() : new ArrayList<>(relevantParameters));
        record.put("score", null);
        record.put("selection_probability", null);
        record.put("eligible", true);
        record.put("rejection_reason", null);
        record.put("selected", true);
        record.put("outcome", "reused_existing_statement");
        int resultPosition = definitionPosition(test, result);
        int receiverPosition = definitionPosition(test, receiver);
        record.put("result_definition_position", resultPosition);
        record.put("receiver_definition_position", receiverPosition);
        state.candidates.add(record);
        if (resultPosition < 0) {
            issue("reused_action_result_not_defined", null, signature(executable),
                    "The relevant action argument is not defined by this test");
        }
        if (receiverPosition < 0) {
            issue("reused_action_receiver_not_defined", null, signature(executable),
                    "The reused action is not tied to a test-defined receiver");
        }
    }

    /** Records the methods considered for realizing an element of an object-owned array field. */
    public static void recordArrayElementCandidates(String fieldOwner, String fieldName,
            String receiverType, Map<Method, Integer> candidates, Method selected) {
        State state = CURRENT.get();
        if (state == null || candidates == null) {
            return;
        }
        for (Map.Entry<Method, Integer> entry : candidates.entrySet()) {
            Method method = entry.getKey();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("candidate_id", state.nextCandidateId++);
            record.put("field_owner", fieldOwner);
            record.put("field_name", fieldName);
            record.put("receiver_type", receiverType);
            record.put("kind", "method");
            record.put("executable", signature(method));
            record.put("relevant_parameter_indices",
                    Collections.singletonList(entry.getValue()));
            record.put("score", null);
            record.put("selection_probability", selected == null
                    ? null : (method.equals(selected) ? 1.0 : 0.0));
            record.put("eligible", true);
            record.put("rejection_reason", null);
            boolean isSelected = method.equals(selected);
            record.put("selected", isSelected);
            record.put("outcome", isSelected ? "selected_pending" : "not_selected");
            state.candidates.add(record);
            if (isSelected) {
                state.selectedKeys.put(arrayCandidateKey(fieldOwner, fieldName, method), record);
            }
        }
        if (candidates.isEmpty()) {
            issue("no_array_element_setter", null, fieldOwner + "." + fieldName,
                    "No public method parameter flows into this array field's stored element");
        }
    }

    /** Completes the selected array-element action record after its statement was generated. */
    public static void recordArrayElementOutcome(String fieldOwner, String fieldName, Method method,
            String outcome, TestCase test, VariableReference result, VariableReference receiver) {
        State state = CURRENT.get();
        if (state == null || method == null) {
            return;
        }
        Map<String, Object> record = state.selectedKeys.get(
                arrayCandidateKey(fieldOwner, fieldName, method));
        if (record == null) {
            return;
        }
        record.put("outcome", outcome);
        record.put("result_definition_position", definitionPosition(test, result));
        record.put("receiver_definition_position", definitionPosition(test, receiver));
    }

    /** Records reuse of an array-element setter already present in the test. */
    public static void recordExistingArrayElementAction(String fieldOwner, String fieldName,
            Method method, int relevantParameter, TestCase test, VariableReference receiver,
            VariableReference result) {
        State state = CURRENT.get();
        if (state == null || method == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("candidate_id", state.nextCandidateId++);
        record.put("field_owner", fieldOwner);
        record.put("field_name", fieldName);
        record.put("receiver_type", receiver == null ? null : receiver.getClassName());
        record.put("kind", "method");
        record.put("executable", signature(method));
        record.put("relevant_parameter_indices", Collections.singletonList(relevantParameter));
        record.put("score", null);
        record.put("selection_probability", null);
        record.put("eligible", true);
        record.put("rejection_reason", null);
        record.put("selected", true);
        record.put("outcome", "reused_existing_statement");
        record.put("result_definition_position", definitionPosition(test, result));
        record.put("receiver_definition_position", definitionPosition(test, receiver));
        state.candidates.add(record);
    }

    public static void issue(String code, Integer nodeId, String subject, String detail) {
        State state = CURRENT.get();
        if (state == null) {
            return;
        }
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("code", code);
        issue.put("node_id", nodeId);
        issue.put("subject", subject);
        issue.put("detail", detail);
        state.issues.add(issue);
    }

    /** Records a deterministic setup inferred from an observation rather than a field setter. */
    public static void setupAction(String kind, Integer nodeId, String subject, int resultPosition) {
        State state = CURRENT.get();
        if (state == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("candidate_id", state.nextCandidateId++);
        record.put("kind", kind);
        record.put("node_id", nodeId);
        record.put("subject", subject);
        record.put("eligible", true);
        record.put("selected", true);
        record.put("outcome", "materialized_observation_setup");
        record.put("result_definition_position", resultPosition);
        state.candidates.add(record);
    }

    public static List<Map<String, Object>> candidateActions() {
        State state = CURRENT.get();
        return state == null ? Collections.emptyList() : new ArrayList<>(state.candidates);
    }

    public static List<Map<String, Object>> validationIssues() {
        State state = CURRENT.get();
        return state == null ? Collections.emptyList() : new ArrayList<>(state.issues);
    }

    public static int definitionPosition(TestCase test, VariableReference reference) {
        if (test == null || reference == null) {
            return -1;
        }
        for (int position = 0; position < test.size(); position++) {
            if (test.getStatement(position).getReturnValue() == reference) {
                return position;
            }
        }
        for (int position = 0; position < test.size(); position++) {
            VariableReference candidate = test.getStatement(position).getReturnValue();
            if (candidate != null && candidate.equals(reference)) {
                return position;
            }
        }
        return -1;
    }

    private static String signature(Executable executable) {
        if (executable instanceof Method) {
            Method method = (Method) executable;
            return method.getDeclaringClass().getName() + "." + method.getName()
                    + Type.getMethodDescriptor(method);
        }
        Constructor<?> constructor = (Constructor<?>) executable;
        return constructor.getDeclaringClass().getName() + ".<init>"
                + Type.getConstructorDescriptor(constructor);
    }

    private static List<Integer> relevantParameters(List<ValueSettings> settings) {
        Set<Integer> result = new LinkedHashSet<>();
        if (settings != null) {
            for (ValueSettings setting : settings) {
                if (setting != null && setting.releventPrams != null) {
                    result.addAll(setting.releventPrams);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static String candidateKey(Field field, Executable executable) {
        String fieldKey = field == null ? "unknown" : field.getDeclaringClass().getName() + "." + field.getName();
        return fieldKey + "::" + signature(executable);
    }

    private static String arrayCandidateKey(String fieldOwner, String fieldName, Method method) {
        return fieldOwner + "." + fieldName + "[]::" + signature(method);
    }

    private static Double finiteOrNull(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
    }

    private static final class State {
        private int nextCandidateId;
        private final List<Map<String, Object>> candidates = new ArrayList<>();
        private final List<Map<String, Object>> issues = new ArrayList<>();
        private final Map<String, Map<String, Object>> selectedKeys = new LinkedHashMap<>();
    }
}
