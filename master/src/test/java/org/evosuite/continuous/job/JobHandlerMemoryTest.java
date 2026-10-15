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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    public void keptPoolsAreOnlyUsedForClassesNotTestedAgain(@TempDir Path tmp) throws Exception {
        File current = Files.createDirectories(tmp.resolve("current")).toFile();
        File kept = Files.createDirectories(tmp.resolve("kept")).toFile();
        Files.write(new File(kept, "B.pool").toPath(), new byte[]{1});

        // B did not change and has no job: its pool from an earlier run is still right
        assertEquals(new File(kept, "B.pool"), JobHandler.findPool(current, kept, "B.pool", false));
        // B changed and its job has not written a pool yet: the kept one is of the old B
        assertNull(JobHandler.findPool(current, kept, "B.pool", true));

        Files.write(new File(current, "B.pool").toPath(), new byte[]{2});
        assertEquals(new File(current, "B.pool"), JobHandler.findPool(current, kept, "B.pool", true));
        assertNull(JobHandler.findPool(current, kept, "C.pool", false));
    }
}
