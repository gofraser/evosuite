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
        String cacheKey = mode.name() + ":" + (className == null ? "" : className);
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
            String text = truncate(context.get());
            return new ContextResult(text, mode, false);
        }

        // Primary failed — apply fallback policy
        if (mode == LlmSutContextMode.SIGNATURE_ONLY) {
            if (Properties.LLM_CONTEXT_FALLBACK_ENABLED) {
                return new ContextResult("", mode, false);
            }
            return new ContextResult("", mode, true);
        }

        if (Properties.LLM_CONTEXT_FALLBACK_ENABLED) {
            logger.debug("Falling back from {} to SIGNATURE_ONLY for {}", mode, className);
            Optional<String> fallbackContext;
            try {
                fallbackContext = signatureProvider.getContext(className, cluster);
            } catch (Exception e) {
                logger.debug("Signature fallback also failed for {}: {}", className, e.getMessage());
                fallbackContext = Optional.empty();
            }
            String text = fallbackContext.isPresent() ? truncate(fallbackContext.get()) : "";
            boolean unavailable = !fallbackContext.isPresent();
            return new ContextResult(text, LlmSutContextMode.SIGNATURE_ONLY, unavailable);
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

    private String truncate(String text) {
        int maxChars = Properties.LLM_CONTEXT_MAX_CHARS;
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... (truncated)";
    }

    /**
     * Result of context extraction, including trace metadata.
     */
    public static class ContextResult {
        private final String text;
        private final LlmSutContextMode modeUsed;
        private final boolean contextUnavailable;

        /** Constructs a context result with text, mode, and availability flag. */
        public ContextResult(String text, LlmSutContextMode modeUsed, boolean contextUnavailable) {
            this.text = text;
            this.modeUsed = modeUsed;
            this.contextUnavailable = contextUnavailable;
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
    }
}
