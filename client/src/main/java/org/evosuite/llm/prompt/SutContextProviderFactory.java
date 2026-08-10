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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.Properties.LlmSutContextMode;
import org.evosuite.setup.TestCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Selects and applies the configured {@link SutContextProvider} based on
 * {@code LLM_SUT_CONTEXT_MODE} with fallback/strict semantics.
 *
 * <p>Results are cached per (className, mode) so that expensive operations like
 * bytecode disassembly or decompilation are performed at most once per class per run.
 */
public class SutContextProviderFactory {

    private static final Logger logger = LoggerFactory.getLogger(SutContextProviderFactory.class);
    private static final Object INSTANCE_LOCK = new Object();
    private static volatile SutContextProviderFactory sharedInstance;

    private final SutContextProvider signatureProvider;
    private final SutContextProvider bytecodeProvider;
    private final SutContextProvider decompiledProvider;
    private final SutContextProvider sourceCodeProvider;

    private final ConcurrentMap<String, ContextResult> cache = new ConcurrentHashMap<>();

    /** Returns the process-scoped shared instance (lazy-initialized with default providers). */
    public static SutContextProviderFactory getInstance() {
        SutContextProviderFactory local = sharedInstance;
        if (local != null) {
            return local;
        }
        synchronized (INSTANCE_LOCK) {
            if (sharedInstance == null) {
                sharedInstance = new SutContextProviderFactory();
            }
            return sharedInstance;
        }
    }

    /** Replaces the shared instance for testing. */
    public static void setInstanceForTesting(SutContextProviderFactory factory) {
        synchronized (INSTANCE_LOCK) {
            sharedInstance = factory;
        }
    }

    /** Resets the shared instance for testing. */
    public static void resetInstanceForTesting() {
        synchronized (INSTANCE_LOCK) {
            sharedInstance = null;
        }
    }

    /** Clears process-scoped context and provider state after one EvoSuite run. */
    public static void resetForRunCompletion() {
        synchronized (INSTANCE_LOCK) {
            if (sharedInstance != null) {
                sharedInstance.clearCache();
            }
            sharedInstance = null;
        }
    }

    /** Constructs a factory with default provider implementations. */
    public SutContextProviderFactory() {
        this(new SignatureContextProvider(),
                new BytecodeContextProvider(),
                new DecompiledContextProvider(),
                new SourceCodeContextProvider());
    }

    /** Constructs a factory with explicit provider implementations for testing. */
    public SutContextProviderFactory(SutContextProvider signatureProvider,
                                     SutContextProvider bytecodeProvider,
                                     SutContextProvider decompiledProvider,
                                     SutContextProvider sourceCodeProvider) {
        this.signatureProvider = signatureProvider;
        this.bytecodeProvider = bytecodeProvider;
        this.decompiledProvider = decompiledProvider;
        this.sourceCodeProvider = sourceCodeProvider;
    }

    /**
     * Extract context using the configured mode, applying fallback/strict policy.
     * Results are cached so that bytecode disassembly / decompilation is done at most once per class.
     *
     * @return result containing context text, the mode used, and whether context was unavailable
     */
    public ContextResult getContext(String className, TestCluster cluster) {
        LlmSutContextMode mode = Properties.LLM_SUT_CONTEXT_MODE;
        // SIGNATURE_ONLY depends on TestCluster which may change after cluster expansion,
        // so we skip caching for it. Other modes are class-intrinsic and safe to cache.
        if (mode == LlmSutContextMode.SIGNATURE_ONLY) {
            return computeContext(className, cluster, mode);
        }
        String cacheKey = mode.name()
                + ":" + (className == null ? "" : className)
                + ":max=" + Properties.LLM_CONTEXT_MAX_CHARS
                + ":source=" + (Properties.LLM_SOURCE_PATH == null ? "" : Properties.LLM_SOURCE_PATH.trim());
        ContextResult cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        ContextResult result = computeContext(className, cluster, mode);
        // Only cache if the primary mode succeeded. Fallback results depend on
        // TestCluster state which may change after cluster expansion.
        if (result.getModeUsed() == mode) {
            cache.putIfAbsent(cacheKey, result);
        }
        return result;
    }

    /** Clears the cache. Useful in tests or if the class under test changes. */
    public void clearCache() {
        cache.clear();
    }

    private ContextResult computeContext(String className, TestCluster cluster, LlmSutContextMode mode) {
        SutContextProvider primary = providerFor(mode);

        Optional<String> context;
        try {
            context = primary.getContext(className, cluster);
        } catch (Exception e) {
            logger.debug("Context provider {} failed for {}: {}", mode, className, e.getMessage());
            context = Optional.empty();
        }

        if (context.isPresent()) {
            TruncateResult tr = truncate(context.get(), mode);
            return new ContextResult(tr.text, mode, false, tr.truncated, tr.commentsStripped,
                    tr.selectivelyTruncated);
        }

        // Primary failed — apply fallback policy
        if (mode == LlmSutContextMode.SIGNATURE_ONLY) {
            if (Properties.LLM_CONTEXT_FALLBACK_ENABLED) {
                return new ContextResult("", mode, false);
            }
            return new ContextResult("", mode, true);
        }

        if (Properties.LLM_CONTEXT_FALLBACK_ENABLED) {
            // WARN (not debug): a wholesale fallback means the requested
            // representation never reached the model. This previously hid a
            // 100% decompiler failure that silently degraded to SIGNATURE_ONLY.
            logger.warn("Context mode {} unavailable for {}; falling back to SIGNATURE_ONLY", mode, className);
            Optional<String> fallbackContext;
            try {
                fallbackContext = signatureProvider.getContext(className, cluster);
            } catch (Exception e) {
                logger.debug("Signature fallback also failed for {}: {}", className, e.getMessage());
                fallbackContext = Optional.empty();
            }
            if (fallbackContext.isPresent()) {
                TruncateResult tr = truncate(fallbackContext.get(), LlmSutContextMode.SIGNATURE_ONLY);
                return new ContextResult(tr.text, LlmSutContextMode.SIGNATURE_ONLY, false, tr.truncated,
                        tr.commentsStripped, tr.selectivelyTruncated);
            }
            return new ContextResult("", LlmSutContextMode.SIGNATURE_ONLY, true);
        }

        // Strict mode: leave context empty and flag unavailable
        return new ContextResult("", mode, true);
    }

    SutContextProvider providerFor(LlmSutContextMode mode) {
        switch (mode) {
            case SIGNATURE_ONLY:
                return signatureProvider;
            case BYTECODE_DISASSEMBLED:
                return bytecodeProvider;
            case DECOMPILED_SOURCE:
                return decompiledProvider;
            case SOURCE_CODE:
                return sourceCodeProvider;
            default:
                return signatureProvider;
        }
    }

    private TruncateResult truncate(String text, LlmSutContextMode mode) {
        int maxChars = Properties.LLM_CONTEXT_MAX_CHARS;
        if (maxChars <= 0 || text.length() <= maxChars) {
            return new TruncateResult(text, false, false, false);
        }

        // Try stripping comments to fit within budget
        String stripped = JavaCommentStripper.stripComments(text);
        if (stripped.length() <= maxChars) {
            return new TruncateResult(stripped, false, true, false);
        }

        // Try selective method truncation before hard truncation
        SelectiveMethodTruncator truncator = truncatorFor(mode);
        if (truncator != null) {
            String selective = truncator.truncate(stripped, maxChars);
            if (selective != null) {
                return new TruncateResult(selective, false, true, true);
            }
            logger.debug("Selective truncation failed for mode {}, falling back to hard truncation", mode);
        }

        // Still too large — hard-truncate the stripped text
        return new TruncateResult(stripped.substring(0, maxChars) + "\n... (truncated)", true, true, false);
    }

    static SelectiveMethodTruncator truncatorFor(LlmSutContextMode mode) {
        switch (mode) {
            case SOURCE_CODE:
            case DECOMPILED_SOURCE:
                return new JavaSourceSelectiveTruncator();
            case BYTECODE_DISASSEMBLED:
                return new BytecodeSelectiveTruncator();
            default:
                return null;
        }
    }

    private static class TruncateResult {
        final String text;
        final boolean truncated;
        final boolean commentsStripped;
        final boolean selectivelyTruncated;

        TruncateResult(String text, boolean truncated, boolean commentsStripped, boolean selectivelyTruncated) {
            this.text = text;
            this.truncated = truncated;
            this.commentsStripped = commentsStripped;
            this.selectivelyTruncated = selectivelyTruncated;
        }
    }

    /**
     * Result of context extraction, including trace metadata.
     */
    public static class ContextResult {
        private final String text;
        private final LlmSutContextMode modeUsed;
        private final boolean contextUnavailable;
        private final boolean contextTruncated;
        private final boolean commentsStripped;
        private final boolean selectivelyTruncated;

        public ContextResult(String text, LlmSutContextMode modeUsed, boolean contextUnavailable) {
            this(text, modeUsed, contextUnavailable, false, false, false);
        }

        /** Constructs a context result with text, mode, availability, and truncation flags. */
        public ContextResult(String text, LlmSutContextMode modeUsed, boolean contextUnavailable,
                             boolean contextTruncated) {
            this(text, modeUsed, contextUnavailable, contextTruncated, false, false);
        }

        /** Constructs a context result with text, mode, availability, truncation, and comment-stripping flags. */
        public ContextResult(String text, LlmSutContextMode modeUsed, boolean contextUnavailable,
                             boolean contextTruncated, boolean commentsStripped) {
            this(text, modeUsed, contextUnavailable, contextTruncated, commentsStripped, false);
        }

        /** Constructs a context result with all metadata flags including selective truncation. */
        public ContextResult(String text, LlmSutContextMode modeUsed, boolean contextUnavailable,
                             boolean contextTruncated, boolean commentsStripped, boolean selectivelyTruncated) {
            this.text = text;
            this.modeUsed = modeUsed;
            this.contextUnavailable = contextUnavailable;
            this.contextTruncated = contextTruncated;
            this.commentsStripped = commentsStripped;
            this.selectivelyTruncated = selectivelyTruncated;
        }

        public String getText() {
            return text;
        }

        public LlmSutContextMode getModeUsed() {
            return modeUsed;
        }

        public boolean isContextUnavailable() {
            return contextUnavailable;
        }

        public boolean isContextTruncated() {
            return contextTruncated;
        }

        public boolean isCommentsStripped() {
            return commentsStripped;
        }

        public boolean isSelectivelyTruncated() {
            return selectivelyTruncated;
        }
    }
}
