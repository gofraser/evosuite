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
package org.evosuite.llm.postprocess;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.DefaultTestCase;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Unified LLM post-processing edits accepted for one test case.
 */
public final class LlmPostProcessingMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    /*
     * Version 1 production suites use DefaultTestCase, which stores this metadata
     * as serialized instance state. The weak fallback keeps tests/non-standard
     * TestCase implementations usable inside one JVM only; it is intentionally
     * not an RMI/serialization contract.
     */
    private static final Map<TestCase, LlmPostProcessingMetadata> BY_TEST =
            Collections.synchronizedMap(new WeakHashMap<TestCase, LlmPostProcessingMetadata>());

    private String testName;
    private final Map<Integer, String> variableNames = new LinkedHashMap<>();
    private final Map<Integer, List<String>> commentsAfter = new LinkedHashMap<>();
    private final Set<Integer> sectionBreaksAfter = new LinkedHashSet<>();

    public LlmPostProcessingMetadata() {
        // Serializable DTO.
    }

    public static LlmPostProcessingMetadata getOrCreate(TestCase test) {
        if (test == null) {
            throw new IllegalArgumentException("test must not be null");
        }
        if (test instanceof DefaultTestCase) {
            return ((DefaultTestCase) test).getOrCreateLlmPostProcessingMetadata();
        }
        synchronized (BY_TEST) {
            LlmPostProcessingMetadata metadata = BY_TEST.get(test);
            if (metadata == null) {
                metadata = new LlmPostProcessingMetadata();
                BY_TEST.put(test, metadata);
            }
            return metadata;
        }
    }

    public static LlmPostProcessingMetadata get(TestCase test) {
        if (test == null) {
            return null;
        }
        if (test instanceof DefaultTestCase) {
            return ((DefaultTestCase) test).getLlmPostProcessingMetadata();
        }
        synchronized (BY_TEST) {
            return BY_TEST.get(test);
        }
    }

    public static void clear(TestCase test) {
        if (test == null) {
            return;
        }
        if (test instanceof DefaultTestCase) {
            ((DefaultTestCase) test).clearLlmPostProcessingMetadata();
            return;
        }
        synchronized (BY_TEST) {
            BY_TEST.remove(test);
        }
    }

    public static void copyTo(TestCase source, TestCase target, int offset) {
        if (source == null || target == null) {
            return;
        }
        LlmPostProcessingMetadata sourceMetadata = get(source);
        if (sourceMetadata == null) {
            return;
        }
        LlmPostProcessingMetadata targetMetadata = getOrCreate(target);
        targetMetadata.testName = sourceMetadata.testName;
        LlmPostProcessingReferences targetReferences = LlmPostProcessingReferences.from(target);

        for (Map.Entry<Integer, String> entry : sourceMetadata.variableNames.entrySet()) {
            int targetPosition = entry.getKey() + offset;
            String variableId = LlmPostProcessingReferences.variableId(targetPosition);
            if (targetReferences.hasVariableId(variableId)) {
                targetMetadata.variableNames.put(targetPosition, entry.getValue());
            }
        }

        for (Map.Entry<Integer, List<String>> entry : sourceMetadata.commentsAfter.entrySet()) {
            int targetPosition = entry.getKey() + offset;
            if (!targetReferences.hasStatementId(LlmPostProcessingReferences.statementId(targetPosition))) {
                continue;
            }
            List<String> comments = targetMetadata.commentsAfter.get(targetPosition);
            if (comments == null) {
                comments = new ArrayList<>();
                targetMetadata.commentsAfter.put(targetPosition, comments);
            }
            comments.addAll(entry.getValue());
        }

        for (Integer position : sourceMetadata.sectionBreaksAfter) {
            int targetPosition = position + offset;
            if (targetReferences.hasStatementId(LlmPostProcessingReferences.statementId(targetPosition))) {
                targetMetadata.sectionBreaksAfter.add(targetPosition);
            }
        }
    }

    public LlmPostProcessingMetadata copy() {
        LlmPostProcessingMetadata copy = new LlmPostProcessingMetadata();
        copy.testName = testName;
        copy.variableNames.putAll(variableNames);
        for (Map.Entry<Integer, List<String>> entry : commentsAfter.entrySet()) {
            copy.commentsAfter.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        copy.sectionBreaksAfter.addAll(sectionBreaksAfter);
        return copy;
    }

    public void replaceWith(LlmPostProcessingMetadata other) {
        testName = other == null ? null : other.testName;
        variableNames.clear();
        commentsAfter.clear();
        sectionBreaksAfter.clear();
        if (other == null) {
            return;
        }
        variableNames.putAll(other.variableNames);
        for (Map.Entry<Integer, List<String>> entry : other.commentsAfter.entrySet()) {
            commentsAfter.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        sectionBreaksAfter.addAll(other.sectionBreaksAfter);
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public Map<Integer, String> getVariableNames() {
        return Collections.unmodifiableMap(variableNames);
    }

    public String getVariableName(int statementPosition) {
        return variableNames.get(statementPosition);
    }

    public void putVariableName(int statementPosition, String name) {
        variableNames.put(statementPosition, name);
    }

    public List<String> getCommentsAfter(int statementPosition) {
        List<String> comments = commentsAfter.get(statementPosition);
        if (comments == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(comments);
    }

    public void addCommentAfter(int statementPosition, String comment) {
        List<String> comments = commentsAfter.get(statementPosition);
        if (comments == null) {
            comments = new ArrayList<>();
            commentsAfter.put(statementPosition, comments);
        }
        comments.add(comment);
    }

    public boolean hasSectionBreakAfter(int statementPosition) {
        return sectionBreaksAfter.contains(statementPosition);
    }

    public void addSectionBreakAfter(int statementPosition) {
        sectionBreaksAfter.add(statementPosition);
    }
}
