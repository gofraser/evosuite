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

import org.evosuite.runtime.mock.OverrideMock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.Toolkit;
import java.net.URL;
import java.util.function.Supplier;

/**
 * Headless-safe override mock for {@link ImageIcon}.
 *
 * <p>The only behavior that genuinely has to differ from the real class is the
 * null argument handling: {@code new ImageIcon((URL) null)} and friends throw an
 * NPE, and a SUT under test generation must survive that.
 *
 * <p>Everything else has to keep loading the image, exactly like the real class:
 * a lot of GUI code does {@code icon.getImage().getScaledInstance(...)} right in
 * a constructor, so an icon without an image poisons every subclass of the SUT
 * (see {@code corina.map.tools.Tool}). Image decoding itself works in a headless
 * JVM; only the {@link java.awt.MediaTracker} bookkeeping in
 * {@link ImageIcon#loadImage(Image)} touches AWT, and a machine with no display
 * hardware at all (a bare CI container, not merely {@code -Djava.awt.headless=true})
 * could in principle fail there. So the image loading is attempted and any
 * {@link Throwable} degrades the icon instead of propagating.
 */
public class MockImageIcon extends ImageIcon implements OverrideMock {

    private static final long serialVersionUID = 1L;

    private static final Logger logger = LoggerFactory.getLogger(MockImageIcon.class);

    /**
     * Default constructor matching {@link ImageIcon#ImageIcon()}.
     */
    public MockImageIcon() {
        super();
    }

    /**
     * Creates a new MockImageIcon from a URL.
     * @param location the URL of the image
     */
    public MockImageIcon(URL location) {
        super();
        if (location == null) {
            // Real ImageIcon dereferences the URL and throws; surviving that is
            // the one reason this mock exists.
            return;
        }
        setDescription(location.toExternalForm());
        loadImageSafely(() -> Toolkit.getDefaultToolkit().createImage(location));
    }

    public MockImageIcon(String filename) {
        super();
        if (filename == null) {
            return;
        }
        setDescription(filename);
        loadImageSafely(() -> Toolkit.getDefaultToolkit().getImage(filename));
    }

    public MockImageIcon(String filename, String description) {
        this(filename);
        setDescription(description != null ? description : filename);
    }

    public MockImageIcon(byte[] imageData) {
        super();
        if (imageData == null) {
            return;
        }
        loadImageSafely(() -> Toolkit.getDefaultToolkit().createImage(imageData));
    }

    public MockImageIcon(byte[] imageData, String description) {
        this(imageData);
        setDescription(description);
    }

    public MockImageIcon(Image image) {
        super();
        if (image != null) {
            setImage(image);
        }
    }

    public MockImageIcon(Image image, String description) {
        this(image);
        setDescription(description);
    }

    public MockImageIcon(URL location, String description) {
        this(location);
        setDescription(description);
    }

    /**
     * Tracks the image through AWT's MediaTracker like the real class does, so
     * that {@link #getIconWidth()} and {@link #getIconHeight()} are populated.
     * Falls back to an untracked image if AWT is unusable in this environment;
     * the image reference is still kept, only its size stays unknown.
     */
    @Override
    protected void loadImage(Image image) {
        try {
            super.loadImage(image);
        } catch (Throwable t) {
            logger.debug("Could not track image in this environment, icon size stays unknown: {}",
                    t.toString());
        }
    }

    /**
     * Creates the image with the real AWT toolkit and installs it. If image
     * creation is not possible in this environment, the icon degrades to an
     * image-less one instead of failing the SUT.
     */
    private void loadImageSafely(Supplier<Image> imageSupplier) {
        try {
            Image image = imageSupplier.get();
            if (image != null) {
                // setImage() also runs loadImage(), which is guarded above.
                setImage(image);
            }
        } catch (Throwable t) {
            logger.debug("Could not create image in this environment, icon stays empty: {}",
                    t.toString());
        }
    }
}
