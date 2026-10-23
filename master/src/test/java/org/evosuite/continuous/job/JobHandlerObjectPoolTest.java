/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.continuous.job;

import org.evosuite.Properties;
import org.evosuite.Properties.AvailableSchedule;
import org.evosuite.continuous.CtgConfiguration;
import org.evosuite.continuous.persistency.StorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JobHandlerObjectPoolTest {

    private final double defaultPoolProbability = Properties.P_OBJECT_POOL;

    @AfterEach
    public void reset() {
        Properties.P_OBJECT_POOL = defaultPoolProbability;
    }

    /** The p_object_pool arguments of the command line of a job; the last one is the one that applies. */
    private static List<String> poolProbabilityArguments(double global, Path tmp, boolean withPool)
            throws Exception {
        File pools = Files.createDirectories(tmp.resolve("pools")).toFile();
        Files.write(new File(pools, "Dep.pool").toPath(), new byte[]{1});
        StorageManager storage = mock(StorageManager.class);
        when(storage.getTmpPools()).thenReturn(pools);
        when(storage.getTmpLogs()).thenReturn(tmp.resolve("logs").toFile());
        when(storage.getTmpReports()).thenReturn(tmp.resolve("reports").toFile());
        when(storage.getTmpTests()).thenReturn(tmp.resolve("tests").toFile());
        when(storage.getTmpSeeds()).thenReturn(tmp.resolve("seeds").toFile());
        JobExecutor executor = mock(JobExecutor.class);
        when(executor.getStorage()).thenReturn(storage);
        when(executor.getProjectClassPath()).thenReturn(tmp.toString());
        when(executor.isScheduled("Dep")).thenReturn(true);
        Field configuration = JobExecutor.class.getDeclaredField("configuration");
        configuration.setAccessible(true);
        configuration.set(executor, new CtgConfiguration(1000, 1, 1, 1, false, AvailableSchedule.BUDGET, null));

        Properties.P_OBJECT_POOL = global;
        JobDefinition job = new JobDefinition(10, 1000, "Cut", 0,
                withPool ? Collections.singleton("Dep") : null, null);

        return new JobHandler(executor).getCommandString(job).stream()
                .filter(c -> c.startsWith("-Dp_object_pool="))
                .collect(Collectors.toList());
    }

    private static String applied(List<String> arguments) {
        return arguments.get(arguments.size() - 1);
    }

    @Test
    public void poolsWithoutAGlobalProbabilityUseOneHalf(@TempDir Path tmp) throws Exception {
        assertEquals("-Dp_object_pool=0.5", applied(poolProbabilityArguments(0.0, tmp, true)));
    }

    @Test
    public void poolsWithAGlobalProbabilityKeepIt(@TempDir Path tmp) throws Exception {
        assertEquals("-Dp_object_pool=0.2", applied(poolProbabilityArguments(0.2, tmp, true)));
    }

    @Test
    public void withoutPoolsTheGlobalProbabilityIsPassedOn(@TempDir Path tmp) throws Exception {
        assertEquals("-Dp_object_pool=0.2", applied(poolProbabilityArguments(0.2, tmp, false)));
    }
}
