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
package org.evosuite.llm;

import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregated runtime metrics for LLM usage.
 */
public class LlmStatistics {

    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong successfulCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final Map<LlmFeature, FeatureStats> perFeature = new ConcurrentHashMap<>();

    /** Records a successful LLM call for the given feature, updating token and latency totals. */
    public void recordCall(LlmFeature feature, int inputTokenCount, int outputTokenCount, long latencyMs) {
        totalCalls.incrementAndGet();
        successfulCalls.incrementAndGet();
        inputTokens.addAndGet(Math.max(0, inputTokenCount));
        outputTokens.addAndGet(Math.max(0, outputTokenCount));
        totalLatencyMs.addAndGet(Math.max(0L, latencyMs));
        perFeature.computeIfAbsent(feature, ignored -> new FeatureStats())
                .recordSuccess(inputTokenCount, outputTokenCount, latencyMs);
    }

    /** Records a failed LLM call for the given feature. */
    public void recordFailure(LlmFeature feature) {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        perFeature.computeIfAbsent(feature, ignored -> new FeatureStats()).recordFailure();
    }

    public long getTotalCalls() {
        return totalCalls.get();
    }

    public long getSuccessfulCalls() {
        return successfulCalls.get();
    }

    public long getFailedCalls() {
        return failedCalls.get();
    }

    public long getInputTokens() {
        return inputTokens.get();
    }

    public long getOutputTokens() {
        return outputTokens.get();
    }

    public long getTotalLatencyMs() {
        return totalLatencyMs.get();
    }

    /** Returns per-feature statistics snapshots. */
    public Map<LlmFeature, FeatureSnapshot> getFeatureSnapshots() {
        Map<LlmFeature, FeatureSnapshot> snapshots = new EnumMap<>(LlmFeature.class);
        for (Map.Entry<LlmFeature, FeatureStats> entry : perFeature.entrySet()) {
            snapshots.put(entry.getKey(), entry.getValue().snapshot());
        }
        return snapshots;
    }

    /** Publishes collected LLM statistics as EvoSuite runtime variables. */
    public void publishRuntimeVariables() {
        ClientServices.track(RuntimeVariable.LLM_Calls, getTotalCalls());
        ClientServices.track(RuntimeVariable.LLM_Calls_Succeeded, getSuccessfulCalls());
        ClientServices.track(RuntimeVariable.LLM_Calls_Failed, getFailedCalls());
        ClientServices.track(RuntimeVariable.LLM_Input_Tokens, getInputTokens());
        ClientServices.track(RuntimeVariable.LLM_Output_Tokens, getOutputTokens());
        ClientServices.track(RuntimeVariable.LLM_Latency_Millis, getTotalLatencyMs());
    }

    private static final class FeatureStats {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final AtomicLong latency = new AtomicLong();

        void recordSuccess(int in, int out, long latencyMs) {
            calls.incrementAndGet();
            input.addAndGet(Math.max(0, in));
            output.addAndGet(Math.max(0, out));
            latency.addAndGet(Math.max(0L, latencyMs));
        }

        void recordFailure() {
            calls.incrementAndGet();
            failures.incrementAndGet();
        }

        /** Returns an immutable snapshot of this feature's statistics. */
        FeatureSnapshot snapshot() {
            return new FeatureSnapshot(calls.get(), failures.get(), input.get(), output.get(), latency.get());
        }
    }

    public static final class FeatureSnapshot {
        private final long calls;
        private final long failures;
        private final long inputTokens;
        private final long outputTokens;
        private final long latencyMs;

        /** Constructs a snapshot with per-feature call, failure, token, and latency counts. */
        public FeatureSnapshot(long calls, long failures, long inputTokens, long outputTokens, long latencyMs) {
            this.calls = calls;
            this.failures = failures;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.latencyMs = latencyMs;
        }

        public long getCalls() {
            return calls;
        }

        public long getFailures() {
            return failures;
        }

        public long getInputTokens() {
            return inputTokens;
        }

        public long getOutputTokens() {
            return outputTokens;
        }

        public long getLatencyMs() {
            return latencyMs;
        }
    }
}
