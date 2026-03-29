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

public final class DmonFailureSite implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String ownerClass;
    private final String methodName;
    private final int lineNumber;
    private final DmonFailureSiteKind kind;

    /**
     * Create a new DmonFailureSite.
     *
     * @param ownerClass The class where the failure occurred.
     * @param methodName The method where the failure occurred.
     * @param lineNumber The line number where the failure occurred.
     * @param kind The kind of failure.
     */
    public DmonFailureSite(String ownerClass,
                           String methodName,
                           int lineNumber,
                           DmonFailureSiteKind kind) {
        this.ownerClass = ownerClass;
        this.methodName = methodName;
        this.lineNumber = lineNumber;
        this.kind = kind;
    }

    public String getOwnerClass() {
        return ownerClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public DmonFailureSiteKind getKind() {
        return kind;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DmonFailureSite)) {
            return false;
        }
        DmonFailureSite that = (DmonFailureSite) o;
        return lineNumber == that.lineNumber
                && Objects.equals(ownerClass, that.ownerClass)
                && Objects.equals(methodName, that.methodName)
                && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerClass, methodName, lineNumber, kind);
    }
}

