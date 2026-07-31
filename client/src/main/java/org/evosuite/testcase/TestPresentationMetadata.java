/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.testcase;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Source-neutral presentation edits attached to one test case. */
public final class TestPresentationMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private String testName;
    private final Map<Integer, String> variableNames = new LinkedHashMap<>();
    private final Map<Integer, List<String>> commentsAfter = new LinkedHashMap<>();
    private final Set<Integer> sectionBreaksAfter = new LinkedHashSet<>();

    public static TestPresentationMetadata getOrCreate(TestCase test) {
        if (!(test instanceof DefaultTestCase)) {
            throw new IllegalArgumentException("Presentation metadata requires DefaultTestCase");
        }
        return ((DefaultTestCase) test).getOrCreatePresentationMetadata();
    }

    public static TestPresentationMetadata get(TestCase test) {
        return test instanceof DefaultTestCase
                ? ((DefaultTestCase) test).getPresentationMetadata() : null;
    }

    public static void clear(TestCase test) {
        if (test instanceof DefaultTestCase) {
            ((DefaultTestCase) test).clearPresentationMetadata();
        }
    }

    public static void copyTo(TestCase source, TestCase target, int offset) {
        TestPresentationMetadata sourceMetadata = get(source);
        if (sourceMetadata == null || !(target instanceof DefaultTestCase)) {
            return;
        }
        TestPresentationMetadata targetMetadata = getOrCreate(target);
        targetMetadata.testName = sourceMetadata.testName;
        LlmReferenceAccess references = new LlmReferenceAccess(target);
        for (Map.Entry<Integer, String> entry : sourceMetadata.variableNames.entrySet()) {
            int position = entry.getKey() + offset;
            if (references.hasVariable(position)) {
                targetMetadata.variableNames.put(position, entry.getValue());
            }
        }
        for (Map.Entry<Integer, List<String>> entry : sourceMetadata.commentsAfter.entrySet()) {
            int position = entry.getKey() + offset;
            if (!references.hasStatement(position)) {
                continue;
            }
            List<String> comments = targetMetadata.commentsAfter.get(position);
            if (comments == null) {
                comments = new ArrayList<>();
                targetMetadata.commentsAfter.put(position, comments);
            }
            comments.addAll(entry.getValue());
        }
        for (Integer entry : sourceMetadata.sectionBreaksAfter) {
            int position = entry + offset;
            if (references.hasStatement(position)) {
                targetMetadata.sectionBreaksAfter.add(position);
            }
        }
    }

    public TestPresentationMetadata copy() {
        TestPresentationMetadata copy = new TestPresentationMetadata();
        copy.testName = testName;
        copy.variableNames.putAll(variableNames);
        for (Map.Entry<Integer, List<String>> entry : commentsAfter.entrySet()) {
            copy.commentsAfter.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        copy.sectionBreaksAfter.addAll(sectionBreaksAfter);
        return copy;
    }

    public void replaceWith(TestPresentationMetadata other) {
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

    public String getTestName() { return testName; }
    public void setTestName(String testName) { this.testName = testName; }
    public Map<Integer, String> getVariableNames() { return Collections.unmodifiableMap(variableNames); }
    public String getVariableName(int position) { return variableNames.get(position); }
    public void putVariableName(int position, String name) { variableNames.put(position, name); }

    public List<String> getCommentsAfter(int position) {
        List<String> comments = commentsAfter.get(position);
        return comments == null ? Collections.<String>emptyList() : Collections.unmodifiableList(comments);
    }

    public void addCommentAfter(int position, String comment) {
        List<String> comments = commentsAfter.get(position);
        if (comments == null) {
            comments = new ArrayList<>();
            commentsAfter.put(position, comments);
        }
        comments.add(comment);
    }

    public boolean hasSectionBreakAfter(int position) { return sectionBreaksAfter.contains(position); }
    public void addSectionBreakAfter(int position) { sectionBreaksAfter.add(position); }

    /** Minimal stable-ID access kept here so core code does not depend on LLM helpers. */
    private static final class LlmReferenceAccess {
        private final TestCase test;
        private LlmReferenceAccess(TestCase test) { this.test = test; }
        private boolean hasVariable(int position) {
            return position >= 0 && position < test.size()
                    && test.getStatement(position).getReturnValue() != null;
        }
        private boolean hasStatement(int position) { return position >= 0 && position < test.size(); }
    }
}
