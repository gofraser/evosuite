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
import java.util.function.IntUnaryOperator;

/** Source-neutral presentation edits attached to one test case. */
public final class TestPresentationMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private String testName;
    private final Map<Integer, String> variableNames = new LinkedHashMap<>();
    private final Map<Integer, List<String>> commentsAfter = new LinkedHashMap<>();
    private final Set<Integer> sectionBreaksAfter = new LinkedHashSet<>();

    /**
     * Returns the presentation metadata of a test case, creating it if necessary.
     *
     * @param test the test case, which must be a {@link DefaultTestCase}
     * @return the metadata attached to the test case
     */
    public static TestPresentationMetadata getOrCreate(TestCase test) {
        if (!(test instanceof DefaultTestCase)) {
            throw new IllegalArgumentException("Presentation metadata requires DefaultTestCase");
        }
        return ((DefaultTestCase) test).getOrCreatePresentationMetadata();
    }

    /**
     * Returns the presentation metadata of a test case, if it has any.
     *
     * @param test the test case
     * @return the metadata, or null if there is none or the test is not a {@link DefaultTestCase}
     */
    public static TestPresentationMetadata get(TestCase test) {
        return test instanceof DefaultTestCase
                ? ((DefaultTestCase) test).getPresentationMetadata() : null;
    }

    /**
     * Removes the presentation metadata of a test case.
     *
     * @param test the test case to clear
     */
    public static void clear(TestCase test) {
        if (test instanceof DefaultTestCase) {
            ((DefaultTestCase) test).clearPresentationMetadata();
        }
    }

    /**
     * Copies the presentation metadata of one test case to another, shifting positions by an offset.
     *
     * @param source the test case to copy from
     * @param target the test case to copy to
     * @param offset the number of statements by which positions in the target are shifted
     */
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

    /**
     * Drops the metadata entry at {@code position} (if any) and shifts every entry keyed
     * at a position greater than {@code position} down by one, across all three
     * collections. Mirrors {@link #copyTo(TestCase, TestCase, int)}, which already applies
     * a position offset when metadata is carried from one test case to another; this is
     * the in-place equivalent for a single statement removal.
     *
     * @param position the position of the removed statement
     */
    public void removePosition(int position) {
        remap(key -> key == position ? -1 : (key > position ? key - 1 : key));
    }

    /**
     * Makes room for a statement inserted at {@code position} by shifting every entry
     * keyed at a position greater than or equal to {@code position} up by one, across all
     * three collections. The inverse of {@link #removePosition(int)}.
     *
     * @param position the position of the inserted statement
     */
    public void insertPosition(int position) {
        remap(key -> key >= position ? key + 1 : key);
    }

    /**
     * Moves every entry of all three collections to {@code newKey(key)}, dropping it on -1.
     */
    private void remap(IntUnaryOperator newKey) {
        remap(variableNames, newKey);
        remap(commentsAfter, newKey);
        Set<Integer> shiftedSectionBreaksAfter = new LinkedHashSet<>();
        for (Integer key : sectionBreaksAfter) {
            int shifted = newKey.applyAsInt(key);
            if (shifted >= 0) {
                shiftedSectionBreaksAfter.add(shifted);
            }
        }
        sectionBreaksAfter.clear();
        sectionBreaksAfter.addAll(shiftedSectionBreaksAfter);
    }

    private static <V> void remap(Map<Integer, V> map, IntUnaryOperator newKey) {
        Map<Integer, V> shifted = new LinkedHashMap<>();
        for (Map.Entry<Integer, V> entry : map.entrySet()) {
            int key = newKey.applyAsInt(entry.getKey());
            if (key >= 0) {
                shifted.put(key, entry.getValue());
            }
        }
        map.clear();
        map.putAll(shifted);
    }

    /**
     * Drops every metadata entry keyed at or beyond {@code length}, across all three
     * collections. Used when the statement list is truncated (e.g. {@code chop}), where
     * surviving positions below {@code length} do not move and only need the dangling
     * trailing entries removed.
     *
     * @param length the new number of statements
     */
    public void truncateFrom(int length) {
        variableNames.keySet().removeIf(key -> key >= length);
        commentsAfter.keySet().removeIf(key -> key >= length);
        sectionBreaksAfter.removeIf(key -> key >= length);
    }

    /**
     * Creates a deep copy of this metadata.
     *
     * @return an independent copy
     */
    public TestPresentationMetadata copy() {
        TestPresentationMetadata copy = new TestPresentationMetadata();
        copy.replaceWith(this);
        return copy;
    }

    /**
     * Replaces the contents of this metadata with a copy of another one.
     *
     * @param other the metadata to take over, or null to clear this one
     */
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

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public Map<Integer, String> getVariableNames() {
        return Collections.unmodifiableMap(variableNames);
    }

    public String getVariableName(int position) {
        return variableNames.get(position);
    }

    public void putVariableName(int position, String name) {
        variableNames.put(position, name);
    }

    /**
     * Returns the comments rendered after the statement at a position.
     *
     * @param position the statement position
     * @return an unmodifiable list of comments, possibly empty
     */
    public List<String> getCommentsAfter(int position) {
        List<String> comments = commentsAfter.get(position);
        return comments == null ? Collections.<String>emptyList() : Collections.unmodifiableList(comments);
    }

    /**
     * Adds a comment to be rendered after the statement at a position.
     *
     * @param position the statement position
     * @param comment the comment text
     */
    public void addCommentAfter(int position, String comment) {
        List<String> comments = commentsAfter.get(position);
        if (comments == null) {
            comments = new ArrayList<>();
            commentsAfter.put(position, comments);
        }
        comments.add(comment);
    }

    public boolean hasSectionBreakAfter(int position) {
        return sectionBreaksAfter.contains(position);
    }

    public void addSectionBreakAfter(int position) {
        sectionBreaksAfter.add(position);
    }

    /**
     * Minimal stable-ID access kept here so core code does not depend on LLM helpers.
     */
    private static final class LlmReferenceAccess {
        private final TestCase test;

        private LlmReferenceAccess(TestCase test) {
            this.test = test;
        }

        private boolean hasVariable(int position) {
            return position >= 0 && position < test.size()
                    && test.getStatement(position).getReturnValue() != null;
        }

        private boolean hasStatement(int position) {
            return position >= 0 && position < test.size();
        }
    }
}
