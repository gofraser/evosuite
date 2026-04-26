/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.javax.swing;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MockImageIconTest {

    @Test
    void constructorsAreHeadlessSafe() {
        assertDoesNotThrow(() -> new MockImageIcon());
        assertDoesNotThrow(() -> new MockImageIcon((String) null));
        assertDoesNotThrow(() -> new MockImageIcon("dummy/path/image.png"));
        assertDoesNotThrow(() -> new MockImageIcon("dummy/path/image.png", "desc"));
        assertDoesNotThrow(() -> new MockImageIcon(new byte[]{1, 2, 3}));
        assertDoesNotThrow(() -> new MockImageIcon(new byte[]{1, 2, 3}, "bytes"));
        assertDoesNotThrow(() -> new MockImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)));
        assertDoesNotThrow(() -> new MockImageIcon(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "img"));
        assertDoesNotThrow(() -> new MockImageIcon((java.net.URL) null));
        assertDoesNotThrow(() -> new MockImageIcon((java.net.URL) null, "url"));
    }
}
