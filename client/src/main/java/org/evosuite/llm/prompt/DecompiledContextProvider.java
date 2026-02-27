/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.prompt;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.evosuite.Properties;
import org.evosuite.setup.TestCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Provides decompiled source context for the CUT using CFR decompiler API.
 * Bounded by {@code LLM_DECOMPILER_TIMEOUT_SECONDS}. Gracefully returns empty on any failure.
 */
public class DecompiledContextProvider implements SutContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(DecompiledContextProvider.class);

    @Override
    public Optional<String> getContext(String className, TestCluster cluster) {
        if (className == null || className.trim().isEmpty()) {
            return Optional.empty();
        }

        int timeoutSeconds = Math.max(1, Properties.LLM_DECOMPILER_TIMEOUT_SECONDS);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> future = executor.submit(new DecompileTask(className));
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.debug("CFR decompiler timed out after {}s for {}", timeoutSeconds, className);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            logger.debug("CFR decompilation failed for {}: {}", className, e.getMessage());
            return Optional.empty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Override
    public String modeLabel() {
        return "Decompiled source";
    }

    private static class DecompileTask implements Callable<Optional<String>> {
        private final String className;

        DecompileTask(String className) {
            this.className = className;
        }

        @Override
        public Optional<String> call() {
            try {
                String resourcePath = className.replace('.', '/') + ".class";
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) {
                    cl = DecompiledContextProvider.class.getClassLoader();
                }
                java.net.URL classUrl = cl.getResource(resourcePath);
                if (classUrl == null) {
                    return Optional.empty();
                }

                String classFilePath = classUrl.toURI().getPath();
                if (classFilePath == null) {
                    return Optional.empty();
                }

                CollectingSinkFactory sinkFactory = new CollectingSinkFactory();
                Map<String, String> options = new HashMap<>();
                options.put("showversion", "false");
                options.put("silent", "true");

                CfrDriver driver = new CfrDriver.Builder()
                        .withOptions(options)
                        .withOutputSink(sinkFactory)
                        .build();

                driver.analyse(Collections.singletonList(classFilePath));
                String result = sinkFactory.getOutput();
                if (result.trim().isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(result);
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }

    /**
     * CFR OutputSinkFactory that collects decompiled Java output into a StringBuilder.
     */
    static class CollectingSinkFactory implements OutputSinkFactory {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
            return Arrays.asList(SinkClass.STRING);
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
            // Discard non-JAVA output (progress, exceptions, etc.)
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
    }
}
