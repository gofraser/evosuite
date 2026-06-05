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
package org.evosuite.llm.search;

/**
 * Tuning knobs that gate problem-card emission. Collected here so the per-card
 * builders can take a single configuration object instead of reaching into the
 * extractor's static constants. Mutable in tests via {@link Builder}; the
 * production extractor uses {@link #defaults()}.
 *
 * <p>Stage D of plan 2's §2.5 split. Stages B and C (observer / builder
 * extraction) will wire builders to receive a {@code ProblemCardThresholds}
 * instance instead of the historical constants.
 */
public final class ProblemCardThresholds {

    private final int minAttemptsForExceptionBarrier;
    private final int minAttemptsForTypeBarrier;
    private final int minPolarityGapOppositeObservations;
    private final int minLowSteerabilityBranchExecutions;
    private final int minAttemptsForStateDiversification;
    private final double minFailureRateForPracticalAcquisitionBarrier;
    private final double minFailureRateForExceptionBarrier;
    private final double minFailureRateForStateSetupBarrier;
    private final double minContextShareForStateDiversification;
    private final double minSuccessShareForStateDiversification;

    private ProblemCardThresholds(Builder b) {
        this.minAttemptsForExceptionBarrier = b.minAttemptsForExceptionBarrier;
        this.minAttemptsForTypeBarrier = b.minAttemptsForTypeBarrier;
        this.minPolarityGapOppositeObservations = b.minPolarityGapOppositeObservations;
        this.minLowSteerabilityBranchExecutions = b.minLowSteerabilityBranchExecutions;
        this.minAttemptsForStateDiversification = b.minAttemptsForStateDiversification;
        this.minFailureRateForPracticalAcquisitionBarrier = b.minFailureRateForPracticalAcquisitionBarrier;
        this.minFailureRateForExceptionBarrier = b.minFailureRateForExceptionBarrier;
        this.minFailureRateForStateSetupBarrier = b.minFailureRateForStateSetupBarrier;
        this.minContextShareForStateDiversification = b.minContextShareForStateDiversification;
        this.minSuccessShareForStateDiversification = b.minSuccessShareForStateDiversification;
    }

    public static ProblemCardThresholds defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int minAttemptsForExceptionBarrier() {
        return minAttemptsForExceptionBarrier;
    }

    public int minAttemptsForTypeBarrier() {
        return minAttemptsForTypeBarrier;
    }

    public int minPolarityGapOppositeObservations() {
        return minPolarityGapOppositeObservations;
    }

    public int minLowSteerabilityBranchExecutions() {
        return minLowSteerabilityBranchExecutions;
    }

    public int minAttemptsForStateDiversification() {
        return minAttemptsForStateDiversification;
    }

    public double minFailureRateForPracticalAcquisitionBarrier() {
        return minFailureRateForPracticalAcquisitionBarrier;
    }

    public double minFailureRateForExceptionBarrier() {
        return minFailureRateForExceptionBarrier;
    }

    public double minFailureRateForStateSetupBarrier() {
        return minFailureRateForStateSetupBarrier;
    }

    public double minContextShareForStateDiversification() {
        return minContextShareForStateDiversification;
    }

    public double minSuccessShareForStateDiversification() {
        return minSuccessShareForStateDiversification;
    }

    public static final class Builder {
        private int minAttemptsForExceptionBarrier = 2;
        private int minAttemptsForTypeBarrier = 2;
        private int minPolarityGapOppositeObservations = 2;
        private int minLowSteerabilityBranchExecutions = 4;
        private int minAttemptsForStateDiversification = 4;
        private double minFailureRateForPracticalAcquisitionBarrier = 0.6;
        private double minFailureRateForExceptionBarrier = 2.0 / 3.0;
        private double minFailureRateForStateSetupBarrier = 2.0 / 3.0;
        private double minContextShareForStateDiversification = 0.75;
        private double minSuccessShareForStateDiversification = 0.8;

        public Builder minAttemptsForExceptionBarrier(int v) {
            this.minAttemptsForExceptionBarrier = v;
            return this;
        }

        public Builder minAttemptsForTypeBarrier(int v) {
            this.minAttemptsForTypeBarrier = v;
            return this;
        }

        public Builder minPolarityGapOppositeObservations(int v) {
            this.minPolarityGapOppositeObservations = v;
            return this;
        }

        public Builder minLowSteerabilityBranchExecutions(int v) {
            this.minLowSteerabilityBranchExecutions = v;
            return this;
        }

        public Builder minAttemptsForStateDiversification(int v) {
            this.minAttemptsForStateDiversification = v;
            return this;
        }

        public Builder minFailureRateForPracticalAcquisitionBarrier(double v) {
            this.minFailureRateForPracticalAcquisitionBarrier = v;
            return this;
        }

        public Builder minFailureRateForExceptionBarrier(double v) {
            this.minFailureRateForExceptionBarrier = v;
            return this;
        }

        public Builder minFailureRateForStateSetupBarrier(double v) {
            this.minFailureRateForStateSetupBarrier = v;
            return this;
        }

        public Builder minContextShareForStateDiversification(double v) {
            this.minContextShareForStateDiversification = v;
            return this;
        }

        public Builder minSuccessShareForStateDiversification(double v) {
            this.minSuccessShareForStateDiversification = v;
            return this;
        }

        public ProblemCardThresholds build() {
            return new ProblemCardThresholds(this);
        }
    }
}
