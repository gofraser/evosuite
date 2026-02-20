/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.testcase;

import org.evosuite.testcase.variable.VariableReference;

/**
 * Configuration for variable resolution and creation.
 *
 * @author Gordon Fraser
 */
public final class VariableResolutionConfig {

    private final boolean allowNull;
    private final boolean canUseMocks;
    private final boolean canReuseExistingVariables;
    private final boolean excludeCalleeGenerators;
    private final VariableReference excludeVar;

    private VariableResolutionConfig(Builder builder) {
        this.allowNull = builder.allowNull;
        this.canUseMocks = builder.canUseMocks;
        this.canReuseExistingVariables = builder.canReuseExistingVariables;
        this.excludeCalleeGenerators = builder.excludeCalleeGenerators;
        this.excludeVar = builder.excludeVar;
    }

    public boolean isAllowNull() {
        return allowNull;
    }

    public boolean isCanUseMocks() {
        return canUseMocks;
    }

    public boolean isCanReuseExistingVariables() {
        return canReuseExistingVariables;
    }

    public boolean isExcludeCalleeGenerators() {
        return excludeCalleeGenerators;
    }

    public VariableReference getExcludeVar() {
        return excludeVar;
    }

    public static class Builder {
        private boolean allowNull = true;
        private boolean canUseMocks = true;
        private boolean canReuseExistingVariables = true;
        private boolean excludeCalleeGenerators = false;
        private VariableReference excludeVar = null;

        /**
         * Default constructor.
         */
        public Builder() {
        }

        /**
         * Copy constructor.
         *
         * @param other the other configuration to copy from
         */
        public Builder(VariableResolutionConfig other) {
            this.allowNull = other.allowNull;
            this.canUseMocks = other.canUseMocks;
            this.canReuseExistingVariables = other.canReuseExistingVariables;
            this.excludeCalleeGenerators = other.excludeCalleeGenerators;
            this.excludeVar = other.excludeVar;
        }

        public Builder withAllowNull(boolean allowNull) {
            this.allowNull = allowNull;
            return this;
        }

        public Builder withCanUseMocks(boolean canUseMocks) {
            this.canUseMocks = canUseMocks;
            return this;
        }

        public Builder withCanReuseExistingVariables(boolean canReuseExistingVariables) {
            this.canReuseExistingVariables = canReuseExistingVariables;
            return this;
        }

        public Builder withExcludeCalleeGenerators(boolean excludeCalleeGenerators) {
            this.excludeCalleeGenerators = excludeCalleeGenerators;
            return this;
        }

        public Builder withExcludeVar(VariableReference excludeVar) {
            this.excludeVar = excludeVar;
            return this;
        }

        public VariableResolutionConfig build() {
            return new VariableResolutionConfig(this);
        }
    }

    public static VariableResolutionConfig defaultConfig() {
        return new Builder().build();
    }

    public static VariableResolutionConfig noMocks() {
        return new Builder().withCanUseMocks(false).build();
    }
}
