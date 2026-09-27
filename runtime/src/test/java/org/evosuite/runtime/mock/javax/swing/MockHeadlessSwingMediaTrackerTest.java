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
package org.evosuite.runtime.mock.javax.swing;

import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.swing.JPanel;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Toolkit;
import java.awt.image.ImageConsumer;
import java.awt.image.ImageProducer;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 58_fps370's {@code teder.TederData}/{@code teder.TederPanel} constructors do
 * {@code Toolkit.getImage(name)} followed by {@code new MediaTracker(this).waitForID(0)}. On the
 * SF110 cluster that wait genuinely never returns inside the search budget, and the class reaches
 * only 2-16 generations in 120s instead of the ~1,500-2,300 a normal class gets: the entire budget
 * is spent blocked in AWT's image-loading machinery, which has nothing to do with progress of the
 * search. {@code Toolkit.getImage} itself never blocks - it hands back an {@code Image} immediately
 * and starts the fetch on a background thread - so the fix lives entirely in {@link MediaTracker}.
 */
class MockHeadlessSwingMediaTrackerTest {

    @BeforeEach
    void forceHeadlessMocking() {
        // GraphicsEnvironment.isHeadless() caches its decision on first read, which on a normal
        // dev/CI machine with a display resolves to false long before this test runs. GuiSupport's
        // reflective flip is what production uses to force the JVM into the SF110-cluster-like
        // headless state instrumentation actually targets, independent of that cache.
        GuiSupport.setHeadless();
        MockFramework.enable();
    }

    @AfterEach
    void restore() {
        MockFramework.disable();
        GuiSupport.restoreHeadlessMode();
    }

    /**
     * An {@link ImageProducer} that never calls back into any {@link ImageConsumer}: no pixels, no
     * {@code imageComplete}, ever. This is what a real MediaTracker sees for as long as the SF110
     * cluster's fetch is still in flight, and a real {@code waitForID} on it blocks forever - there
     * is nothing that will ever notify it. Using a synthetic producer instead of a real file/URL
     * keeps the test deterministic and independent of the filesystem-probe short-circuit noted
     * below.
     */
    private static final class NeverCompletingImageProducer implements ImageProducer {
        @Override
        public void addConsumer(ImageConsumer ic) {
            // never notified
        }

        @Override
        public boolean isConsumer(ImageConsumer ic) {
            return false;
        }

        @Override
        public void removeConsumer(ImageConsumer ic) {
        }

        @Override
        public void startProduction(ImageConsumer ic) {
            // deliberately produces nothing
        }

        @Override
        public void requestTopDownLeftRightResend(ImageConsumer ic) {
        }
    }

    /**
     * FAILS without the fix: on the production code as checked in, this deadlocks instead of
     * finishing, because {@code MediaTracker.waitForID} is not in the replacement cache and the
     * real JDK method runs, blocking forever on an image that never completes. See the class
     * comment for why this exact wait is what starves 58_fps370's TederData/TederPanel on the
     * cluster.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void waitForIdOnAStillLoadingImageReturnsPromptlyAndReportsComplete() throws InterruptedException {
        Image neverLoads = Toolkit.getDefaultToolkit().createImage(new NeverCompletingImageProducer());
        MediaTracker tracker = new MediaTracker(new JPanel());
        tracker.addImage(neverLoads, 0);

        long start = System.currentTimeMillis();
        MockHeadlessSwing.replacement_waitForID(tracker, 0);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 5000,
                "replacement_waitForID blocked for " + elapsed + "ms on an image that never finishes "
                        + "loading; MediaTracker.waitForID must be in the replacement cache");

        // The caution that matters here: report the wait as *finished*, not the image as *absent*
        // or *errored*. A caller that waited and then asks for status must see a coherent picture,
        // or it falls into its own polling loop and the starvation just moves - and ERRORED would
        // repeat the getAllFonts()/Container.add/getPredefinedCursor mistake of a mock claiming the
        // requested thing simply is not there.
        assertEquals(MediaTracker.COMPLETE, MockHeadlessSwing.replacement_statusID(tracker, 0, false));
        assertTrue(MockHeadlessSwing.replacement_checkID(tracker, 0));
        assertTrue(MockHeadlessSwing.replacement_checkAll(tracker));
        assertEquals(MediaTracker.COMPLETE, MockHeadlessSwing.replacement_statusAll(tracker, false));
    }

    /**
     * With mocking off, a SUT that genuinely waits must still wait: {@code checkID} must reach the
     * real, still-loading tracker rather than being told the load is done. (The blocking
     * {@code waitForID} itself is intentionally not exercised here without mocking - on the
     * production code it would hang this test forever, which is exactly the bug; {@code checkID}
     * proves the same pass-through non-blockingly.)
     */
    @Test
    void checkIdPassesThroughToTheRealTrackerWhenMockingIsOff() {
        MockFramework.disable();
        Image neverLoads = Toolkit.getDefaultToolkit().createImage(new NeverCompletingImageProducer());
        MediaTracker tracker = new MediaTracker(new JPanel());
        tracker.addImage(neverLoads, 0);

        assertFalse(MockHeadlessSwing.replacement_checkID(tracker, 0));
    }
}
