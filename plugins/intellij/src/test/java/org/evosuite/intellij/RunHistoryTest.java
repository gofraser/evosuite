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
package org.evosuite.intellij;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.xmlb.XmlSerializer;

import org.evosuite.ide.*;
import org.evosuite.intellij.run.*;
import org.evosuite.intellij.settings.*;
import org.evosuite.intellij.ui.*;

import java.nio.file.*;
import java.util.*;

public class RunHistoryTest extends LightJavaCodeInsightFixtureTestCase {
    public void testEffectiveConfigurationAndReplayPreserveOverridesWithoutCredentials() {
        var options = new GenerationOptions();
        options.llmProvider = "OPENAI";
        options.llmApproach = "SEARCH_ONLY";
        options.extraArguments =
                "-Dsearch_budget=19 -Dalgorithm=MOSA -Dllm_api_key=never-save-this -criterion"
                        + " BRANCH";
        options.pomProperties = Map.of("search_budget", "23", "llm_provider", "ANTHROPIC");
        options.pomExtraArguments = "-Dsearch_budget=31 -Dllm_max_tokens=900";
        var rows =
                EffectiveConfiguration.describe(
                        options, Map.of("searchBudget", "60"), "IDE defaults");
        var budget =
                rows.stream()
                        .filter(v -> v.property().equals("search_budget"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("31", budget.value());
        assertEquals("Maven additional arguments", budget.source());
        assertEquals(
                "NONE",
                rows.stream()
                        .filter(v -> v.property().equals("llm_provider"))
                        .findFirst()
                        .orElseThrow()
                        .value());
        var saved = EffectiveConfiguration.savedValues(options);
        assertFalse(saved.toString().contains("never-save-this"));
        var replay = SettingKeys.resolve(saved);
        var args =
                new EvoSuiteCommand(
                                "java",
                                "engine",
                                "demo.Target",
                                List.of(),
                                new java.io.File("."),
                                new java.io.File("tests"),
                                new java.io.File("reports"),
                                replay)
                        .toArguments(0);
        assertEquals(
                "-Dsearch_budget=31",
                args.stream()
                        .filter(a -> a.startsWith("-Dsearch_budget="))
                        .reduce((a, b) -> b)
                        .orElseThrow());
        assertTrue(args.contains("-Dalgorithm=MOSA"));
        assertTrue(args.contains("-Dllm_max_tokens=900"));
        assertTrue(args.contains("-criterion"));
        assertFalse(replay.usePomSettings);
        assertEquals("NONE", replay.effectiveLlmProvider());
    }

    public void testHistorySurvivesSerializationAndReopensPendingComparison() throws Exception {
        var history = new RunHistory(getProject());
        String run = history.start("CLASSES", "selection");
        int index =
                history.target(
                        run,
                        "module",
                        GenerationTarget.wholeClass("demo.Target"),
                        new GenerationOptions(),
                        Path.of("/tmp/Target_ESTest.java"));
        Path staging = Files.createTempDirectory("evosuite-history-test-");
        try {
            history.beginTarget(run, index, staging);
            history.line(run, index, "java -Drandom_seed=99 -Drandom_seed=-1234");
            history.progress(run, index, 50, 75);
            var destination = staging.resolve("accepted/Target_ESTest.java");
            history.comparisons(
                    run,
                    index,
                    List.of(SuiteComparison.pending(destination, "public class Target_ESTest {}")),
                    staging);
            var restored = new RunHistory(getProject());
            restored.loadState(
                    XmlSerializer.deserialize(
                            XmlSerializer.serialize(history.getState()), RunHistory.State.class));
            var record = restored.getState().runs.getFirst();
            var target = record.targets.getFirst();
            assertEquals("Interrupted", record.status);
            assertEquals("-1234", target.seed);
            assertEquals(75, target.coverage);
            var pairs = restored.comparisons(target);
            assertEquals(1, pairs.size());
            assertEquals(destination, pairs.getFirst().destination());
            assertFalse(Files.exists(destination));
            restored.cleanup(true);
            assertEquals(
                    "Pending comparisons must not be expired", 1, restored.getState().runs.size());
            restored.reviewed(record.id, 0);
            restored.cleanup(true);
            assertTrue(restored.getState().runs.isEmpty());
            assertFalse("Cleanup cannot delete unmanaged directories", Files.notExists(staging));
        } finally {
            GenerationRun.deleteTree(staging);
        }
    }

    public void testRunsPanelKeepsActionsInsideNarrowViewport() {
        var history = RunHistory.getInstance(getProject());
        String id = history.start("CLASSES", "demo run");
        var options = new GenerationOptions();
        options.randomSeed = "1234";
        int target =
                history.target(
                        id,
                        "app",
                        GenerationTarget.wholeClass("demo.Calculator"),
                        options,
                        Path.of("/tmp/Calculator_ESTest.java"));
        history.progress(id, target, 100, 85);
        history.outcome(id, target, "Written / updated", "Generated suite is ready", 4);
        history.finish(id, "Completed", "Finished");
        var panel = new RunResultsPanel(getProject());
        try {
            panel.setSize(400, 600);
            layout(panel);
            checkButtons(panel, 400);
            var image =
                    new java.awt.image.BufferedImage(
                            400, 600, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                panel.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            try {
                javax.imageio.ImageIO.write(
                        image,
                        "png",
                        Path.of(System.getProperty("java.io.tmpdir"), "evosuite-runs.png")
                                .toFile());
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel);
        }
    }

    private static void layout(java.awt.Container c) {
        c.doLayout();
        for (var child : c.getComponents())
            if (child instanceof java.awt.Container parent) layout(parent);
    }

    private static void checkButtons(java.awt.Container c, int width) {
        for (var child : c.getComponents()) {
            if (child instanceof javax.swing.JButton button && button.isVisible())
                assertTrue(button.getText(), button.getX() + button.getWidth() <= c.getWidth());
            if (child instanceof java.awt.Container parent) checkButtons(parent, width);
        }
    }
}
