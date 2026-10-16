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
package org.evosuite.testcase.statements;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testparser.TestParser;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EntityWithParametersStatement#mutateParameter} may add a copy of a parameter's defining
 * statement as a new candidate value. For a field read such as {@code holder0.value} the
 * position of the parameter is that of the first assignment to the field, which can come after
 * the call; copying that assignment in front of the call made it read a value defined later
 * ({@code holder0.value = int0; Holder.read(holder0.value); int int0 = 5; holder0.value = int0;}).
 * A crossover cut between the two then left the copied assignment with an orphaned value.
 */
public class MutateParameterFieldCopyTest {

    @SuppressWarnings("unused")
    public static class Holder {
        public int value;

        public static int read(int v) {
            return v;
        }
    }

    @Test
    public void mutatingAFieldReadParameterDoesNotCopyALaterAssignment() throws Exception {
        for (long seed = 0; seed < 20; seed++) {
            DefaultTestCase test = new DefaultTestCase();
            VariableReference holder = test.addStatement(new ConstructorStatement(test,
                    new GenericConstructor(Holder.class.getConstructor(), Holder.class),
                    Collections.emptyList()));
            FieldReference fieldRead = new FieldReference(test,
                    new GenericField(Holder.class.getField("value"), Holder.class), holder);
            MethodStatement call = new MethodStatement(test,
                    new GenericMethod(Holder.class.getMethod("read", int.class), Holder.class),
                    null, Collections.singletonList(fieldRead));
            test.addStatement(call);
            VariableReference value = test.addStatement(new IntPrimitiveStatement(test, 5));
            FieldReference fieldWrite = new FieldReference(test,
                    new GenericField(Holder.class.getField("value"), Holder.class), holder);
            test.addStatement(new AssignmentStatement(test, fieldWrite, value));

            Randomness.setSeed(seed);
            call.mutateParameter(test, 0);

            assertEquals(Collections.emptyList(), TestParser.findUnsafelyCopyableStatements(test),
                    "seed " + seed + ":\n" + test.toCode());
        }
    }
}
