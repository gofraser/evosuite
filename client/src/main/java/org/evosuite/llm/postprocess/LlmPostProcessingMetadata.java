/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestPresentationMetadata;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Compatibility facade for old integrations.  Storage belongs to the neutral
 * {@link TestPresentationMetadata}; this class contains no fallback map and no
 * LLM-specific state on core test classes.
 */
@Deprecated
public final class LlmPostProcessingMetadata implements Serializable {
    private static final long serialVersionUID = 1L;
    private final TestPresentationMetadata delegate;

    /** Compatibility-only detached metadata value used by legacy unit callers. */
    public LlmPostProcessingMetadata() {
        this(new TestPresentationMetadata());
    }

    private LlmPostProcessingMetadata(TestPresentationMetadata delegate) {
        this.delegate = delegate;
    }

    public static LlmPostProcessingMetadata getOrCreate(TestCase test) {
        return new LlmPostProcessingMetadata(TestPresentationMetadata.getOrCreate(test));
    }

    public static LlmPostProcessingMetadata get(TestCase test) {
        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        return metadata == null ? null : new LlmPostProcessingMetadata(metadata);
    }

    public static void clear(TestCase test) { TestPresentationMetadata.clear(test); }

    public static void copyTo(TestCase source, TestCase target, int offset) {
        TestPresentationMetadata.copyTo(source, target, offset);
    }

    public LlmPostProcessingMetadata copy() {
        return new LlmPostProcessingMetadata(delegate.copy());
    }

    public void replaceWith(LlmPostProcessingMetadata other) {
        delegate.replaceWith(other == null ? null : other.delegate);
    }

    public String getTestName() { return delegate.getTestName(); }
    public void setTestName(String value) { delegate.setTestName(value); }
    public Map<Integer, String> getVariableNames() { return delegate.getVariableNames(); }
    public String getVariableName(int position) { return delegate.getVariableName(position); }
    public void putVariableName(int position, String value) { delegate.putVariableName(position, value); }
    public List<String> getCommentsAfter(int position) { return delegate.getCommentsAfter(position); }
    public void addCommentAfter(int position, String comment) { delegate.addCommentAfter(position, comment); }
    public boolean hasSectionBreakAfter(int position) { return delegate.hasSectionBreakAfter(position); }
    public void addSectionBreakAfter(int position) { delegate.addSectionBreakAfter(position); }
}
