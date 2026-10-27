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
package org.evosuite.runtime.instrumentation;

import org.evosuite.PackageInfo;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.EvoSuiteMock;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.mock.OverrideMock;
import org.evosuite.runtime.mock.StaticReplacementMethod;
import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.lang.MockThrowable;
import org.evosuite.runtime.util.ReflectionUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache for method call replacements.
 *
 * @author gordon
 */
public class MethodCallReplacementCache {

    private static final Logger logger = LoggerFactory.getLogger(MethodCallReplacementCache.class);

    private static MethodCallReplacementCache instance = null;

    /**
     * method replacements, which are called with Opcodes.INVOKESTATIC.
     */
    private final Map<String, Map<String, MethodCallReplacement>> replacementCalls = new HashMap<>();

    /**
     * method replacements, which are called with Opcodes.INVOKESPECIAL.
     */
    private final Map<String, Map<String, MethodCallReplacement>> specialReplacementCalls = new HashMap<>();

    /** The settings the replacements were built for. */
    private final int settings = RuntimeSettings.mockingFingerprint();

    private MethodCallReplacementCache() {

        // Which replacements exist is decided here, once, and the instance is then reused for
        // every class instrumented afterwards. A class loaded while a flag was still off keeps
        // the instrumentation it got, so if these do not read as expected, look at what reset
        // the singleton and when relative to the settings being applied. Runtime.resetRuntime()
        // carries the matching caveat for mockJVMNonDeterminism.
        logger.info("Building method call replacement cache: useVFS={} useVNET={} "
                        + "mockJVMNonDeterminism={} mockGUI={}",
                RuntimeSettings.useVFS, RuntimeSettings.useVNET,
                RuntimeSettings.mockJVMNonDeterminism, RuntimeSettings.mockGUI);

        if (RuntimeSettings.mockJVMNonDeterminism) {

            // java.lang.*
            addJavaLangCalls();

            // java.lang.reflect.{AccessibleObject,Field,Method,Constructor}.setAccessible(..)
            addReflectionAccessCalls();

            // sun.misc.Unsafe field writes
            addUnsafeWriteCalls();

            // javax.swing.JComponent.getPreferredSize()
            addReplacementCall(new MethodCallReplacement("javax/swing/JComponent", "getPreferredSize",
                    "()Ljava/awt/Dimension;", Opcodes.INVOKEVIRTUAL,
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.javax.swing.MockJComponent.class),
                    "getPreferredSize", "()Ljava/awt/Dimension;", true, false));

            // setDragEnabled, setDropTarget, setMixingCutoutShape, setCursor, Container.add/remove
            // and JTabbedPane.addTab/insertTab are rewritten by the generic fallbacks in
            // MethodCallReplacementMethodAdapter, which run before this cache is consulted.
            String headlessSwing = PackageInfo.getNameWithSlash(
                    org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class);
            String box = "javax/swing/JComboBox";
            instanceToStatic(box, "setSelectedItem", "(Ljava/lang/Object;)V",
                    headlessSwing, "replacement_setSelectedItem");
            instanceToStatic(box, "setEditable", "(Z)V", headlessSwing, "replacement_setEditable");
            instanceToStatic(box, "getEditor", "()Ljavax/swing/ComboBoxEditor;",
                    headlessSwing, "replacement_getEditor");
            String text = "javax/swing/text/JTextComponent";
            instanceToStatic(text, "getCaret", "()Ljavax/swing/text/Caret;",
                    headlessSwing, "replacement_getCaret");
            instanceToStatic(text, "setCaretPosition", "(I)V", headlessSwing, "replacement_setCaretPosition");
            instanceToStatic(text, "moveCaretPosition", "(I)V", headlessSwing, "replacement_moveCaretPosition");
            instanceToStatic(text, "setSelectionStart", "(I)V", headlessSwing, "replacement_setSelectionStart");
            instanceToStatic(text, "setSelectionEnd", "(I)V", headlessSwing, "replacement_setSelectionEnd");
            instanceToStatic(text, "select", "(II)V", headlessSwing, "replacement_select");
            instanceToStatic(text, "selectAll", "()V", headlessSwing, "replacement_selectAll");
            staticCall("javax/swing/JColorChooser", "showDialog",
                    "(Ljava/awt/Component;Ljava/lang/String;Ljava/awt/Color;)Ljava/awt/Color;",
                    headlessSwing, "replacement_showColorDialog");
            String desktop = "java/awt/Desktop";
            staticCall(desktop, "isDesktopSupported", "()Z", headlessSwing, "replacement_isDesktopSupported");
            staticCall(desktop, "getDesktop", "()Ljava/awt/Desktop;", headlessSwing, "replacement_getDesktop");
            instanceToStatic(desktop, "open", "(Ljava/io/File;)V", headlessSwing, "replacement_desktopOpen");
            instanceToStatic(desktop, "edit", "(Ljava/io/File;)V", headlessSwing, "replacement_desktopEdit");
            instanceToStatic(desktop, "print", "(Ljava/io/File;)V", headlessSwing, "replacement_desktopPrint");
            instanceToStatic(desktop, "browse", "(Ljava/net/URI;)V", headlessSwing, "replacement_desktopBrowse");
            instanceToStatic(desktop, "mail", "()V", headlessSwing, "replacement_desktopMail");
            instanceToStatic(desktop, "mail", "(Ljava/net/URI;)V", headlessSwing, "replacement_desktopMail");
            String tray = "java/awt/SystemTray";
            staticCall(tray, "isSupported", "()Z", headlessSwing, "replacement_isSystemTraySupported");
            staticCall(tray, "getSystemTray", "()Ljava/awt/SystemTray;", headlessSwing,
                    "replacement_getSystemTray");
            instanceToStatic(tray, "add", "(Ljava/awt/TrayIcon;)V", headlessSwing, "replacement_systemTrayAdd");
            instanceToStatic(tray, "remove", "(Ljava/awt/TrayIcon;)V", headlessSwing,
                    "replacement_systemTrayRemove");
            constructorsToStatic("java/awt/Robot", headlessSwing, "replacement_newRobot",
                    "()V", "(Ljava/awt/GraphicsDevice;)V");
            instanceToStatic("java/awt/Robot", "createScreenCapture",
                    "(Ljava/awt/Rectangle;)Ljava/awt/image/BufferedImage;",
                    headlessSwing, "replacement_robotCreateScreenCapture");
            staticCall("java/awt/MouseInfo", "getPointerInfo", "()Ljava/awt/PointerInfo;",
                    headlessSwing, "replacement_getPointerInfo");
            instanceToStatic("java/awt/Toolkit", "getScreenSize", "()Ljava/awt/Dimension;",
                    headlessSwing, "replacement_getScreenSize");
            staticCall("java/awt/Toolkit", "getDefaultToolkit", "()Ljava/awt/Toolkit;",
                    headlessSwing, "replacement_getDefaultToolkit");
            String tracker = "java/awt/MediaTracker";
            instanceToStatic(tracker, "waitForID", "(I)V", headlessSwing, "replacement_waitForID");
            instanceToStatic(tracker, "waitForID", "(IJ)Z", headlessSwing, "replacement_waitForID");
            instanceToStatic(tracker, "waitForAll", "()V", headlessSwing, "replacement_waitForAll");
            instanceToStatic(tracker, "waitForAll", "(J)Z", headlessSwing, "replacement_waitForAll");
            instanceToStatic(tracker, "checkID", "(I)Z", headlessSwing, "replacement_checkID");
            instanceToStatic(tracker, "checkID", "(IZ)Z", headlessSwing, "replacement_checkID");
            instanceToStatic(tracker, "checkAll", "()Z", headlessSwing, "replacement_checkAll");
            instanceToStatic(tracker, "checkAll", "(Z)Z", headlessSwing, "replacement_checkAll");
            instanceToStatic(tracker, "statusID", "(IZ)I", headlessSwing, "replacement_statusID");
            instanceToStatic(tracker, "statusAll", "(Z)I", headlessSwing, "replacement_statusAll");
            staticCall("java/awt/GraphicsEnvironment", "getLocalGraphicsEnvironment",
                    "()Ljava/awt/GraphicsEnvironment;", headlessSwing, "replacement_getLocalGraphicsEnvironment");
            instanceToStatic("java/security/CodeSource", "getLocation", "()Ljava/net/URL;",
                    PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.java.security.MockCodeSource.class),
                    "replacement_getLocation");
            // Cursor.getDefaultCursor/getPredefinedCursor/new Cursor(int) and the keyboard focus
            // manager accessors work headless, so they are deliberately not replaced.
            staticCall("java/awt/Cursor", "getSystemCustomCursor", "(Ljava/lang/String;)Ljava/awt/Cursor;",
                    headlessSwing, "replacement_getSystemCustomCursor");
            constructorsToStatic("java/awt/Button", headlessSwing, "replacement_newButton",
                    "()V", "(Ljava/lang/String;)V");
            constructorsToStatic("java/awt/Label", headlessSwing, "replacement_newLabel",
                    "()V", "(Ljava/lang/String;)V", "(Ljava/lang/String;I)V");
            constructorsToStatic("java/awt/TextField", headlessSwing, "replacement_newTextField",
                    "()V", "(Ljava/lang/String;)V", "(I)V", "(Ljava/lang/String;I)V");
            constructorsToStatic("javax/swing/JTextField", headlessSwing, "replacement_newJTextField",
                    "()V", "(Ljava/lang/String;)V", "(I)V", "(Ljava/lang/String;I)V",
                    "(Ljavax/swing/text/Document;Ljava/lang/String;I)V");
            constructorsToStatic("javax/swing/JMenuBar", headlessSwing, "replacement_newJMenuBar", "()V");
            constructorsToStatic("javax/swing/JTextArea", headlessSwing, "replacement_newJTextArea",
                    "()V", "(Ljava/lang/String;)V", "(II)V", "(Ljava/lang/String;II)V",
                    "(Ljavax/swing/text/Document;)V", "(Ljavax/swing/text/Document;Ljava/lang/String;II)V");
            constructorsToStatic("java/awt/MenuItem", headlessSwing, "replacement_newMenuItem",
                    "()V", "(Ljava/lang/String;)V", "(Ljava/lang/String;Ljava/awt/MenuShortcut;)V");
            constructorsToStatic("java/awt/Menu", headlessSwing, "replacement_newMenu",
                    "()V", "(Ljava/lang/String;)V", "(Ljava/lang/String;Z)V");
            constructorsToStatic("java/awt/MenuBar", headlessSwing, "replacement_newMenuBar", "()V");
            // With mockGUI, MockWindow and MockFrame construct real, display-less objects and are
            // registered as constructor replacements through the mock list, so a null stand-in
            // is only needed when they are not in use.
            if (!RuntimeSettings.mockGUI) {
                constructorsToStatic("java/awt/Window", headlessSwing, "replacement_newWindow",
                        "(Ljava/awt/Frame;)V", "(Ljava/awt/Window;)V",
                        "(Ljava/awt/Window;Ljava/awt/GraphicsConfiguration;)V");
                constructorsToStatic("java/awt/Frame", headlessSwing, "replacement_newFrame",
                        "()V", "(Ljava/lang/String;)V");
            }
            constructorsToStatic("java/awt/Dialog", headlessSwing, "replacement_newDialog",
                    "(Ljava/awt/Frame;)V", "(Ljava/awt/Frame;Z)V", "(Ljava/awt/Frame;Ljava/lang/String;)V",
                    "(Ljava/awt/Frame;Ljava/lang/String;Z)V");
            constructorsToStatic("java/awt/PopupMenu", headlessSwing, "replacement_newPopupMenu",
                    "()V", "(Ljava/lang/String;)V");
            constructorsToStatic("java/awt/CheckboxMenuItem", headlessSwing, "replacement_newCheckboxMenuItem",
                    "()V", "(Ljava/lang/String;)V", "(Ljava/lang/String;Z)V");
            constructorsToStatic("java/awt/TextArea", headlessSwing, "replacement_newTextArea",
                    "()V", "(Ljava/lang/String;)V", "(II)V", "(Ljava/lang/String;II)V",
                    "(Ljava/lang/String;III)V");
            constructorsToStatic("java/awt/Insets", headlessSwing, "replacement_newInsets", "(IIII)V");
            staticCall("java/awt/KeyboardFocusManager", "setCurrentKeyboardFocusManager",
                    "(Ljava/awt/KeyboardFocusManager;)V", headlessSwing,
                    "replacement_setCurrentKeyboardFocusManager");
            constructorsToStatic("java/awt/dnd/DropTarget", headlessSwing, "replacement_newDropTarget",
                    "()V", "(Ljava/awt/Component;Ljava/awt/dnd/DropTargetListener;)V",
                    "(Ljava/awt/Component;ILjava/awt/dnd/DropTargetListener;)V",
                    "(Ljava/awt/Component;ILjava/awt/dnd/DropTargetListener;Z)V",
                    "(Ljava/awt/Component;ILjava/awt/dnd/DropTargetListener;ZLjava/awt/datatransfer/FlavorMap;)V");

            addExtraceExceptionReplacements();

        }

        handleMockList();

    }

    /**
     * <p>Getter for the field <code>instance</code>.</p>
     *
     * @return a {@link org.evosuite.runtime.instrumentation.MethodCallReplacementCache} object.
     */
    public static MethodCallReplacementCache getInstance() {
        int current = RuntimeSettings.mockingFingerprint();
        MethodCallReplacementCache cache = instance;
        if (cache == null || cache.settings != current) {
            // Which replacements exist depends on the settings, so a change of any of them, as
            // between test classes with different runner parameters, calls for a new cache.
            cache = new MethodCallReplacementCache();
            instance = cache;
        }
        return cache;
    }

    /**
     * <p>resetSingleton.</p>
     */
    public static void resetSingleton() {
        instance = null;
    }

    /**
     * Adds a replacement call.
     *
     * @param replacement the replacement call to add.
     */
    private void addReplacementCall(MethodCallReplacement replacement) {
        if (!replacementCalls.containsKey(replacement.getClassName())) {
            replacementCalls.put(replacement.getClassName(), new HashMap<>());
        }
        replacementCalls.get(replacement.getClassName()).put(replacement.getMethodNameWithDesc(), replacement);
    }

    /**
     * Adds a special replacement call.
     *
     * @param replacement the replacement call to add.
     */
    private void addSpecialReplacementCall(MethodCallReplacement replacement) {
        if (!specialReplacementCalls.containsKey(replacement.getClassName())) {
            specialReplacementCalls.put(replacement.getClassName(), new HashMap<>());
        }
        specialReplacementCalls.get(replacement.getClassName()).put(replacement.getMethodNameWithDesc(), replacement);
    }

    /**
     * <p>hasReplacementCall.</p>
     *
     * @param className              a {@link java.lang.String} object.
     * @param methodNameWithDesc a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean hasReplacementCall(String className, String methodNameWithDesc) {
        if (!replacementCalls.containsKey(className)) {
            return false;
        }

        return replacementCalls.get(className).containsKey(methodNameWithDesc);
    }

    /**
     * <p>getReplacementCall.</p>
     *
     * @param className              a {@link java.lang.String} object.
     * @param methodNameWithDesc a {@link java.lang.String} object.
     * @return a {@link org.evosuite.runtime.instrumentation.MethodCallReplacement} object.
     */
    public MethodCallReplacement getReplacementCall(String className, String methodNameWithDesc) {
        return replacementCalls.get(className).get(methodNameWithDesc);
    }

    /**
     * <p>hasSpecialReplacementCall.</p>
     *
     * @param className              a {@link java.lang.String} object.
     * @param methodNameWithDesc a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean hasSpecialReplacementCall(String className, String methodNameWithDesc) {
        if (!specialReplacementCalls.containsKey(className)) {
            return false;
        }

        return specialReplacementCalls.get(className).containsKey(methodNameWithDesc);
    }

    /**
     * <p>getSpecialReplacementCall.</p>
     *
     * @param className              a {@link java.lang.String} object.
     * @param methodNameWithDesc a {@link java.lang.String} object.
     * @return a {@link org.evosuite.runtime.instrumentation.MethodCallReplacement} object.
     */
    public MethodCallReplacement getSpecialReplacementCall(String className, String methodNameWithDesc) {
        return specialReplacementCalls.get(className).get(methodNameWithDesc);
    }

    /**
     * All registered replacement calls, ie the ones dispatched with INVOKESTATIC. Exposed so that
     * consistency of the table can be checked.
     *
     * @return a {@link java.util.Collection} object.
     */
    Collection<MethodCallReplacement> getAllReplacementCalls() {
        List<MethodCallReplacement> all = new ArrayList<>();
        for (Map<String, MethodCallReplacement> perClass : replacementCalls.values()) {
            all.addAll(perClass.values());
        }
        return all;
    }

    /**
     * Ideally, all mocking should be handled by either OverrideMock or
     * StaticReplacementMock. However, even in such cases, we would not be able
     * to get mocks from classes that are not instrumented (eg, other Java API
     * classes we do not mock). In theory, we should mock all of them. The
     * problem raises for "Exceptions", as they are used everywhere, and would
     * require to mock the full Java API (which is not going to happen...).
     * Solution is, beside using OverrideMock for them, to also a further static
     * replacement (implemented in this method).
     *
     * <p>Note: why not just using static replacement instead of OverrideMock?
     * Because static replacement will not work if an exception instance is used
     * in a non-instrumented class, whereas OverrideMock would. Still, it could
     * be tedious to prepare OverrideMock for every single type of exception, so
     * the static replacement here could be a temporary workaround.
     */
    private void addExtraceExceptionReplacements() {

        List<Class<? extends Throwable>> classes = Arrays.asList(IOException.class,
                // following java.lang
                Throwable.class, ArithmeticException.class, ArrayIndexOutOfBoundsException.class,
                ArrayStoreException.class, ClassCastException.class, ClassNotFoundException.class,
                CloneNotSupportedException.class, EnumConstantNotPresentException.class, Exception.class,
                IllegalAccessException.class, IllegalArgumentException.class, IllegalMonitorStateException.class,
                IllegalStateException.class, IllegalThreadStateException.class, IndexOutOfBoundsException.class,
                InstantiationException.class, InterruptedException.class, NegativeArraySizeException.class,
                NoSuchFieldException.class, NoSuchMethodException.class, NullPointerException.class,
                NumberFormatException.class, ReflectiveOperationException.class, RuntimeException.class,
                SecurityException.class, StringIndexOutOfBoundsException.class, TypeNotPresentException.class,
                UnsupportedOperationException.class, AbstractMethodError.class, AssertionError.class,
                BootstrapMethodError.class, ClassCircularityError.class, ClassFormatError.class, Error.class,
                ExceptionInInitializerError.class, IllegalAccessError.class, IncompatibleClassChangeError.class,
                InstantiationError.class, InternalError.class, LinkageError.class, NoClassDefFoundError.class,
                NoSuchFieldError.class, NoSuchMethodError.class, OutOfMemoryError.class, StackOverflowError.class,
                ThreadDeath.class, UnknownError.class, UnsatisfiedLinkError.class, UnsupportedClassVersionError.class,
                VerifyError.class, VirtualMachineError.class);

        for (Class<?> k : classes) {

            String jvmOriginal = k.getName().replace('.', '/');
            String jvmMock = MockThrowable.class.getName().replace('.', '/');

            addReplacementCall(new MethodCallReplacement(jvmOriginal, "getStackTrace",
                    "()[Ljava/lang/StackTraceElement;", Opcodes.INVOKEVIRTUAL, jvmMock, "replacement_getStackTrace",
                    "(Ljava/lang/Throwable;)[Ljava/lang/StackTraceElement;", false, false));

            addReplacementCall(new MethodCallReplacement(jvmOriginal, "printStackTrace", "(Ljava/io/PrintStream;)V",
                    Opcodes.INVOKEVIRTUAL, jvmMock, "replacement_printStackTrace",
                    "(Ljava/lang/Throwable;Ljava/io/PrintStream;)V", false, false));

            addReplacementCall(new MethodCallReplacement(jvmOriginal, "printStackTrace", "(Ljava/io/PrintWriter;)V",
                    Opcodes.INVOKEVIRTUAL, jvmMock, "replacement_printStackTrace",
                    "(Ljava/lang/Throwable;Ljava/io/PrintWriter;)V", false, false));
        }

    }

    private void handleMockList() {
        for (Class<? extends EvoSuiteMock> mock : MockList.getList()) {
            if (OverrideMock.class.isAssignableFrom(mock)) {
                replaceAllConstructors(mock, mock.getSuperclass());
                replaceAllStaticMethods(mock, mock.getSuperclass());
                replaceAllInvokeSpecial(mock, mock.getSuperclass());
                handleStaticReplacementMethods(mock);
            } else if (StaticReplacementMock.class.isAssignableFrom(mock)) {

                String mockedName;
                try {
                    mockedName = ((StaticReplacementMock) mock.newInstance()).getMockedClassName();
                } catch (Throwable t) {
                    // Optional mocks for newer JDK APIs may fail to initialize on older runtimes.
                    // In that case we skip the mock instead of aborting instrumentation.
                    logger.debug("Skipping unavailable mock {}", mock.getCanonicalName(), t);
                    continue;
                }
                Class<?> mocked;
                try {
                    mocked = StaticReplacementMock.class.getClassLoader().loadClass(mockedName);
                } catch (Throwable t) {
                    logger.debug(
                            "Skipping mock {} because target {} is unavailable",
                            mock.getCanonicalName(),
                            mockedName,
                            t);
                    continue;
                }

                replaceAllStaticMethods(mock, mocked);
                replaceAllInstanceMethodsWithStatic(mock, mocked);
                replaceAllConstructorsWithStaticCalls(mock, mocked);
            }
        }
    }

    private void handleStaticReplacementMethods(Class<? extends EvoSuiteMock> mockClass) {

        for (Method m : mockClass.getMethods()) {

            StaticReplacementMethod srm = m.getAnnotation(StaticReplacementMethod.class);
            if (srm == null) {
                continue;
            }

            if (!Modifier.isStatic(m.getModifiers())) {
                throw new RuntimeException("EvoSuite Bug: improper annotations in class " + mockClass.getName());
            }

            String target;
            if (OverrideMock.class.isAssignableFrom(mockClass)) {
                target = mockClass.getSuperclass().getCanonicalName();
            } else {
                throw new RuntimeException("EvoSuite Bug: StaticReplacementMethod can only be used in OverrideMock");
            }

            String desc = Type.getMethodDescriptor(m);
            addSpecialReplacementCall(
                    new MethodCallReplacement(target.replace('.', '/'), m.getName(), desc, Opcodes.INVOKESPECIAL,
                            mockClass.getCanonicalName().replace('.', '/'), m.getName(), desc, false, false));

        }
    }

    private void addJavaLangCalls() {

        // java/lang/System
        String system = PackageInfo.getNameWithSlash(org.evosuite.runtime.System.class);
        staticCall("java/lang/System", "exit", "(I)V", system, "exit");
        staticCall("java/lang/System", "setSecurityManager", "(Ljava/lang/SecurityManager;)V",
                system, "setSecurityManager");
        staticCall("java/lang/System", "currentTimeMillis", "()J", system, "currentTimeMillis");
        staticCall("java/lang/System", "nanoTime", "()J", system, "nanoTime");
        // A SUT that collects garbage in a loop must not pay for full
        // collections on every test; Runtime.gc() is already mocked the same way.
        staticCall("java/lang/System", "gc", "()V", system, "gc");
        staticCall("java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", system, "identityHashCode");

        // Thread.sleep is replaced through the mock list (MockThread).

        // java/lang/Object
        instanceToStatic("java/lang/Object", "hashCode", "()I", system, "hashCode");
        instanceToStatic("java/lang/Object", "toString", "()Ljava/lang/String;", system, "toString");

        // java/lang/Object - monitor methods
        String mockObjectWait = PackageInfo.getNameWithSlash(
                org.evosuite.runtime.mock.java.lang.MockObjectWait.class);
        instanceToStatic("java/lang/Object", "wait", "()V", mockObjectWait, "wait");
        instanceToStatic("java/lang/Object", "wait", "(J)V", mockObjectWait, "wait");
        instanceToStatic("java/lang/Object", "wait", "(JI)V", mockObjectWait, "wait");
        instanceToStatic("java/lang/Object", "notify", "()V", mockObjectWait, "notify");
        instanceToStatic("java/lang/Object", "notifyAll", "()V", mockObjectWait, "notifyAll");

        staticCall("java/lang/Math", "random", "()D",
                PackageInfo.getNameWithSlash(org.evosuite.runtime.Random.class), "nextDouble");

        // java/lang/Class
        String reflection = PackageInfo.getNameWithSlash(org.evosuite.runtime.Reflection.class);
        String[][] classCalls = {
                {"getClasses", "()[Ljava/lang/Class;"},
                {"getAnnotations", "()[Ljava/lang/annotation/Annotation;"},
                {"getFields", "()[Ljava/lang/reflect/Field;"},
                {"getConstructors", "()[Ljava/lang/reflect/Constructor;"},
                {"getMethods", "()[Ljava/lang/reflect/Method;"},
                {"getDeclaredClasses", "()[Ljava/lang/Class;"},
                {"getDeclaredAnnotations", "()[Ljava/lang/annotation/Annotation;"},
                {"getDeclaredFields", "()[Ljava/lang/reflect/Field;"},
                {"getDeclaredConstructors", "()[Ljava/lang/reflect/Constructor;"},
                {"getDeclaredMethods", "()[Ljava/lang/reflect/Method;"},
                {"getInterfaces", "()[Ljava/lang/Class;"},
                {"getModifiers", "()I"},
        };
        for (String[] call : classCalls) {
            instanceToStatic("java/lang/Class", call[0], call[1], reflection, call[0]);
        }
    }

    /**
     * Registers the replacement of a static method by a static method with the same descriptor.
     */
    private void staticCall(String owner, String name, String desc, String target, String targetName) {
        addReplacementCall(new MethodCallReplacement(owner, name, desc, Opcodes.INVOKESTATIC,
                target, targetName, desc, false, false));
    }

    /**
     * Registers the replacement of an instance method by a static method that takes the receiver
     * as its first parameter.
     */
    private void instanceToStatic(String owner, String name, String desc, String target, String targetName) {
        addReplacementCall(new MethodCallReplacement(owner, name, desc, Opcodes.INVOKEVIRTUAL,
                target, targetName, "(L" + owner + ";" + desc.substring(1), false, false));
    }

    /**
     * Registers the replacement of the given constructors of {@code owner} by static factory
     * methods of the same name and parameters that return the new instance.
     */
    private void constructorsToStatic(String owner, String target, String targetName, String... ctorDescs) {
        for (String desc : ctorDescs) {
            addReplacementCall(new MethodCallReplacement(owner, "<init>", desc, Opcodes.INVOKESTATIC,
                    target, targetName, desc.replace(")V", ")L" + owner + ";"), true, true));
        }
    }

    /**
     * setAccessible(boolean) is caller-sensitive and therefore redeclared in Field, Method
     * and Constructor; javac emits the receiver's static type as owner for both it and the
     * final trySetAccessible(), so all four owners need entries.
     */
    private void addReflectionAccessCalls() {

        String reflectionAccess = PackageInfo.getNameWithSlash(org.evosuite.runtime.ReflectionAccess.class);
        String[] owners = {"java/lang/reflect/AccessibleObject", "java/lang/reflect/Field",
                "java/lang/reflect/Method", "java/lang/reflect/Constructor"};

        for (String owner : owners) {
            addReplacementCall(new MethodCallReplacement(owner, "setAccessible", "(Z)V", Opcodes.INVOKEVIRTUAL,
                    reflectionAccess, "setAccessible", "(Ljava/lang/reflect/AccessibleObject;Z)V", false, false));
            addReplacementCall(new MethodCallReplacement(owner, "trySetAccessible", "()Z", Opcodes.INVOKEVIRTUAL,
                    reflectionAccess, "trySetAccessible", "(Ljava/lang/reflect/AccessibleObject;)Z", false, false));
        }

        addReplacementCall(new MethodCallReplacement("java/lang/reflect/AccessibleObject", "setAccessible",
                "([Ljava/lang/reflect/AccessibleObject;Z)V", Opcodes.INVOKESTATIC,
                reflectionAccess, "setAccessible", "([Ljava/lang/reflect/AccessibleObject;Z)V", false, false));
    }

    /**
     * Redirects the field-writing methods of {@code sun.misc.Unsafe} to {@link UnsafeGuard},
     * which refuses the writes that would corrupt the JVM instead of letting the garbage
     * collector find out. The receiver becomes the first argument of the replacement.
     */
    private void addUnsafeWriteCalls() {
        String guard = PackageInfo.getNameWithSlash(org.evosuite.runtime.UnsafeGuard.class);
        String[][] writes = {
                // name, parameters of the original, return type
                {"putObject", "Ljava/lang/Object;JLjava/lang/Object;", "V"},
                {"putObjectVolatile", "Ljava/lang/Object;JLjava/lang/Object;", "V"},
                {"putOrderedObject", "Ljava/lang/Object;JLjava/lang/Object;", "V"},
                {"compareAndSwapObject", "Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;", "Z"},
                {"putBoolean", "Ljava/lang/Object;JZ", "V"},
                {"putByte", "Ljava/lang/Object;JB", "V"},
                {"putShort", "Ljava/lang/Object;JS", "V"},
                {"putChar", "Ljava/lang/Object;JC", "V"},
                {"putInt", "Ljava/lang/Object;JI", "V"},
                {"putIntVolatile", "Ljava/lang/Object;JI", "V"},
                {"putOrderedInt", "Ljava/lang/Object;JI", "V"},
                {"compareAndSwapInt", "Ljava/lang/Object;JII", "Z"},
                {"putLong", "Ljava/lang/Object;JJ", "V"},
                {"putLongVolatile", "Ljava/lang/Object;JJ", "V"},
                {"putOrderedLong", "Ljava/lang/Object;JJ", "V"},
                {"compareAndSwapLong", "Ljava/lang/Object;JJJ", "Z"},
                {"putFloat", "Ljava/lang/Object;JF", "V"},
                {"putDouble", "Ljava/lang/Object;JD", "V"},
                // absolute-address forms
                {"putByte", "JB", "V"},
                {"putShort", "JS", "V"},
                {"putChar", "JC", "V"},
                {"putInt", "JI", "V"},
                {"putLong", "JJ", "V"},
                {"putFloat", "JF", "V"},
                {"putDouble", "JD", "V"},
                {"putAddress", "JJ", "V"},
                // off-heap memory the writes above may target
                {"allocateMemory", "J", "J"},
                {"reallocateMemory", "JJ", "J"},
                {"freeMemory", "J", "V"},
                {"setMemory", "JJB", "V"},
                {"setMemory", "Ljava/lang/Object;JJB", "V"},
                {"copyMemory", "JJJ", "V"},
                {"copyMemory", "Ljava/lang/Object;JLjava/lang/Object;JJ", "V"},
        };
        for (String[] w : writes) {
            addReplacementCall(new MethodCallReplacement("sun/misc/Unsafe", w[0],
                    "(" + w[1] + ")" + w[2], Opcodes.INVOKEVIRTUAL,
                    guard, w[0], "(Ljava/lang/Object;" + w[1] + ")" + w[2], false, false));
        }
    }

    private void replaceAllInstanceMethodsWithStatic(Class<?> mockClass, Class<?> target) {

        /*
         * replace "fooInstance.bar(x)" with "MockFooClass.bar(fooInstance,x)"
         */

        for (Method m : ReflectionUtils.getMethods(target)) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }

            /*
             * TODO: should check Object methods, and see if they were mocked.
             * If not, we could just skip them. If other methods are not mocked,
             * should throw an exception?
             */
            Class<?>[] parameters = new Class<?>[m.getParameterCount() + 1];
            parameters[0] = target;
            int numParam = 1;
            for (Class<?> paramClass : m.getParameterTypes()) {
                parameters[numParam++] = paramClass;
            }

            try {
                Method mockMethod = mockClass.getMethod(m.getName(), parameters);
                if (Modifier.isStatic(mockMethod.getModifiers())) {
                    String desc = Type.getMethodDescriptor(m);
                    Type[] argumentTypes = Type.getArgumentTypes(m);
                    Type[] mockedArgumentTypes = new Type[argumentTypes.length + 1];
                    mockedArgumentTypes[0] = Type.getType(target);
                    for (int i = 0; i < argumentTypes.length; i++) {
                        mockedArgumentTypes[i + 1] = argumentTypes[i];
                    }
                    String mockedDesc = Type.getMethodDescriptor(Type.getReturnType(m), mockedArgumentTypes);
                    // Instance methods declared on an interface are called with INVOKEINTERFACE,
                    // not INVOKEVIRTUAL (eg java.nio.file.Path, java.lang.ProcessHandle).
                    int originalOpcode = target.isInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                    addReplacementCall(new MethodCallReplacement(target.getCanonicalName().replace('.', '/'),
                            m.getName(), desc, originalOpcode, mockClass.getCanonicalName().replace('.', '/'),
                            m.getName(), mockedDesc, false, false));
                } else {
                    logger.debug("Skipping non-static mock method: {}", mockMethod);
                }
            } catch (NoSuchMethodException e) {
                // logger.debug("Skipping method " + m.getName());
                continue;
            }
        }
    }

    private void replaceAllStaticMethods(Class<?> mockClass, Class<?> target) throws IllegalArgumentException {

        for (Method m : target.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }

            try {
                Method mockMethod = mockClass.getMethod(m.getName(), m.getParameterTypes());
                if (!Modifier.isStatic(mockMethod.getModifiers())) {
                    continue;
                }
            } catch (NoSuchMethodException e) {
                continue;
            }

            String desc = Type.getMethodDescriptor(m);
            addReplacementCall(new MethodCallReplacement(target.getCanonicalName().replace('.', '/'), m.getName(), desc,
                    Opcodes.INVOKESTATIC, mockClass.getCanonicalName().replace('.', '/'), m.getName(), desc, false,
                    false));
        }
    }

    /**
     * Replace all the constructors of {@code target} with a constructor (with
     * same input parameters) of mock subclass {@code mockClass}.
     *
     * @param mockClass the mock class
     * @param target the target class to be mocked
     * @throws IllegalArgumentException if mockClass is not a subclass of target
     */
    private void replaceAllConstructors(Class<?> mockClass, Class<?> target) throws IllegalArgumentException {

        if (!target.isAssignableFrom(mockClass)) {
            throw new IllegalArgumentException("Constructor replacement can be done only for subclasses. Class "
                    + mockClass + " is not an instance of " + target);
        }

        for (Constructor<?> constructor : ReflectionUtils.getDeclaredConstructors(mockClass)) {
            String desc = Type.getConstructorDescriptor(constructor);
            addSpecialReplacementCall(new MethodCallReplacement(target.getCanonicalName().replace('.', '/'), "<init>",
                    desc, Opcodes.INVOKESPECIAL, mockClass.getCanonicalName().replace('.', '/'), "<init>", desc, false,
                    false));
        }
    }

    /**
     * Replace all the constructors of {@code target} with a static call (with
     * same input parameters) of static mock class {@code mockClass}.
     *
     * @param mockClass the mock class
     * @param target the target class to be mocked
     * @throws IllegalArgumentException if initialization fails
     */
    private void replaceAllConstructorsWithStaticCalls(Class<?> mockClass, Class<?> target)
            throws IllegalArgumentException {

        for (Constructor<?> constructor : target.getConstructors()) {
            String desc = Type.getConstructorDescriptor(constructor);
            String replacementDesc = desc.substring(0, desc.length() - 1) + Type.getDescriptor(target);
            addReplacementCall(new MethodCallReplacement(target.getCanonicalName().replace('.', '/'), "<init>", desc,
                    Opcodes.INVOKESPECIAL, mockClass.getCanonicalName().replace('.', '/'), target.getSimpleName(),
                    replacementDesc, true, true));
        }
    }

    /**
     * Replace all the methods of {@code target} with a method (with same input
     * parameters) of mock subclass {@code mockClass}.
     *
     * @param mockClass the mock class
     * @param target the target class to be mocked
     * @throws IllegalArgumentException if mockClass is not a subclass of target
     */
    private void replaceAllInvokeSpecial(Class<?> mockClass, Class<?> target) throws IllegalArgumentException {

        if (!target.isAssignableFrom(mockClass)) {
            throw new IllegalArgumentException("Method replacement can be done only for subclasses. Class " + mockClass
                    + " is not an instance of " + target);
        }

        // logger.debug("Static Mock: " + mockClass.getCanonicalName() + " for " + target.getCanonicalName());
        for (Method method : mockClass.getMethods()) {
            String desc = Type.getMethodDescriptor(method);
            addSpecialReplacementCall(new MethodCallReplacement(target.getCanonicalName().replace('.', '/'),
                    method.getName(), desc, Opcodes.INVOKESPECIAL, mockClass.getCanonicalName().replace('.', '/'),
                    method.getName(), desc, false, false));
        }
    }

}
