/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.mock.javax.swing;

import org.evosuite.runtime.mock.OverrideMock;
import org.evosuite.runtime.mock.javax.swing.filechooser.MockFileSystemView;
import javax.accessibility.AccessibleContext;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JRootPane;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileSystemView;
import javax.swing.filechooser.FileView;
import javax.swing.plaf.FileChooserUI;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Vector;

public class MockJFileChooser extends javax.swing.JFileChooser implements OverrideMock {

    private static final long serialVersionUID = 1062809726268959728L;

    private static final String uiClassID = "FileChooserUI";

    //---------------------
    // final static fields
    //---------------------

    public static final int OPEN_DIALOG = 0;
    public static final int SAVE_DIALOG = 1;
    public static final int CUSTOM_DIALOG = 2;
    public static final int CANCEL_OPTION = 1;
    public static final int APPROVE_OPTION = 0;
    public static final int ERROR_OPTION = -1;
    public static final int FILES_ONLY = 0;
    public static final int DIRECTORIES_ONLY = 1;
    public static final int FILES_AND_DIRECTORIES = 2;
    public static final String CANCEL_SELECTION = "CancelSelection";
    public static final String APPROVE_SELECTION = "ApproveSelection";
    public static final String APPROVE_BUTTON_TEXT_CHANGED_PROPERTY = "ApproveButtonTextChangedProperty";
    public static final String APPROVE_BUTTON_TOOL_TIP_TEXT_CHANGED_PROPERTY =
            "ApproveButtonToolTipTextChangedProperty";
    public static final String APPROVE_BUTTON_MNEMONIC_CHANGED_PROPERTY = "ApproveButtonMnemonicChangedProperty";
    public static final String CONTROL_BUTTONS_ARE_SHOWN_CHANGED_PROPERTY = "ControlButtonsAreShownChangedProperty";
    public static final String DIRECTORY_CHANGED_PROPERTY = "directoryChanged";
    public static final String SELECTED_FILE_CHANGED_PROPERTY = "SelectedFileChangedProperty";
    public static final String SELECTED_FILES_CHANGED_PROPERTY = "SelectedFilesChangedProperty";
    public static final String MULTI_SELECTION_ENABLED_CHANGED_PROPERTY = "MultiSelectionEnabledChangedProperty";
    public static final String FILE_SYSTEM_VIEW_CHANGED_PROPERTY = "FileSystemViewChanged";
    public static final String FILE_VIEW_CHANGED_PROPERTY = "fileViewChanged";
    public static final String FILE_HIDING_CHANGED_PROPERTY = "FileHidingChanged";
    public static final String FILE_FILTER_CHANGED_PROPERTY = "fileFilterChanged";
    public static final String FILE_SELECTION_MODE_CHANGED_PROPERTY = "fileSelectionChanged";
    public static final String ACCESSORY_CHANGED_PROPERTY = "AccessoryChangedProperty";
    public static final String ACCEPT_ALL_FILE_FILTER_USED_CHANGED_PROPERTY = "acceptAllFileFilterUsedChanged";
    public static final String DIALOG_TITLE_CHANGED_PROPERTY = "DialogTitleChangedProperty";
    public static final String DIALOG_TYPE_CHANGED_PROPERTY = "DialogTypeChangedProperty";
    public static final String CHOOSABLE_FILE_FILTER_CHANGED_PROPERTY = "ChoosableFileFilterChangedProperty";

    private static final String SHOW_HIDDEN_PROP = "awt.file.showHiddenFiles";

    //--------------------------------------------
    // private fields redefined from superclass
    //--------------------------------------------

    private String dialogTitle = null;
    private String approveButtonText = null;
    private String approveButtonToolTipText = null;
    private int approveButtonMnemonic = 0;
    private Vector<FileFilter> filters = new Vector<>(5);
    private JDialog dialog = null;
    private int dialogType = OPEN_DIALOG;
    private int returnValue = ERROR_OPTION;
    private JComponent accessory = null;
    private FileView fileView = null;
    private boolean controlsShown = true;
    private boolean useFileHiding = true;
    private transient PropertyChangeListener showFilesListener = null;
    private int fileSelectionMode = FILES_ONLY;
    private boolean multiSelectionEnabled = false;
    private boolean useAcceptAllFileFilter = true;
    private boolean dragEnabled = false;
    private FileFilter fileFilter = null;
    private FileSystemView fileSystemView = null;
    private File currentDirectory = null;
    private File selectedFile = null;
    private File[] selectedFiles;

    /*
     * flag used to avoid executing initializing code in the superclass
     * constructors
     */
    private boolean finishedInitializingSuperclass;

    /* ------------------------
     * Constructors
     * ------------------------
     */

    /**
     * Constructs a {@code MockJFileChooser} pointing to the user's default directory.
     */
    public MockJFileChooser() {
        this((File) null, null);
    }

    /**
     * Constructs a {@code MockJFileChooser} using the given path.
     *
     * @param currentDirectoryPath a {@code String} giving the path to a file or directory
     */
    public MockJFileChooser(String currentDirectoryPath) {
        this(currentDirectoryPath, null);
    }

    /**
     * Constructs a {@code MockJFileChooser} using the given {@code File} as the path.
     *
     * @param currentDirectory a {@code File} object specifying the path to a file or directory
     */
    public MockJFileChooser(File currentDirectory) {
        this(currentDirectory, null);
    }

    /**
     * Constructs a {@code MockJFileChooser} using the given {@code FileSystemView}.
     *
     * @param fsv a {@code FileSystemView}
     */
    public MockJFileChooser(FileSystemView fsv) {
        this((File) null, fsv);
    }

    /**
     * Constructs a {@code MockJFileChooser} using the given current directory and {@code FileSystemView}.
     *
     * @param currentDirectory a {@code File} object specifying the path to a file or directory
     * @param fsv              a {@code FileSystemView}
     */
    public MockJFileChooser(File currentDirectory, FileSystemView fsv) {

        super();
        finishedInitializingSuperclass = true;

        putClientProperty("FileChooser.useShellFolder", Boolean.FALSE);

        setup(fsv);
        setCurrentDirectory(currentDirectory);
    }

    /**
     * Constructs a {@code MockJFileChooser} using the given current directory path and {@code FileSystemView}.
     *
     * @param currentDirectoryPath a {@code String} specifying the path to a file or directory
     * @param fsv                  a {@code FileSystemView}
     */
    public MockJFileChooser(String currentDirectoryPath, FileSystemView fsv) {

        super();
        finishedInitializingSuperclass = true;

        /*
         * if don't use this, most likely we ll need to mock FileChooserUI as well
         */
        putClientProperty("FileChooser.useShellFolder", Boolean.FALSE);

        setup(fsv);
        if (currentDirectoryPath == null) {
            setCurrentDirectory(null);
        } else {
            setCurrentDirectory(fileSystemView.createFileObject(currentDirectoryPath));
        }
    }

    //-----------------------------------------------------

    @Override
    protected void setup(FileSystemView view) {

        if (!finishedInitializingSuperclass) {
            return;
        }

        installShowFilesListener();

        if (view == null) {
            view = MockFileSystemView.getFileSystemView();
        }
        setFileSystemView(view);
        updateUI();
        if (isAcceptAllFileFilterUsed()) {
            setFileFilter(getAcceptAllFileFilter());
        }
        enableEvents(AWTEvent.MOUSE_EVENT_MASK);
    }

    @Override
    public void setCurrentDirectory(File dir) {

        if (!finishedInitializingSuperclass) {
            return;
        }

        File oldValue = currentDirectory;

        if (dir != null && !dir.exists()) {
            dir = currentDirectory;
        }
        if (dir == null) {
            dir = getFileSystemView().getDefaultDirectory();
        }
        if (currentDirectory != null) {
            /* Verify the toString of object */
            if (this.currentDirectory.equals(dir)) {
                return;
            }
        }

        File prev = null;
        while (!isTraversable(dir) && prev != dir) {
            prev = dir;
            dir = getFileSystemView().getParentDirectory(dir);
        }
        if (dir == null && getFileSystemView().getDefaultDirectory() != null) {
            getFileSystemView().getDefaultDirectory().mkdirs();
            dir = getFileSystemView().getDefaultDirectory();
        }
        currentDirectory = dir;

        firePropertyChange(DIRECTORY_CHANGED_PROPERTY, oldValue, currentDirectory);
    }

    private void installShowFilesListener() {
        // Track native setting for showing hidden files
        Toolkit tk = Toolkit.getDefaultToolkit();
        Object showHiddenProperty = tk.getDesktopProperty(SHOW_HIDDEN_PROP);
        if (showHiddenProperty instanceof Boolean) {
            useFileHiding = !(Boolean) showHiddenProperty;
            showFilesListener = new MockWeakPCL(this);
            tk.addPropertyChangeListener(SHOW_HIDDEN_PROP, showFilesListener);
        }
    }

    /**
     * Sets the dragEnabled property.
     *
     * @param b the value to which the dragEnabled property is to be set
     * @throws HeadlessException if b is true and GraphicsEnvironment.isHeadless() returns true
     */
    public void setDragEnabled(boolean b) {
        if (b && GraphicsEnvironment.isHeadless()) {
            throw new HeadlessException();
        }
        dragEnabled = b;
    }

    /**
     * Returns the value of the dragEnabled property.
     *
     * @return the value of the dragEnabled property
     */
    public boolean getDragEnabled() {
        return dragEnabled;
    }

    /**
     * Returns the selected file.
     *
     * @return the selected file
     */
    public File getSelectedFile() {
        return selectedFile;
    }

    /**
     * Sets the selected file.
     *
     * @param file the selected file
     */
    public void setSelectedFile(File file) {
        File oldValue = selectedFile;
        selectedFile = file;
        if (selectedFile != null) {
            if (file.isAbsolute() && !getFileSystemView().isParent(getCurrentDirectory(), selectedFile)) {
                setCurrentDirectory(selectedFile.getParentFile());
            }
            if (!isMultiSelectionEnabled() || selectedFiles == null || selectedFiles.length == 1) {
                ensureFileIsVisible(selectedFile);
            }
        }
        firePropertyChange(SELECTED_FILE_CHANGED_PROPERTY, oldValue, selectedFile);
    }

    /**
     * Returns a list of selected files if the file chooser is set to allow multi-selection.
     *
     * @return an array of selected {@code File} objects
     */
    public File[] getSelectedFiles() {
        if (selectedFiles == null) {
            return new File[0];
        } else {
            return selectedFiles.clone();
        }
    }

    /**
     * Sets the list of selected files if the file chooser is set to allow multi-selection.
     *
     * @param selectedFiles an array of {@code File} objects to be selected
     */
    public void setSelectedFiles(File[] selectedFiles) {
        File[] oldValue = this.selectedFiles;
        if (selectedFiles == null || selectedFiles.length == 0) {
            selectedFiles = null;
            this.selectedFiles = null;
            setSelectedFile(null);
        } else {
            this.selectedFiles = selectedFiles.clone();
            setSelectedFile(this.selectedFiles[0]);
        }
        firePropertyChange(SELECTED_FILES_CHANGED_PROPERTY, oldValue, selectedFiles);
    }

    /**
     * Returns the current directory.
     *
     * @return the current directory
     */
    public File getCurrentDirectory() {
        return currentDirectory;
    }

    /**
     * Changes the directory to be set to the parent of the current directory.
     */
    public void changeToParentDirectory() {
        selectedFile = null;
        File oldValue = getCurrentDirectory();
        setCurrentDirectory(getFileSystemView().getParentDirectory(oldValue));
    }

    /**
     * Tells the UI to rescan the current directory from the file system.
     */
    public void rescanCurrentDirectory() {
        getUI().rescanCurrentDirectory(this);
    }

    /**
     * Makes sure that the specified file is visible and not hidden.
     *
     * @param f a File object
     */
    public void ensureFileIsVisible(File f) {
        getUI().ensureFileIsVisible(this, f);
    }

    // **************************************
    // ***** JFileChooser Dialog methods *****
    // **************************************

    /**
     * Pops up an "Open File" file chooser dialog.
     *
     * @param parent the parent component of the dialog, can be null
     * @return the return state of the file chooser on popdown
     * @throws HeadlessException if GraphicsEnvironment.isHeadless() returns true.
     */
    public int showOpenDialog(Component parent) throws HeadlessException {
        setDialogType(OPEN_DIALOG);
        return showDialog(parent, null);
    }

    /**
     * Pops up a "Save File" file chooser dialog.
     *
     * @param parent the parent component of the dialog, can be null
     * @return the return state of the file chooser on popdown
     * @throws HeadlessException if GraphicsEnvironment.isHeadless() returns true.
     */
    public int showSaveDialog(Component parent) throws HeadlessException {
        setDialogType(SAVE_DIALOG);
        return showDialog(parent, null);
    }

    /**
     * Pops a custom file chooser dialog with a custom approve button.
     *
     * @param parent            the parent component of the dialog; can be null
     * @param approveButtonText the text of the {@code ApproveButton}
     * @return the return state of the file chooser on popdown
     * @throws HeadlessException if GraphicsEnvironment.isHeadless() returns true.
     */
    public int showDialog(Component parent, String approveButtonText)
            throws HeadlessException {
        if (dialog != null) {
            // Prevent to show second instance of dialog if the previous one still exists
            return JFileChooser.ERROR_OPTION;
        }

        if (approveButtonText != null) {
            setApproveButtonText(approveButtonText);
            setDialogType(CUSTOM_DIALOG);
        }
        dialog = createDialog(parent);
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                returnValue = CANCEL_OPTION;
            }
        });
        returnValue = ERROR_OPTION;
        rescanCurrentDirectory();

        dialog.show();
        firePropertyChange("JFileChooserDialogIsClosingProperty", dialog, null);

        // Remove all components from dialog. The MetalFileChooserUI.installUI() method (and other LAFs)
        // registers AWT listener for dialogs and produces memory leaks. It happens when
        // installUI invoked after the showDialog method.
        dialog.getContentPane().removeAll();
        dialog.dispose();
        dialog = null;
        return returnValue;
    }

    /**
     *  Adapted from JOptionPane.getWindowForComponent()
     */
    private static Window getWindowForComponent(Component parentComponent)
            throws HeadlessException {
        if (parentComponent == null) {
            return JOptionPane.getRootFrame();
        }
        if (parentComponent instanceof Frame || parentComponent instanceof Dialog) {
            return (Window) parentComponent;
        }
        return getWindowForComponent(parentComponent.getParent());
    }

    protected JDialog createDialog(Component parent) throws HeadlessException {
        FileChooserUI ui = getUI();
        String title = ui.getDialogTitle(this);
        putClientProperty(AccessibleContext.ACCESSIBLE_DESCRIPTION_PROPERTY,
                title);

        JDialog dialog;
        Window window = getWindowForComponent(parent);
        if (window instanceof Frame) {
            dialog = new JDialog((Frame)window, title, true);
        } else {
            dialog = new JDialog((Dialog)window, title, true);
        }
        dialog.setComponentOrientation(this.getComponentOrientation());

        Container contentPane = dialog.getContentPane();
        contentPane.setLayout(new BorderLayout());
        contentPane.add(this, BorderLayout.CENTER);

        if (JDialog.isDefaultLookAndFeelDecorated()) {
            boolean supportsWindowDecorations =
                    UIManager.getLookAndFeel().getSupportsWindowDecorations();
            if (supportsWindowDecorations) {
                dialog.getRootPane().setWindowDecorationStyle(JRootPane.FILE_CHOOSER_DIALOG);
            }
        }
        dialog.getRootPane().setDefaultButton(ui.getDefaultButton(this));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);

        return dialog;
    }

    /**
     * Returns true if the control buttons are shown.
     *
     * @return true if the control buttons are shown
     */
    public boolean getControlButtonsAreShown() {
        return controlsShown;
    }

    /**
     * Sets whether the control buttons are shown.
     *
     * @param b true if the control buttons should be shown
     */
    public void setControlButtonsAreShown(boolean b) {
        if (controlsShown == b) {
            return;
        }
        boolean oldValue = controlsShown;
        controlsShown = b;
        firePropertyChange(CONTROL_BUTTONS_ARE_SHOWN_CHANGED_PROPERTY, oldValue, controlsShown);
    }

    /**
     * Returns the type of this dialog.
     *
     * @return the type of dialog to be displayed
     */
    public int getDialogType() {
        return dialogType;
    }

    /**
     * Sets the type of this dialog.
     *
     * @param dialogType the type of dialog to be displayed
     * @throws IllegalArgumentException if dialogType is invalid
     */
    public void setDialogType(int dialogType) {
        if (this.dialogType == dialogType) {
            return;
        }
        if (!(dialogType == OPEN_DIALOG || dialogType == SAVE_DIALOG || dialogType == CUSTOM_DIALOG)) {
            throw new IllegalArgumentException("Incorrect Dialog Type: " + dialogType);
        }
        int oldValue = this.dialogType;
        this.dialogType = dialogType;
        if (dialogType == OPEN_DIALOG || dialogType == SAVE_DIALOG) {
            setApproveButtonText(null);
        }
        firePropertyChange(DIALOG_TYPE_CHANGED_PROPERTY, oldValue, dialogType);
    }

    /**
     * Sets the string that goes into the {@code MockJFileChooser} window's title bar.
     *
     * @param dialogTitle the new {@code String} for the title bar
     */
    public void setDialogTitle(String dialogTitle) {
        String oldValue = this.dialogTitle;
        this.dialogTitle = dialogTitle;
        if (dialog != null) {
            dialog.setTitle(dialogTitle);
        }
        firePropertyChange(DIALOG_TITLE_CHANGED_PROPERTY, oldValue, dialogTitle);
    }

    /**
     * Gets the string that goes into the {@code MockJFileChooser} window's title bar.
     *
     * @return the {@code String} for the title bar
     */
    public String getDialogTitle() {
        return dialogTitle;
    }

    // ************************************
    // ***** JFileChooser View Options *****
    // ************************************

    /**
     * Sets the tooltip text used in the {@code ApproveButton}.
     *
     * @param toolTipText the tooltip text for the approve button
     */
    public void setApproveButtonToolTipText(String toolTipText) {
        if (Objects.equals(approveButtonToolTipText, toolTipText)) {
            return;
        }
        String oldValue = approveButtonToolTipText;
        approveButtonToolTipText = toolTipText;
        firePropertyChange(APPROVE_BUTTON_TOOL_TIP_TEXT_CHANGED_PROPERTY, oldValue, approveButtonToolTipText);
    }

    /**
     * Returns the tooltip text used in the {@code ApproveButton}.
     *
     * @return the tooltip text for the approve button
     */
    public String getApproveButtonToolTipText() {
        return approveButtonToolTipText;
    }

    /**
     * Returns the approve button's mnemonic.
     *
     * @return an integer value for the mnemonic key
     */
    public int getApproveButtonMnemonic() {
        return approveButtonMnemonic;
    }

    /**
     * Sets the approve button's mnemonic using a numeric keycode.
     *
     * @param mnemonic an integer value for the mnemonic key
     */
    public void setApproveButtonMnemonic(int mnemonic) {
        if (approveButtonMnemonic == mnemonic) {
            return;
        }
        int oldValue = approveButtonMnemonic;
        approveButtonMnemonic = mnemonic;
        firePropertyChange(APPROVE_BUTTON_MNEMONIC_CHANGED_PROPERTY, oldValue, approveButtonMnemonic);
    }

    /**
     * Sets the approve button's mnemonic using a character.
     *
     * @param mnemonic a character value for the mnemonic key
     */
    public void setApproveButtonMnemonic(char mnemonic) {
        int vk = mnemonic;
        if (vk >= 'a' && vk <= 'z') {
            vk -= ('a' - 'A');
        }
        setApproveButtonMnemonic(vk);
    }

    /**
     * Sets the text used in the {@code ApproveButton} in the {@code FileChooserUI}.
     *
     * @param approveButtonText the text used in the {@code ApproveButton}
     */
    public void setApproveButtonText(String approveButtonText) {
        if (Objects.equals(this.approveButtonText, approveButtonText)) {
            return;
        }
        String oldValue = this.approveButtonText;
        this.approveButtonText = approveButtonText;
        firePropertyChange(APPROVE_BUTTON_TEXT_CHANGED_PROPERTY, oldValue, approveButtonText);
    }

    /**
     * Returns the text used in the {@code ApproveButton} in the {@code FileChooserUI}.
     *
     * @return the text used in the {@code ApproveButton}
     */
    public String getApproveButtonText() {
        return approveButtonText;
    }

    /**
     * Returns a list of user choosable file filters.
     *
     * @return a {@code FileFilter} array containing all the choosable file filters
     */
    public FileFilter[] getChoosableFileFilters() {
        FileFilter[] filterArray = new FileFilter[filters.size()];
        filters.copyInto(filterArray);
        return filterArray;
    }

    /**
     * Adds a filter to the list of user choosable file filters.
     *
     * @param filter the {@code FileFilter} to add to the choosable file filter list
     */
    public void addChoosableFileFilter(FileFilter filter) {
        if (filter != null && !filters.contains(filter)) {
            FileFilter[] oldValue = getChoosableFileFilters();
            filters.addElement(filter);
            firePropertyChange(CHOOSABLE_FILE_FILTER_CHANGED_PROPERTY, oldValue, getChoosableFileFilters());
            if (fileFilter == null && filters.size() == 1) {
                setFileFilter(filter);
            }
        }
    }

    /**
     * Removes a filter from the list of user choosable file filters.
     *
     * @param f the {@code FileFilter} to remove
     * @return true if the file filter was removed
     */
    public boolean removeChoosableFileFilter(FileFilter f) {
        if (filters.contains(f)) {
            if (getFileFilter() == f) {
                setFileFilter(null);
            }
            FileFilter[] oldValue = getChoosableFileFilters();
            filters.removeElement(f);
            firePropertyChange(CHOOSABLE_FILE_FILTER_CHANGED_PROPERTY, oldValue, getChoosableFileFilters());
            return true;
        } else {
            return false;
        }
    }

    /**
     * Resets the choosable file filter list to its starting state.
     */
    public void resetChoosableFileFilters() {
        FileFilter[] oldValue = getChoosableFileFilters();
        setFileFilter(null);
        filters.removeAllElements();
        if (isAcceptAllFileFilterUsed()) {
            addChoosableFileFilter(getAcceptAllFileFilter());
        }
        firePropertyChange(CHOOSABLE_FILE_FILTER_CHANGED_PROPERTY, oldValue, getChoosableFileFilters());
    }

    /**
     * Returns the {@code AcceptAll} file filter.
     *
     * @return the {@code AcceptAll} file filter
     */
    public FileFilter getAcceptAllFileFilter() {
        FileFilter filter = null;
        if (getUI() != null) {
            filter = getUI().getAcceptAllFileFilter(this);
        }
        return filter;
    }

    /**
     * Returns whether the {@code AcceptAll FileFilter} is used.
     *
     * @return true if the {@code AcceptAll FileFilter} is used
     */
    public boolean isAcceptAllFileFilterUsed() {
        return useAcceptAllFileFilter;
    }

    /**
     * Sets whether the {@code AcceptAll FileFilter} is used as an available choice in the choosable filter list.
     *
     * @param b true if the {@code AcceptAll FileFilter} is used
     */
    public void setAcceptAllFileFilterUsed(boolean b) {
        boolean oldValue = useAcceptAllFileFilter;
        useAcceptAllFileFilter = b;
        if (!b) {
            removeChoosableFileFilter(getAcceptAllFileFilter());
        } else {
            removeChoosableFileFilter(getAcceptAllFileFilter());
            addChoosableFileFilter(getAcceptAllFileFilter());
        }
        firePropertyChange(ACCEPT_ALL_FILE_FILTER_USED_CHANGED_PROPERTY, oldValue, useAcceptAllFileFilter);
    }

    /**
     * Returns the accessory component.
     *
     * @return the accessory component
     */
    public JComponent getAccessory() {
        return accessory;
    }

    /**
     * Sets the accessory component.
     *
     * @param newAccessory the new accessory component
     */
    public void setAccessory(JComponent newAccessory) {
        JComponent oldValue = accessory;
        accessory = newAccessory;
        firePropertyChange(ACCESSORY_CHANGED_PROPERTY, oldValue, accessory);
    }

    /**
     * Sets the file selection mode.
     *
     * @param mode the mode of selection
     * @throws IllegalArgumentException if mode is invalid
     */
    public void setFileSelectionMode(int mode) {
        if (fileSelectionMode == mode) {
            return;
        }

        if ((mode == FILES_ONLY) || (mode == DIRECTORIES_ONLY) || (mode == FILES_AND_DIRECTORIES)) {
            int oldValue = fileSelectionMode;
            fileSelectionMode = mode;
            firePropertyChange(FILE_SELECTION_MODE_CHANGED_PROPERTY, oldValue, fileSelectionMode);
        } else {
            throw new IllegalArgumentException("Incorrect Mode for file selection: " + mode);
        }
    }

    /**
     * Returns the current file selection mode.
     *
     * @return the mode of selection
     */
    public int getFileSelectionMode() {
        return fileSelectionMode;
    }

    /**
     * Returns true if files are selectable based on the current file selection mode.
     *
     * @return true if files are selectable
     */
    public boolean isFileSelectionEnabled() {
        return ((fileSelectionMode == FILES_ONLY) || (fileSelectionMode == FILES_AND_DIRECTORIES));
    }

    /**
     * Returns true if directories are selectable based on the current file selection mode.
     *
     * @return true if directories are selectable
     */
    public boolean isDirectorySelectionEnabled() {
        return ((fileSelectionMode == DIRECTORIES_ONLY) || (fileSelectionMode == FILES_AND_DIRECTORIES));
    }

    /**
     * Sets the file chooser to allow multiple file selections.
     *
     * @param b true if multiple files may be selected
     */
    public void setMultiSelectionEnabled(boolean b) {
        if (multiSelectionEnabled == b) {
            return;
        }
        boolean oldValue = multiSelectionEnabled;
        multiSelectionEnabled = b;
        firePropertyChange(MULTI_SELECTION_ENABLED_CHANGED_PROPERTY, oldValue, multiSelectionEnabled);
    }

    /**
     * Returns true if multiple files can be selected.
     *
     * @return true if multiple files can be selected
     */
    public boolean isMultiSelectionEnabled() {
        return multiSelectionEnabled;
    }

    /**
     * Returns true if file hiding is enabled.
     *
     * @return true if file hiding is enabled
     */
    public boolean isFileHidingEnabled() {
        return useFileHiding;
    }

    /**
     * Sets file hiding on or off.
     *
     * @param b true if file hiding is enabled
     */
    public void setFileHidingEnabled(boolean b) {
        // Dump showFilesListener since we'll ignore it from now on
        if (showFilesListener != null) {
            Toolkit.getDefaultToolkit().removePropertyChangeListener(SHOW_HIDDEN_PROP, showFilesListener);
            showFilesListener = null;
        }
        boolean oldValue = useFileHiding;
        useFileHiding = b;
        firePropertyChange(FILE_HIDING_CHANGED_PROPERTY, oldValue, useFileHiding);
    }

    /**
     * Sets the current file filter.
     *
     * @param filter the new current file filter to use
     */
    public void setFileFilter(FileFilter filter) {
        FileFilter oldValue = fileFilter;
        fileFilter = filter;
        if (filter != null) {
            if (isMultiSelectionEnabled() && selectedFiles != null && selectedFiles.length > 0) {
                Vector<File> fileList = new Vector<>();
                boolean failed = false;
                for (File file : selectedFiles) {
                    if (filter.accept(file)) {
                        fileList.add(file);
                    } else {
                        failed = true;
                    }
                }
                if (failed) {
                    setSelectedFiles((fileList.size() == 0) ? null : fileList.toArray(new File[fileList.size()]));
                }
            } else if (selectedFile != null && !filter.accept(selectedFile)) {
                setSelectedFile(null);
            }
        }
        firePropertyChange(FILE_FILTER_CHANGED_PROPERTY, oldValue, fileFilter);
    }

    /**
     * Returns the currently selected file filter.
     *
     * @return the current file filter
     */
    public FileFilter getFileFilter() {
        return fileFilter;
    }

    /**
     * Sets the file view to used to retrieve UI information.
     *
     * @param fileView the new file view
     */
    public void setFileView(FileView fileView) {
        FileView oldValue = this.fileView;
        this.fileView = fileView;
        firePropertyChange(FILE_VIEW_CHANGED_PROPERTY, oldValue, fileView);
    }

    /**
     * Returns the current file view.
     *
     * @return the current file view
     */
    public FileView getFileView() {
        return fileView;
    }

    // ******************************
    // *****FileView delegation *****
    // ******************************

    /**
     * Returns the filename.
     *
     * @param f the File
     * @return the filename
     */
    public String getName(File f) {
        String filename = null;
        if (f != null) {
            if (getFileView() != null) {
                filename = getFileView().getName(f);
            }

            FileView uiFileView = getUI().getFileView(this);

            if (filename == null && uiFileView != null) {
                filename = uiFileView.getName(f);
            }
        }
        return filename;
    }

    /**
     * Returns the file description.
     *
     * @param f the File
     * @return the file description
     */
    public String getDescription(File f) {
        String description = null;
        if (f != null) {
            if (getFileView() != null) {
                description = getFileView().getDescription(f);
            }

            FileView uiFileView = getUI().getFileView(this);

            if (description == null && uiFileView != null) {
                description = uiFileView.getDescription(f);
            }
        }
        return description;
    }

    /**
     * Returns the type description.
     *
     * @param f the File
     * @return the type description
     */
    public String getTypeDescription(File f) {
        String typeDescription = null;
        if (f != null) {
            if (getFileView() != null) {
                typeDescription = getFileView().getTypeDescription(f);
            }

            FileView uiFileView = getUI().getFileView(this);

            if (typeDescription == null && uiFileView != null) {
                typeDescription = uiFileView.getTypeDescription(f);
            }
        }
        return typeDescription;
    }

    /**
     * Returns the icon for this file or type of file.
     *
     * @param f the File
     * @return the icon for this file, or type of file
     */
    public Icon getIcon(File f) {
        Icon icon = null;
        if (f != null) {
            if (getFileView() != null) {
                icon = getFileView().getIcon(f);
            }

            FileView uiFileView = getUI().getFileView(this);

            if (icon == null && uiFileView != null) {
                icon = uiFileView.getIcon(f);
            }
        }
        return icon;
    }

    /**
     * Returns true if the file (directory) can be visited.
     *
     * @param f the File
     * @return true if the file can be visited
     */
    public boolean isTraversable(File f) {
        Boolean traversable = null;
        if (f != null) {
            if (getFileView() != null) {
                traversable = getFileView().isTraversable(f);
            }

            FileView uiFileView = getUI().getFileView(this);

            if (traversable == null && uiFileView != null) {
                traversable = uiFileView.isTraversable(f);
            }
            if (traversable == null) {
                traversable = getFileSystemView().isTraversable(f);
            }
        }
        return (traversable != null && traversable);
    }

    /**
     * Returns true if the file should be displayed.
     *
     * @param f the File
     * @return true if the file should be displayed
     */
    public boolean accept(File f) {
        boolean shown = true;
        if (f != null && fileFilter != null) {
            shown = fileFilter.accept(f);
        }
        return shown;
    }

    /**
     * Sets the file system view that the {@code MockJFileChooser} uses for
     * accessing and creating file system resources.
     *
     * @param fsv the new {@code FileSystemView}
     */
    public void setFileSystemView(FileSystemView fsv) {
        FileSystemView oldValue = fileSystemView;
        fileSystemView = fsv;
        firePropertyChange(FILE_SYSTEM_VIEW_CHANGED_PROPERTY, oldValue, fileSystemView);
    }

    /**
     * Returns the file system view.
     *
     * @return the {@code FileSystemView} object
     */
    public FileSystemView getFileSystemView() {
        return fileSystemView;
    }

    // **************************
    // ***** Event Handling *****
    // **************************

    /**
     * Called by the UI when the user hits the Approve button.
     */
    public void approveSelection() {
        returnValue = APPROVE_OPTION;
        if (dialog != null) {
            dialog.setVisible(false);
        }
        fireActionPerformed(APPROVE_SELECTION);
    }

    /**
     * Called by the UI when the user chooses the Cancel button.
     */
    public void cancelSelection() {
        returnValue = CANCEL_OPTION;
        if (dialog != null) {
            dialog.setVisible(false);
        }
        fireActionPerformed(CANCEL_SELECTION);
    }

    /**
     * Adds an {@code ActionListener} to the file chooser.
     *
     * @param l the listener to be added
     */
    public void addActionListener(ActionListener l) {
        listenerList.add(ActionListener.class, l);
    }

    /**
     * Removes an {@code ActionListener} from the file chooser.
     *
     * @param l the listener to be removed
     */
    public void removeActionListener(ActionListener l) {
        listenerList.remove(ActionListener.class, l);
    }

    /**
     * Returns an array of all the action listeners registered on this file chooser.
     *
     * @return all of this file chooser's {@code ActionListener}s
     */
    public ActionListener[] getActionListeners() {
        return listenerList.getListeners(ActionListener.class);
    }

    protected void fireActionPerformed(String command) {
        // Guaranteed to return a non-null array
        Object[] listeners = listenerList.getListenerList();
        long mostRecentEventTime = EventQueue.getMostRecentEventTime();
        int modifiers = 0;
        AWTEvent currentEvent = EventQueue.getCurrentEvent();
        if (currentEvent instanceof InputEvent) {
            modifiers = ((InputEvent)currentEvent).getModifiers();
        } else if (currentEvent instanceof ActionEvent) {
            modifiers = ((ActionEvent)currentEvent).getModifiers();
        }
        ActionEvent e = null;
        // Process the listeners last to first, notifying
        // those that are interested in this event
        for (int i = listeners.length - 2; i >= 0; i -= 2) {
            if (listeners[i] == ActionListener.class) {
                // Lazily create the event:
                if (e == null) {
                    e = new ActionEvent(this, ActionEvent.ACTION_PERFORMED,
                            command, mostRecentEventTime,
                            modifiers);
                }
                ((ActionListener) listeners[i + 1]).actionPerformed(e);
            }
        }
    }

    private static class MockWeakPCL implements PropertyChangeListener {
        WeakReference<MockJFileChooser> jfcRef;

        public MockWeakPCL(MockJFileChooser jfc) {
            jfcRef = new WeakReference<>(jfc);
        }

        public void propertyChange(PropertyChangeEvent ev) {
            assert ev.getPropertyName().equals(SHOW_HIDDEN_PROP);
            MockJFileChooser jfc = jfcRef.get();
            if (jfc == null) {
                // Our JFileChooser is no longer around, so we no longer need to
                // listen for PropertyChangeEvents.
                Toolkit.getDefaultToolkit().removePropertyChangeListener(SHOW_HIDDEN_PROP, this);
            } else {
                boolean oldValue = jfc.useFileHiding;
                jfc.useFileHiding = !(Boolean) ev.getNewValue();
                jfc.firePropertyChange(FILE_HIDING_CHANGED_PROPERTY, oldValue, jfc.useFileHiding);
            }
        }
    }

    // *********************************
    // ***** Pluggable L&F methods *****
    // *********************************

    /**
     * Resets the UI property to a value from the current look and feel.
     */
    public void updateUI() {
        if (isAcceptAllFileFilterUsed()) {
            removeChoosableFileFilter(getAcceptAllFileFilter());
        }

        FileChooserUI ui = ((FileChooserUI)UIManager.getUI(this));

        if (fileSystemView == null) {
            // We were probably deserialized
            setFileSystemView(MockFileSystemView.getFileSystemView());
        }
        setUI(ui);

        if (isAcceptAllFileFilterUsed()) {
            addChoosableFileFilter(getAcceptAllFileFilter());
        }
    }

    public String getUIClassID() {
        return uiClassID;
    }

    public FileChooserUI getUI() {
        return (FileChooserUI) ui;
    }

    private void readObject(java.io.ObjectInputStream in)
            throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        installShowFilesListener();
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
        FileSystemView fsv = null;

        if (isAcceptAllFileFilterUsed()) {
            //The AcceptAllFileFilter is UI specific, it will be reset by
            //updateUI() after deserialization
            removeChoosableFileFilter(getAcceptAllFileFilter());
        }
        if (fileSystemView.equals(MockFileSystemView.getFileSystemView())) {
            //The default FileSystemView is platform specific, it will be
            //reset by updateUI() after deserialization
            fsv = fileSystemView;
            fileSystemView = null;
        }
        s.defaultWriteObject();
        if (fsv != null) {
            fileSystemView = fsv;
        }
        if (isAcceptAllFileFilterUsed()) {
            addChoosableFileFilter(getAcceptAllFileFilter());
        }
        if (getUIClassID().equals(uiClassID)) {

            try {
                Method getWrite = JComponent.class.getMethod("getWriteObjCounter", JComponent.class);
                getWrite.setAccessible(true);
                byte count = (Byte) getWrite.invoke(null, this);
                //byte count = JComponent.getWriteObjCounter(this);

                Method setWrite = JComponent.class.getMethod("setWriteObjCounter", JComponent.class, Byte.TYPE);
                setWrite.setAccessible(true);
                setWrite.invoke(null, this, --count);
                //JComponent.setWriteObjCounter(this, --count);

                if (count == 0 && ui != null) {
                    ui.installUI(this);
                }
            } catch (Exception e) {
                throw new RuntimeException("Bug in EvoSuite", e);
            }

        }
    }

    protected String paramString() {
        String approveButtonTextString = (approveButtonText != null
                ? approveButtonText : "");
        String dialogTitleString = (dialogTitle != null
                ? dialogTitle : "");
        String dialogTypeString;
        if (dialogType == OPEN_DIALOG) {
            dialogTypeString = "OPEN_DIALOG";
        } else if (dialogType == SAVE_DIALOG) {
            dialogTypeString = "SAVE_DIALOG";
        } else if (dialogType == CUSTOM_DIALOG) {
            dialogTypeString = "CUSTOM_DIALOG";
        } else {
            dialogTypeString = "";
        }
        String returnValueString;
        if (returnValue == CANCEL_OPTION) {
            returnValueString = "CANCEL_OPTION";
        } else if (returnValue == APPROVE_OPTION) {
            returnValueString = "APPROVE_OPTION";
        } else if (returnValue == ERROR_OPTION) {
            returnValueString = "ERROR_OPTION";
        } else {
            returnValueString = "";
        }
        String useFileHidingString = (useFileHiding
                ? "true" : "false");
        String fileSelectionModeString;
        if (fileSelectionMode == FILES_ONLY) {
            fileSelectionModeString = "FILES_ONLY";
        } else if (fileSelectionMode == DIRECTORIES_ONLY) {
            fileSelectionModeString = "DIRECTORIES_ONLY";
        } else if (fileSelectionMode == FILES_AND_DIRECTORIES) {
            fileSelectionModeString = "FILES_AND_DIRECTORIES";
        } else {
            fileSelectionModeString = "";
        }
        String currentDirectoryString = (currentDirectory != null
                ? currentDirectory.toString() : "");
        String selectedFileString = (selectedFile != null
                ? selectedFile.toString() : "");

        /*
         * We cannot call super.super.paramString()
         * but anyway, this is done only for toString methods
         */
        return super.paramString() + ",MOCK OBJECT"
                + ",approveButtonText=" + approveButtonTextString
                + ",currentDirectory=" + currentDirectoryString
                + ",dialogTitle=" + dialogTitleString
                + ",dialogType=" + dialogTypeString
                + ",fileSelectionMode=" + fileSelectionModeString
                + ",returnValue=" + returnValueString
                + ",selectedFile=" + selectedFileString
                + ",useFileHiding=" + useFileHidingString;
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        return super.getAccessibleContext();
    }

}
