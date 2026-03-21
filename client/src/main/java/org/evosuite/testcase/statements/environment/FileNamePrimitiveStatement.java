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
package org.evosuite.testcase.statements.environment;

import org.evosuite.runtime.testdata.EvoSuiteFile;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.StringUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;


/**
 * This class is a wrapper for a string that represents a filename.
 *
 * @author fraser
 */
public class FileNamePrimitiveStatement extends EnvironmentDataStatement<EvoSuiteFile> {

    private static final long serialVersionUID = 4402006999670328128L;

    /**
     * Constructor.
     *
     * @param tc    a {@link org.evosuite.testcase.TestCase} object.
     * @param value a {@link org.evosuite.runtime.testdata.EvoSuiteFile} object.
     */
    public FileNamePrimitiveStatement(TestCase tc, EvoSuiteFile value) {
        super(tc, EvoSuiteFile.class, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTestCode(String varName) {
        String testCode = "";
        VariableReference retval = getReturnValue();
        Object value = getValue();

        if (value != null) {
            String escapedPath = StringUtil.getEscapedString(value.toString());
            testCode += ((Class<?>) retval.getType()).getSimpleName() + " "
                    + varName + " = new "
                    + ((Class<?>) retval.getType()).getSimpleName() + "(\""
                    + escapedPath + "\");\n";
        } else {
            testCode += ((Class<?>) retval.getType()).getSimpleName() + " "
                    + varName + " = null;\n";
        }
        return testCode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delta() {
        randomize();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void zero() {
        // there does not exist a zero value for files
    }

    @Override
    public boolean hasMoreThanOneValue() {
        return !getAlternativePaths().isEmpty();
    }

    @Override
    public boolean mutate(TestCase test, TestFactory factory) {
        List<String> alternativePaths = getAlternativePaths();
        if (alternativePaths.isEmpty()) {
            return false;
        }
        setValue(new EvoSuiteFile(Randomness.choice(alternativePaths)));
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void randomize() {
        String path = Randomness.choice(tc.getAccessedEnvironment().getViewOfAccessedFiles());
        if (path != null) {
            setValue(new EvoSuiteFile(path));
        }
        // When no files have been accessed yet, keep the current value.
        // The accessed files list is populated after test execution, so
        // meaningful paths will become available once the SUT has run.
    }

    private List<String> getAlternativePaths() {
        List<String> alternatives = new ArrayList<>();
        String currentPath = value != null ? value.toString() : null;

        for (String path : tc.getAccessedEnvironment().getViewOfAccessedFiles()) {
            if (path == null) {
                continue;
            }
            if (!path.equals(currentPath)) {
                alternatives.add(path);
            }
        }
        return alternatives;
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();
        oos.writeObject(value);
    }

    private void readObject(ObjectInputStream ois) throws ClassNotFoundException,
            IOException {
        ois.defaultReadObject();
        value = (EvoSuiteFile) ois.readObject();
    }
}
