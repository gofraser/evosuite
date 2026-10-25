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
package org.evosuite.runtime.mock.java.nio.channels;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.evosuite.runtime.EvoRunner;
import org.evosuite.runtime.agent.InstrumentingAgent;
import org.junit.runners.model.InitializationError;

/**
 * An {@link EvoRunner} that installs the instrumenting agent through Byte
 * Buddy's own agent jar first. {@code AgentLoader} attaches the packaged
 * runtime jar, which does not exist yet when surefire runs this module's
 * tests from a clean build.
 */
public class AgentInstallingEvoRunner extends EvoRunner {

    public AgentInstallingEvoRunner(Class<?> klass) throws InitializationError {
        super(installAgent(klass));
    }

    private static Class<?> installAgent(Class<?> klass) throws InitializationError {
        if (InstrumentingAgent.getInstrumentation() == null) {
            try {
                InstrumentingAgent.agentmain(null, ByteBuddyAgent.install());
            } catch (Exception e) {
                throw new InitializationError(e);
            }
        }
        return klass;
    }
}
