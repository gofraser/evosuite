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
package org.evosuite.rmi;


import org.evosuite.Properties;
import org.evosuite.rmi.service.ClientNodeLocal;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.rmi.service.MasterNodeLocal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ServicesTest {

    private int currentPort;

    @BeforeEach
    public void init() {
        currentPort = Properties.PROCESS_COMMUNICATION_PORT;
    }

    @AfterEach
    public void tearDown() {
        Properties.PROCESS_COMMUNICATION_PORT = currentPort;
    }

    @Test
    public void testMasterClientCommunication() throws Exception {
        MasterServices master = new MasterServices();
        master.startRegistry();
        master.registerServices();

        Properties.PROCESS_COMMUNICATION_PORT = master.getRegistryPort();

        ClientServices<?> clients = new ClientServices<>();
        clients.registerServices("ClientNode");

        ClientNodeLocal<?> clientNode = clients.getClientNode();
        clientNode.changeState(ClientState.STARTED);

        MasterNodeLocal masterNode = master.getMasterNode();
        String summary = masterNode.getSummaryOfClientStatuses();

        Assertions.assertNotNull(summary);
        Assertions.assertTrue(summary.contains(ClientState.STARTED.toString()), "summary=" + summary);
    }

}
