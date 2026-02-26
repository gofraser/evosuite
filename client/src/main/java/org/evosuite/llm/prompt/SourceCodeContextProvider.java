package org.evosuite.llm.prompt;

import org.evosuite.setup.TestCluster;

import java.util.Optional;

/**
 * Provides real source code context for the CUT using the existing SourceCodeProvider.
 */
public class SourceCodeContextProvider implements SutContextProvider {

    private final SourceCodeProvider sourceCodeProvider;

    public SourceCodeContextProvider() {
        this(new SourceCodeProvider());
    }

    public SourceCodeContextProvider(SourceCodeProvider sourceCodeProvider) {
        this.sourceCodeProvider = sourceCodeProvider;
    }

    @Override
    public Optional<String> getContext(String className, TestCluster cluster) {
        return sourceCodeProvider.getSourceCode(className);
    }

    @Override
    public String modeLabel() {
        return "Source code";
    }
}
