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

import org.evosuite.ga.operators.mutation.MutationHistoryEntry;
import org.evosuite.testcase.statements.Statement;

import java.io.Serializable;

public class TestMutationHistoryEntry implements MutationHistoryEntry, Serializable {

    private static final long serialVersionUID = -4278409687247714553L;

    public enum TestMutation {
        CHANGE, INSERTION, DELETION
    }

    protected TestMutation mutationType;

    protected Statement statement;

    public String whatwasit;

    /**
     * Creates a new mutation history entry.
     *
     * @param type      the type of mutation
     * @param statement the statement affected by the mutation
     */
    public TestMutationHistoryEntry(TestMutation type, Statement statement) {
        this.mutationType = type;
        this.statement = statement;
        this.whatwasit = statement.getCode() + " at position " + statement.getPosition();
    }

    /**
     * Creates a new mutation history entry for a deletion.
     *
     * @param type the type of mutation (usually DELETION)
     */
    public TestMutationHistoryEntry(TestMutation type) {
        this.mutationType = type;
        this.statement = null;
        this.whatwasit = "Deleted some statement";
    }

    public Statement getStatement() {
        return statement;
    }

    /**
     * Returns the type of mutation.
     *
     * @return the mutation type
     */
    public TestMutation getMutationType() {
        return mutationType;
    }

    /**
     * Creates a copy of this mutation history entry for the given test case.
     *
     * @param newTest the test case to clone to
     * @return the cloned mutation history entry
     */
    public TestMutationHistoryEntry clone(TestCase newTest) {
        if (statement == null) {
            return new TestMutationHistoryEntry(mutationType);
        }

        return new TestMutationHistoryEntry(mutationType,
                newTest.getStatement(statement.getPosition()));
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return mutationType + " at " + statement;
    }
}
