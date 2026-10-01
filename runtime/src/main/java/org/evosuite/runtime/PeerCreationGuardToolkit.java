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
import sun.awt.NullComponentPeer;
import sun.awt.datatransfer.DataTransferer;

import java.awt.Button;
import java.awt.Canvas;
import java.awt.Checkbox;
import java.awt.CheckboxMenuItem;
import java.awt.Choice;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.Label;
import java.awt.List;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.Panel;
import java.awt.PopupMenu;
import java.awt.PrintJob;
import java.awt.Robot;
import java.awt.Scrollbar;
import java.awt.ScrollPane;
import java.awt.TextArea;
import java.awt.TextField;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.dnd.peer.DragSourceContextPeer;
import java.awt.font.TextAttribute;
import java.awt.im.InputMethodHighlight;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.ImageProducer;
import java.awt.peer.ButtonPeer;
import java.awt.peer.CanvasPeer;
import java.awt.peer.CheckboxMenuItemPeer;
import java.awt.peer.CheckboxPeer;
import java.awt.peer.ChoicePeer;
import java.awt.peer.DialogPeer;
import java.awt.peer.FileDialogPeer;
import java.awt.peer.FontPeer;
import java.awt.peer.FramePeer;
import java.awt.peer.KeyboardFocusManagerPeer;
import java.awt.peer.LabelPeer;
import java.awt.peer.LightweightPeer;
import java.awt.peer.ListPeer;
import java.awt.peer.MenuBarPeer;
import java.awt.peer.MenuItemPeer;
import java.awt.peer.MenuPeer;
import java.awt.peer.PanelPeer;
import java.awt.peer.PopupMenuPeer;
import java.awt.peer.RobotPeer;
import java.awt.peer.ScrollPanePeer;
import java.awt.peer.ScrollbarPeer;
import java.awt.peer.TextAreaPeer;
import java.awt.peer.TextFieldPeer;
import java.awt.peer.WindowPeer;
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
 * itself and explicitly overrides every one of its peer-creation methods
 * ({@code createWindow}, {@code createFrame}, {@code createDialog},
 * {@code createButton}, ...) to throw {@link HeadlessException}, matching
 * the interface's own JDK 9+ default body exactly (verified with {@code
 * javap -c} against the JDK source). These overrides are not optional on
 * this class: {@code sun.awt.ComponentFactory} has those default bodies only
 * from JDK 9 onward — on Java 8 every one of its methods is abstract
 * (verified with {@code javap} against Corretto 8), so a class that merely
 * compiles against a modern JDK's defaults loads fine on Java 8 but throws
 * {@link AbstractMethodError} the moment a method nobody overrode is
 * actually called there. {@code createCanvas}/{@code createPanel} (the
 * lightweight-peer path used by Swing/{@code JComponent}) are overridden the
 * same way, returning a shared {@code sun.awt.NullComponentPeer} — the same
 * concrete type the JDK 9+ default resolves to — without touching the real
 * toolkit at all; {@code createComponent} itself is not overridden, since it
 * is not part of Java 8's {@code ComponentFactory} at all and nothing
 * running on a Java 8 JVM can reach it through this interface.
 * {@code getFontPeer}/{@code getDataTransferer} are overridden to return
 * {@code null}, {@code createDragSourceContextPeer} to throw {@link
 * InvalidDnDOperationException}, and the Java-8-only two-argument {@code
 * createRobot(Robot, GraphicsDevice)} — a signature JDK 9+ replaced outright,
 * so it has no modern default to copy — to throw {@link HeadlessException},
 * matching {@code sun.awt.HeadlessToolkit}'s own Java 8 implementation of
 * that exact method. Everything else — the abstract methods {@link Toolkit}
 * itself requires every subclass to implement, plus {@link
 * Toolkit#getSystemEventQueueImpl()} — is delegated to the real toolkit,
 * mirroring the JDK's own {@code sun.awt.HeadlessToolkit} split between
 * "supported" (delegate) and "unsupported" (throw) methods.
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

    // ── create*Peer family: sun.awt.ComponentFactory only grew default
    // implementations of these methods in JDK 9 -- confirmed with javap against
    // both Corretto 8 and Corretto 17: Java 8's ComponentFactory has no default
    // methods at all, every one of them is abstract. A concrete class that relies
    // on inheriting an interface default compiles fine against a modern JDK (which
    // the multi-release runtime module's main source set always does) but, on
    // Java 8, the JVM finds no implementation to dispatch to and throws
    // AbstractMethodError the first time a SUT reaches it -- not at class load,
    // only at that call. Every one of these is therefore overridden explicitly so
    // behaviour does not depend on an interface default that Java 8 does not have.
    // Each body matches JDK 9+'s own default exactly (see sun.awt.ComponentFactory,
    // javap -c): a headless environment has no native peer to create, so every
    // heavyweight create*Peer method throws HeadlessException.
    @Override
    public WindowPeer createWindow(Window target) {
        throw new HeadlessException();
    }

    @Override
    public FramePeer createFrame(Frame target) {
        throw new HeadlessException();
    }

    @Override
    public DialogPeer createDialog(Dialog target) {
        throw new HeadlessException();
    }

    @Override
    public ButtonPeer createButton(Button target) {
        throw new HeadlessException();
    }

    @Override
    public TextFieldPeer createTextField(TextField target) {
        throw new HeadlessException();
    }

    @Override
    public ChoicePeer createChoice(Choice target) {
        throw new HeadlessException();
    }

    @Override
    public LabelPeer createLabel(Label target) {
        throw new HeadlessException();
    }

    @Override
    public ListPeer createList(List target) {
        throw new HeadlessException();
    }

    @Override
    public CheckboxPeer createCheckbox(Checkbox target) {
        throw new HeadlessException();
    }

    @Override
    public ScrollbarPeer createScrollbar(Scrollbar target) {
        throw new HeadlessException();
    }

    @Override
    public ScrollPanePeer createScrollPane(ScrollPane target) {
        throw new HeadlessException();
    }

    @Override
    public TextAreaPeer createTextArea(TextArea target) {
        throw new HeadlessException();
    }

    @Override
    public FileDialogPeer createFileDialog(FileDialog target) {
        throw new HeadlessException();
    }

    @Override
    public MenuBarPeer createMenuBar(MenuBar target) {
        throw new HeadlessException();
    }

    @Override
    public MenuPeer createMenu(Menu target) {
        throw new HeadlessException();
    }

    @Override
    public PopupMenuPeer createPopupMenu(PopupMenu target) {
        throw new HeadlessException();
    }

    @Override
    public MenuItemPeer createMenuItem(MenuItem target) {
        throw new HeadlessException();
    }

    @Override
    public CheckboxMenuItemPeer createCheckboxMenuItem(CheckboxMenuItem target) {
        throw new HeadlessException();
    }

    // ── createCanvas/createPanel: on JDK 9+ these default to casting the result of
    // the interface's createComponent default, which returns a shared
    // sun.awt.LightweightPeerHolder.lightweightMarker singleton -- a
    // sun.awt.NullComponentPeer instance (verified: javap -c -p shows
    // LightweightPeerHolder's <clinit> doing exactly `new NullComponentPeer()`
    // once into a static final field). NullComponentPeer itself implements
    // LightweightPeer, CanvasPeer and PanelPeer directly and is present, public,
    // and has a public no-arg constructor on both Corretto 8 and Corretto 17, so
    // it is used directly here. LightweightPeerHolder itself is JDK 9+ only (does
    // not exist on Corretto 8), so it cannot be referenced from code that must
    // load on Java 8; a private singleton field reproduces its sharing behaviour
    // instead of allocating a fresh peer per call. createComponent is not
    // overridden: it is not part of Java 8's ComponentFactory interface at all
    // (confirmed with javap against Corretto 8), so nothing running on a Java 8
    // JVM can reach it through this interface, and on JDK 9+ it already resolves
    // to this same lightweight marker via the interface default.
    private static final LightweightPeer LIGHTWEIGHT_MARKER = new NullComponentPeer();

    @Override
    public CanvasPeer createCanvas(Canvas target) {
        return (CanvasPeer) LIGHTWEIGHT_MARKER;
    }

    @Override
    public PanelPeer createPanel(Panel target) {
        return (PanelPeer) LIGHTWEIGHT_MARKER;
    }

    // ── createDragSourceContextPeer: same create*Peer family as above, but its
    // JDK 9+ default throws InvalidDnDOperationException rather than
    // HeadlessException -- confirmed with javap -c against Corretto 17 (see
    // sun.awt.ComponentFactory). DragSourceContextPeer creation is also the one
    // path in this family that generated tests can reach directly, independent of
    // addNotify()/peer creation (a SUT building a java.awt.dnd.DragSource and
    // starting a drag). The body matches JDK 9+'s own default exactly: a headless
    // environment has no drag source to create a peer for.
    @Override
    public DragSourceContextPeer createDragSourceContextPeer(DragGestureEvent dge) {
        throw new InvalidDnDOperationException("Headless environment");
    }

    // ── getFontPeer/getDataTransferer: abstract on Java 8 (confirmed with javap
    // against Corretto 8), both with a JDK 9+ default that simply returns null
    // (confirmed with javap -c against Corretto 17 -- `aconst_null; areturn` for
    // both). sun.awt.HeadlessToolkit's own getDataTransferer agrees (javap -c
    // against Corretto 8 shows the identical body), and its getFontPeer only
    // returns non-null by delegating to a wrapped ComponentFactory -- i.e. to a
    // method exactly like this one -- so null is the correct terminal answer here
    // too.
    @Override
    public FontPeer getFontPeer(String name, int style) {
        return null;
    }

    @Override
    public DataTransferer getDataTransferer() {
        return null;
    }

    // ── createRobot(Robot, GraphicsDevice): abstract on Java 8 (confirmed with
    // javap against Corretto 8) but not present at all on JDK 9+'s
    // ComponentFactory, which replaced it with a different signature,
    // createRobot(GraphicsDevice) (no Robot parameter) -- confirmed with javap
    // against Corretto 17. Because the two are different method signatures, not
    // overriding the modern one-arg method does not satisfy Java 8's two-arg
    // abstract method: on a Java 8 JVM, reaching this old signature (java.awt.Robot's
    // constructor calls it) would still throw AbstractMethodError even after the
    // JDK 9+ create*Peer methods above are fixed. There is no JDK 9+ default body to
    // copy for this exact signature, since the signature no longer exists there, so
    // this instead matches sun.awt.HeadlessToolkit's own Java 8 implementation of
    // this exact method (javap -c against Corretto 8: `throw new HeadlessException()`),
    // which is the authoritative "headless, no robot peer" answer for this signature
    // -- and is consistent with this class's own choice elsewhere (getPrintJob,
    // getSystemClipboard, mapInputMethodHighlight above) to mirror HeadlessToolkit's
    // HeadlessException for operations headless construction does not support. This
    // method intentionally carries no @Override annotation: javac compiles this
    // module against a JDK 9+ ComponentFactory, which has no two-arg createRobot to
    // override, so @Override here would fail to compile. The JVM still binds it to
    // Java 8's two-arg abstract interface method by signature at runtime regardless
    // of the annotation -- interface method resolution does not consult it.
    public RobotPeer createRobot(Robot robot, GraphicsDevice screen) {
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
}
