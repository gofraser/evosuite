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
package org.evosuite.testcase.statements.environment;

import org.evosuite.runtime.testdata.EvoSuiteAddress;
import org.evosuite.runtime.testdata.EvoSuiteRemoteAddress;
import org.evosuite.runtime.vnet.EndPointInfo;
import org.evosuite.seeding.ConstantPool;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.StringUtil;

/**
 * Primitive statement for remote address.
 *
 * @author arcuri
 */
public class RemoteAddressPrimitiveStatement extends EnvironmentDataStatement<EvoSuiteRemoteAddress> {

    private static final long serialVersionUID = -4863601663573415059L;

    /**
     * Constructor.
     *
     * @param tc the test case context.
     */
    public RemoteAddressPrimitiveStatement(TestCase tc) {
        this(tc, null);
        randomize();
    }

    /**
     * Constructor.
     *
     * @param tc    the test case context.
     * @param value the remote address value.
     */
    public RemoteAddressPrimitiveStatement(TestCase tc, EvoSuiteRemoteAddress value) {
        super(tc, EvoSuiteRemoteAddress.class, value);
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
            String escapedAddress = StringUtil.getEscapedString(((EvoSuiteAddress) value).getHost());
            int port = ((EvoSuiteAddress) value).getPort();

            testCode += ((Class<?>) retval.getType()).getSimpleName() + " "
                    + varName + " = new "
                    + ((Class<?>) retval.getType()).getSimpleName() + "(\""
                    + escapedAddress + "\", " + port + ");\n";
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

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void randomize() {
        EvoSuiteRemoteAddress addr;

        double threshold = 0.8; //TODO parameter
        boolean accessed = Randomness.nextDouble() <= threshold;

        if (accessed && !tc.getAccessedEnvironment().getViewOfRemoteContactedPorts().isEmpty()) {
            // use an address that the SUT tried to contact
            EndPointInfo info = Randomness.choice(tc.getAccessedEnvironment().getViewOfRemoteContactedPorts());
            String host = info.getHost();
            int port = info.getPort();//TODO check why it can be a 0 here
            port = getPort(port);
            addr = new EvoSuiteRemoteAddress(host, port);
        } else {
            /*
                make up an address based on string/int constants.
                this is needed to handle the cases when the SUT get
                an incoming message, and then check its remote address.

                TODO: here we could validate if host/port values are
                indeed valid. However, as this is kind of special case,
                and likely not so common, it doesn't have high priority.
             */
            ConstantPool constantPool = ConstantPoolManager.getInstance().getConstantPool();
            String host = constantPool.getRandomString();
            int port = constantPool.getRandomInt();
            port = getPort(port);
            addr = new EvoSuiteRemoteAddress(host, port);
        }

        setValue(addr);
    }

    /**
     * Ensures the port is within valid range.
     *
     * @param port the port to check.
     * @return a valid port number.
     */
    private int getPort(int port) {
        if (port <= 0 || port > 65535) {
            port = 12345; //just a valid port number
        }
        return port;
    }

}
