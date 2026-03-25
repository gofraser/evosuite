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
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small coordinator for DMoN detection in V1.
 */
public class DmonCoordinator {

    private static final DmonCoordinator INSTANCE = new DmonCoordinator();

    private final DmonNpeAnalyzer analyzer = new DmonNpeAnalyzer();
    private final Map<CacheKey, Optional<DmonPromotionPlan>> analysisCache = new ConcurrentHashMap<>();

    public static DmonCoordinator getInstance() {
        return INSTANCE;
    }

    public Optional<DmonPromotionPlan> analyzeConstructorFailure(ConstructorStatement statement, Throwable throwable) {
        if (!Properties.DMON_ENABLED || Properties.NO_RUNTIME_DEPENDENCY) {
            return Optional.empty();
        }
        if (!Properties.DMON_CACHE_ANALYSIS) {
            return analyzer.analyzeConstructorNpe(statement, throwable);
        }
        CacheKey key = CacheKey.from(statement, throwable);
        return analysisCache.computeIfAbsent(key, k -> analyzer.analyzeConstructorNpe(statement, throwable));
    }

    private static final class CacheKey {
        private final String owner;
        private final String message;
        private final String topFrame;

        private CacheKey(String owner, String message, String topFrame) {
            this.owner = owner == null ? "" : owner;
            this.message = message == null ? "" : message;
            this.topFrame = topFrame == null ? "" : topFrame;
        }

        private static CacheKey from(ConstructorStatement statement, Throwable throwable) {
            String owner = statement == null ? "" : statement.getDeclaringClassName();
            String message = throwable == null ? "" : String.valueOf(throwable.getMessage());
            String topFrame = "";
            if (throwable != null && throwable.getStackTrace() != null && throwable.getStackTrace().length > 0) {
                StackTraceElement first = throwable.getStackTrace()[0];
                topFrame = first.getClassName() + "#" + first.getMethodName() + ":" + first.getLineNumber();
            }
            return new CacheKey(owner, message, topFrame);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CacheKey)) {
                return false;
            }
            CacheKey other = (CacheKey) o;
            return owner.equals(other.owner)
                    && message.equals(other.message)
                    && topFrame.equals(other.topFrame);
        }

        @Override
        public int hashCode() {
            int result = owner.hashCode();
            result = 31 * result + message.hashCode();
            result = 31 * result + topFrame.hashCode();
            return result;
        }
    }
}
