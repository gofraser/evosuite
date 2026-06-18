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

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.evosuite.Properties;
import org.evosuite.classpath.ResourceList;
import org.evosuite.setup.TestCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provides decompiled source context for the CUT using the CFR decompiler API.
 * Bounded by {@code LLM_DECOMPILER_TIMEOUT_SECONDS}. Gracefully returns empty on any failure.
 *
 * <p>Decompilation is performed entirely <em>in memory</em>: the class bytes are
 * read once and handed to CFR through a {@link ClassFileSource}. This deliberately
 * avoids materializing a temporary {@code .class} file, because under EvoSuite's
 * runtime sandbox (a {@code SecurityManager} on JDKs that still ship one) the
 * temp-file write is denied, which previously made the decompiler fail silently
 * for every class and degrade to {@code SIGNATURE_ONLY}. The bytes are read from
 * the project classpath via {@link ResourceList} (the original, un-instrumented
 * class), falling back to the context class loader's resource stream.
 */
public class DecompiledContextProvider implements SutContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(DecompiledContextProvider.class);

    /** Shared single-thread executor for decompilation tasks. Daemon thread so JVM exit is not blocked. */
    private static final ExecutorService DECOMPILER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cfr-decompiler");
        t.setDaemon(true);
        return t;
    });

    @Override
    public Optional<String> getContext(String className, TestCluster cluster) {
        if (className == null || className.trim().isEmpty()) {
            return Optional.empty();
        }

        int timeoutSeconds = Math.max(1, Properties.LLM_DECOMPILER_TIMEOUT_SECONDS);
        try {
            Future<Optional<String>> future = DECOMPILER_EXECUTOR.submit(new DecompileTask(className));
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("CFR decompiler timed out after {}s for {}; falling back", timeoutSeconds, className);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            logger.warn("CFR decompilation failed for {}: {}", className, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public String modeLabel() {
        return "Decompiled source";
    }

    /** Reads the (original) class bytes for {@code className}, or {@code null} if unavailable. */
    private static byte[] readClassBytes(String className) {
        // Prefer the original bytes from the project classpath: this avoids the
        // instrumented/mock-redirected bytecode that the SUT class loader may
        // otherwise serve as a resource, which CFR decompiles poorly.
        InputStream is = null;
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = DecompiledContextProvider.class.getClassLoader();
            }
            try {
                is = ResourceList.getInstance(cl).getClassAsStream(className);
            } catch (Throwable t) {
                logger.debug("ResourceList lookup failed for {}: {}", className, t.getMessage());
                is = null;
            }
            if (is == null) {
                is = cl.getResourceAsStream(className.replace('.', '/') + ".class");
            }
            if (is == null) {
                logger.warn("Class file not found on classpath for decompilation: {}", className);
                return null;
            }
            return readAllBytes(is);
        } catch (IOException e) {
            logger.warn("Failed to read class bytes for {}: {}", className, e.getMessage());
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = is.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static class DecompileTask implements Callable<Optional<String>> {
        private final String className;

        DecompileTask(String className) {
            this.className = className;
        }

        @Override
        public Optional<String> call() {
            byte[] classBytes = readClassBytes(className);
            if (classBytes == null) {
                return Optional.empty();
            }

            String classPath = className.replace('.', '/') + ".class";
            try {
                CollectingSinkFactory sinkFactory = new CollectingSinkFactory();
                Map<String, String> options = new HashMap<>();
                options.put("showversion", "false");
                options.put("silent", "true");

                CfrDriver driver = new CfrDriver.Builder()
                        .withOptions(options)
                        .withClassFileSource(new InMemoryClassFileSource(classPath, classBytes))
                        .withOutputSink(sinkFactory)
                        .build();

                // CFR resolves the analysed path through the ClassFileSource, so no
                // file is ever written to disk.
                driver.analyse(Collections.singletonList(classPath));
                String result = sinkFactory.getOutput();
                if (result.trim().isEmpty()) {
                    logger.warn("CFR produced empty output for {}{}", className,
                            sinkFactory.getErrors().isEmpty() ? "" : " (" + sinkFactory.getErrors() + ")");
                    return Optional.empty();
                }
                return Optional.of(result);
            } catch (Exception e) {
                logger.warn("Unexpected error decompiling {}: {}", className, e.toString());
                return Optional.empty();
            }
        }
    }

    /**
     * In-memory {@link ClassFileSource} that serves a single class's bytes to CFR.
     * Requests for any other path (e.g. dependencies) throw {@link IOException},
     * which CFR treats as an unresolved reference and decompiles around.
     */
    static class InMemoryClassFileSource implements ClassFileSource {
        private final String path;
        private final byte[] bytes;

        InMemoryClassFileSource(String path, byte[] bytes) {
            this.path = path;
            this.bytes = bytes;
        }

        @Override
        public void informAnalysisRelativePathDetail(String usePath, String specPath) {
            // No-op: single-class, in-memory source.
        }

        @Override
        public Collection<String> addJar(String jarPath) {
            return Collections.emptyList();
        }

        @Override
        public String getPossiblyRenamedPath(String path) {
            return path;
        }

        @Override
        public Pair<byte[], String> getClassFileContent(String inputPath) throws IOException {
            if (path.equals(inputPath)) {
                return Pair.make(bytes, inputPath);
            }
            throw new IOException("Class not available in in-memory source: " + inputPath);
        }
    }

    /**
     * CFR OutputSinkFactory that collects decompiled Java output (and any reported
     * exceptions, for diagnostics) into string buffers.
     */
    static class CollectingSinkFactory implements OutputSinkFactory {

        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder errors = new StringBuilder();

        @Override
        public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
            return Arrays.asList(SinkClass.STRING, SinkClass.EXCEPTION_MESSAGE);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
            if (sinkType == SinkType.JAVA) {
                return (Sink<T>) new Sink<String>() {
                    @Override
                    public void write(String s) {
                        buffer.append(s);
                    }
                };
            }
            if (sinkType == SinkType.EXCEPTION) {
                return new Sink<T>() {
                    @Override
                    public void write(T t) {
                        if (errors.length() < 2000 && t != null) {
                            errors.append(t).append(' ');
                        }
                    }
                };
            }
            // Discard other output (progress, summaries, etc.)
            return new Sink<T>() {
                @Override
                public void write(T t) {
                    // ignore
                }
            };
        }

        String getOutput() {
            return buffer.toString();
        }

        String getErrors() {
            return errors.toString().trim();
        }
    }
}
