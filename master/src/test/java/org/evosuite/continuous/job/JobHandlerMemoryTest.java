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
package org.evosuite.continuous.job;

import org.evosuite.Properties;
import org.evosuite.Properties.AvailableSchedule;
import org.evosuite.continuous.CtgConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JobHandlerMemoryTest {

    @AfterEach
    public void reset() {
        Properties.CTG_MASTER_MEMORY = null;
    }

    @Test
    public void smallJobsKeepTheMinimumMasterHeap() {
        assertEquals(250, JobHandler.getMasterMemoryInMB(800));
    }

    @Test
    public void largeJobsGiveTheMasterAQuarter() {
        assertEquals(1000, JobHandler.getMasterMemoryInMB(4000));
    }

    @Test
    public void explicitMasterMemoryWins() {
        Properties.CTG_MASTER_MEMORY = 3000;
        assertEquals(3000, JobHandler.getMasterMemoryInMB(8000));
    }

    @Test
    public void masterMemoryMustLeaveRoomForTheClient() {
        Properties.CTG_MASTER_MEMORY = 900;
        assertThrows(IllegalArgumentException.class,
                () -> new CtgConfiguration(1000, 1, 1, 1, false, AvailableSchedule.BUDGET, null));
    }
}
