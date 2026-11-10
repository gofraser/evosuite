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
package org.evosuite.testcase.dmon;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * Lightweight promotion plan captured during execution.
 * V1 stores only analysis metadata; setup-statement generation is added incrementally.
 */
public final class DmonPromotionPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    private final DmonFailureSite failureSite;
    private final String nullExpression;
    private final String ownerToken;
    private final String memberToken;
    private final String inferredMissingTypeName;

    /**
     * Create a new DmonPromotionPlan.
     *
     * @param failureSite The site where the failure occurred.
     * @param nullExpression The expression that was null.
     * @param ownerToken The owner token.
     * @param memberToken The member token.
     * @param inferredMissingTypeName The inferred missing type name.
     */
    public DmonPromotionPlan(DmonFailureSite failureSite,
                             Optional<String> nullExpression,
                             Optional<String> ownerToken,
                             Optional<String> memberToken,
                             Optional<String> inferredMissingTypeName) {
        this.failureSite = failureSite;
        this.nullExpression = nullExpression == null ? null : nullExpression.orElse(null);
        this.ownerToken = ownerToken == null ? null : ownerToken.orElse(null);
        this.memberToken = memberToken == null ? null : memberToken.orElse(null);
        this.inferredMissingTypeName = inferredMissingTypeName == null ? null : inferredMissingTypeName.orElse(null);
    }

    public DmonFailureSite getFailureSite() {
        return failureSite;
    }

    public Optional<String> getNullExpression() {
        return Optional.ofNullable(nullExpression);
    }

    public Optional<String> getOwnerToken() {
        return Optional.ofNullable(ownerToken);
    }

    public Optional<String> getMemberToken() {
        return Optional.ofNullable(memberToken);
    }

    public Optional<String> getInferredMissingTypeName() {
        return Optional.ofNullable(inferredMissingTypeName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DmonPromotionPlan)) {
            return false;
        }
        DmonPromotionPlan that = (DmonPromotionPlan) o;
        return Objects.equals(failureSite, that.failureSite)
                && Objects.equals(nullExpression, that.nullExpression)
                && Objects.equals(ownerToken, that.ownerToken)
                && Objects.equals(memberToken, that.memberToken)
                && Objects.equals(inferredMissingTypeName, that.inferredMissingTypeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(failureSite, nullExpression, ownerToken, memberToken, inferredMissingTypeName);
    }
}

