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
package org.evosuite.runtime;

import sun.awt.ComponentFactory;
import sun.awt.KeyboardFocusManagerPeerProvider;

import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.PrintJob;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.font.TextAttribute;
import java.awt.im.InputMethodHighlight;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.ImageProducer;
import java.awt.peer.KeyboardFocusManagerPeer;
import java.net.URL;
import java.util.Map;
import java.util.Properties;

/**
 * Wraps the real, unwrapped {@link Toolkit} that {@code
 * GuiSupport.disableHeadlessForMockConstruction()} installs so that GUI
 * super-constructors can run, without exposing that toolkit's peer-creation
 * entry points.
 *
 * <p>Background: {@code GuiSupport.initializeHeadlessToolkitIfNeeded()}
 * constructs the real platform toolkit (e.g. {@code sun.awt.X11.XToolkit})
 * while the JVM is still headless, so the toolkit's own constructor skips its
 * native initialization ({@code XToolkit.init()} / {@code XWM.init()}, the
 * code that would start its native event thread) — it never runs, because on
 * a JVM started headless there is no real display to initialize against.
 * Bare <em>construction</em> of a {@code Window}/{@code Frame}/{@code Dialog}
 * does not need that native state (see {@link Toolkit#getScreenInsets}, which
 * this class does not even need to override — the JDK base implementation
 * returns a zero {@code Insets} once the {@code headless} flag itself is
 * false). <em>Peer creation</em> does: {@code Window.addNotify()} (reached
 * from {@code pack()}/{@code setVisible(true)}/{@code addNotify()}) calls
 * {@code createWindow()}/{@code createFrame()}/... on this toolkit, and the
 * real toolkit's overrides of those methods construct a native peer (e.g.
 * {@code new XFramePeer(target)}), which loads {@code sun.awt.X11.XWindow}
 * for the first time and runs its unconditional native {@code initIDs()} —
 * unconditional because that class assumes the toolkit already completed the
 * native initialization above. On a JVM with no real display that call fails
 * with {@code UnsatisfiedLinkError}, and because the failure happens inside a
 * class's {@code <clinit>}, the class is left in {@code <clinit failed>}
 * state for the rest of the JVM: every later reference to it — even from an
 * unrelated SUT — throws {@code NoClassDefFoundError} instead of running.
 *
 * <p>This matters even though the {@code Mock*}/{@code OverrideMock} classes
 * (e.g. {@code MockFrame}) already no-op {@code pack()}/{@code setVisible()}:
 * that only protects SUTs that directly <em>extend</em> {@code Frame}/{@code
 * JFrame}/etc. (superclass-rewritten by {@code MethodCallReplacementClassAdapter}).
 * A SUT that merely <em>uses</em> a raw {@code JFrame}/{@code Frame} — as a
 * field or a local built and packed inside its own constructor — gets the
 * genuine JDK class, and if that constructor also calls {@code pack()}/{@code
 * setVisible()} on it, that call happens while still inside the disable/restore
 * window, reaching the real toolkit's peer creation directly.
 *
 * <p>This class closes that gap: it implements {@link ComponentFactory}
 * itself but does not override any of its peer-creation methods
 * ({@code createWindow}, {@code createFrame}, {@code createDialog},
 * {@code createButton}, ...) — every one of them already has a default
 * implementation on the interface that throws {@link HeadlessException}
 * (verified against the JDK source), so simply not overriding them is
 * sufficient. {@code createComponent}/{@code createCanvas}/{@code
 * createPanel} (the lightweight-peer path used by Swing/{@code JComponent})
 * are left the same way, returning the interface's safe lightweight marker
 * peer without touching the real toolkit at all. Everything else — the
 * abstract methods {@link Toolkit} itself requires every subclass to
 * implement, plus {@link Toolkit#getSystemEventQueueImpl()} — is delegated
 * to the real toolkit, mirroring the JDK's own {@code sun.awt.HeadlessToolkit}
 * split between "supported" (delegate) and "unsupported" (throw) methods.
 *
 * <p><b>Why this is a separate top-level class, and not nested inside {@code
 * GuiSupport}:</b> {@code sun.awt.ComponentFactory} is a JDK-internal type.
 * Referencing it in an {@code implements} clause requires the {@code sun.awt}
 * package to be <em>exported</em> (not just <em>opened</em>) to this code's
 * module — verified empirically: {@code --add-opens java.desktop/sun.awt=ALL-UNNAMED}
 * alone is not sufficient on every JDK build, some require {@code --add-exports
 * java.desktop/sun.awt=ALL-UNNAMED} too (both are granted where EvoSuite
 * controls the launch, see {@code ExecutionModeUtils.addCommonModuleOpens}).
 * Where the export is missing, resolving this class's supertype fails with
 * {@code IllegalAccessError} — and critically, if that reference lived
 * directly inside one of {@code GuiSupport}'s own methods (as a typed {@code
 * new PeerCreationGuardToolkit(...)} expression), the JVM's verifier resolves
 * it eagerly while preparing the *enclosing* method for its first invocation,
 * so a {@code try/catch (Throwable)} around the construction site inside that
 * method cannot catch it — the whole enclosing method becomes uninvocable,
 * and the error surfaces at its caller instead (observed in practice: it broke
 * Mockito's ability to instrument {@code GuiSupport} at all, since Mockito's
 * bytecode generation touches every member it can reach). Keeping this class
 * separate and constructing it purely via {@code Class.forName(...)}
 * reflection from {@code GuiSupport} means {@code GuiSupport} itself never
 * carries a compile-time reference to {@code ComponentFactory}: a failure to
 * load *this* class is an ordinary, catchable exception at the reflective
 * call site, and {@code GuiSupport}'s own methods remain fully verifiable
 * and invocable regardless of whether this class can load on a given JDK.
 */
final class PeerCreationGuardToolkit extends Toolkit
        implements ComponentFactory, KeyboardFocusManagerPeerProvider {

    private final Toolkit real;

    /**
     * The {@code sun.awt.HeadlessToolkit} this guard temporarily replaces in
     * {@code Toolkit.toolkit}. Kept only to borrow its no-op
     * {@link KeyboardFocusManagerPeer} -- see {@link #getKeyboardFocusManagerPeer()}.
     */
    private final Toolkit headless;

    /**
     * @param real     the unwrapped real platform toolkit (the one {@code
     *                 sun.awt.HeadlessToolkit.getUnderlyingToolkit()} returns)
     * @param headless the {@code sun.awt.HeadlessToolkit} that was installed
     *                 before the flip and is being swapped out for this guard
     */
    PeerCreationGuardToolkit(Toolkit real, Toolkit headless) {
        this.real = real;
        this.headless = headless;
    }

    // ── KeyboardFocusManagerPeerProvider: the one toolkit cast the JDK makes
    // WITHOUT an instanceof guard on a Swing construction path.
    // java.awt.KeyboardFocusManager.initPeer() does
    //     (KeyboardFocusManagerPeerProvider) Toolkit.getDefaultToolkit()
    // the first time a KeyboardFocusManager is created for the AppContext,
    // which JComponent construction reaches (Container.<init> ->
    // Component.getFocusTraversalKeys_NoIDCheck -> KeyboardFocusManager.
    // getCurrentKeyboardFocusManager -> new DefaultKeyboardFocusManager()).
    // Every JDK toolkit implements this interface -- the real platform
    // toolkits and sun.awt.HeadlessToolkit alike -- so a toolkit that does
    // not gets a ClassCastException there, and because the manager is only
    // cached in the AppContext after a *successful* construction, the cast
    // is retried (and fails again) on every later GUI constructor executed
    // inside the flip window: an all-or-nothing failure of every JComponent
    // SUT, not a one-off. Delegate to the HeadlessToolkit's peer, a no-op
    // stub that never touches native code; the real toolkit's peer (e.g.
    // XKeyboardFocusManagerPeer) would. ─────────────────────────────────

    @Override
    public KeyboardFocusManagerPeer getKeyboardFocusManagerPeer() {
        if (headless instanceof KeyboardFocusManagerPeerProvider) {
            return ((KeyboardFocusManagerPeerProvider) headless).getKeyboardFocusManagerPeer();
        }
        throw new HeadlessException();
    }

    // ── Toolkit's abstract methods: delegate to the real toolkit. None of
    // these construct a native peer, so they do not reach the broken
    // native-init path described above. ──────────────────────────────

    @Override
    public Dimension getScreenSize() {
        return real.getScreenSize();
    }

    @Override
    public int getScreenResolution() {
        return real.getScreenResolution();
    }

    @Override
    public ColorModel getColorModel() {
        return real.getColorModel();
    }

    @Override
    @SuppressWarnings("deprecation")
    public String[] getFontList() {
        return real.getFontList();
    }

    @Override
    @SuppressWarnings("deprecation")
    public FontMetrics getFontMetrics(Font font) {
        return real.getFontMetrics(font);
    }

    @Override
    public void sync() {
        real.sync();
    }

    @Override
    public Image getImage(String filename) {
        return real.getImage(filename);
    }

    @Override
    public Image getImage(URL url) {
        return real.getImage(url);
    }

    @Override
    public Image createImage(String filename) {
        return real.createImage(filename);
    }

    @Override
    public Image createImage(URL url) {
        return real.createImage(url);
    }

    @Override
    public boolean prepareImage(Image image, int width, int height, ImageObserver observer) {
        return real.prepareImage(image, width, height, observer);
    }

    @Override
    public int checkImage(Image image, int width, int height, ImageObserver observer) {
        return real.checkImage(image, width, height, observer);
    }

    @Override
    public Image createImage(ImageProducer producer) {
        return real.createImage(producer);
    }

    @Override
    public Image createImage(byte[] imagedata, int imageoffset, int imagelength) {
        return real.createImage(imagedata, imageoffset, imagelength);
    }

    @Override
    public void beep() {
        real.beep();
    }

    @Override
    public boolean isModalityTypeSupported(Dialog.ModalityType modalityType) {
        // Mirrors sun.awt.HeadlessToolkit: no real window ever gets shown,
        // so no modality behavior needs to be supported.
        return false;
    }

    @Override
    public boolean isModalExclusionTypeSupported(Dialog.ModalExclusionType exclusionType) {
        return false;
    }

    // ── Unsupported in this construction-only context: mirrors
    // sun.awt.HeadlessToolkit's own choice to throw for these rather than
    // delegate. None of these are needed for a GUI SUT's constructor to
    // run; delegating them to the real (natively-uninitialized) toolkit
    // would only be trading one unverified code path for another. ──────

    @Override
    public PrintJob getPrintJob(Frame frame, String jobtitle, Properties props) {
        throw new HeadlessException();
    }

    @Override
    public Clipboard getSystemClipboard() {
        throw new HeadlessException();
    }

    @Override
    public Map<TextAttribute, ?> mapInputMethodHighlight(InputMethodHighlight highlight) {
        throw new HeadlessException();
    }

    // ── protected abstract on Toolkit; delegate via the same public
    // static helper sun.awt.HeadlessToolkit itself uses internally. ────

    @Override
    protected EventQueue getSystemEventQueueImpl() {
        try {
            Class<?> sunToolkitClass = Class.forName("sun.awt.SunToolkit");
            Class<?> appContextClass = Class.forName("sun.awt.AppContext");
            Object appContext = appContextClass.getMethod("getAppContext").invoke(null);
            return (EventQueue) sunToolkitClass
                    .getMethod("getSystemEventQueueImplPP", appContextClass)
                    .invoke(null, appContext);
        } catch (Throwable t) {
            throw new HeadlessException();
        }
    }

    // ── ComponentFactory's create*Peer methods (createWindow, createFrame,
    // createDialog, createButton, createTextField, ...) are deliberately
    // NOT overridden here. Every one of them already has a default
    // implementation on the ComponentFactory interface that throws
    // HeadlessException (see sun/awt/ComponentFactory.java); not
    // overriding them means that default is what a caller gets, instead
    // of the real toolkit's override that would construct a native peer.
    // createComponent/createCanvas/createPanel (the lightweight peer path
    // used by JComponent/Swing) are left the same way, resolving to the
    // interface's safe lightweight marker peer. ─────────────────────────
}
