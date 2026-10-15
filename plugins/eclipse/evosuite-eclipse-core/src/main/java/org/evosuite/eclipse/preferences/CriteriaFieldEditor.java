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
package org.evosuite.eclipse.preferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;

/**
 * The coverage criteria as checkboxes, one for each of EvoSuite's default criteria. The value
 * stays EvoSuite's colon-separated {@code -criterion} list, and the empty string, EvoSuite's
 * default, while all of them are ticked.
 *
 * <p>Criteria that a user entered by name before and that have no checkbox, such as
 * {@code STRONGMUTATION}, are kept, and listed below the checkboxes with a link to remove them.
 */
class CriteriaFieldEditor extends FieldEditor {

    /** EvoSuite's default criteria, {@code Properties.CRITERION}, with their names here. */
    static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("LINE", "Lines");
        DEFAULTS.put("BRANCH", "Branches");
        DEFAULTS.put("EXCEPTION", "Exceptions");
        DEFAULTS.put("WEAKMUTATION", "Weak mutation");
        DEFAULTS.put("OUTPUT", "Output values");
        DEFAULTS.put("METHOD", "Methods");
        DEFAULTS.put("METHODNOEXCEPTION", "Methods without exceptions");
        DEFAULTS.put("CBRANCH", "Context branches");
    }

    private static final int CHECKBOX_COLUMNS = 2;

    private Composite boxes;
    private final Map<String, Button> checkboxes = new LinkedHashMap<>();
    private Link othersLink;
    /** The criteria as loaded, whose order storing keeps. */
    private List<String> loaded = List.of();
    /** The criteria without a checkbox that are kept. */
    private List<String> others = List.of();
    private String value = "";
    private boolean valid = true;

    CriteriaFieldEditor(String name, String label, Composite parent) {
        init(name, label);
        createControl(parent);
    }

    /** The criteria in a stored value, in their order; empty for EvoSuite's default. */
    static List<String> parse(String value) {
        Set<String> criteria = new LinkedHashSet<>();
        for (String entry : value.split(":")) {
            String criterion = entry.trim();
            if (!criterion.isEmpty()) {
                String upper = criterion.toUpperCase(Locale.ROOT);
                criteria.add(DEFAULTS.containsKey(upper) ? upper : criterion);
            }
        }
        return List.copyOf(criteria);
    }

    /**
     * The value to store: the empty string if exactly EvoSuite's default criteria are ticked,
     * and otherwise the ticked criteria and {@code others}, in the order of {@code previous},
     * the criteria as loaded, followed by the ones not in it.
     */
    static String format(List<String> previous, Set<String> ticked, List<String> others) {
        if (others.isEmpty() && ticked.containsAll(DEFAULTS.keySet())) {
            return "";
        }
        Set<String> criteria = new LinkedHashSet<>();
        for (String criterion : previous) {
            if (ticked.contains(criterion) || others.contains(criterion)) {
                criteria.add(criterion);
            }
        }
        DEFAULTS.keySet().stream().filter(ticked::contains).forEach(criteria::add);
        criteria.addAll(others);
        return String.join(":", criteria);
    }

    /** What would be stored. */
    String getValue() {
        return value;
    }

    @Override
    protected void adjustForNumColumns(int numColumns) {
        ((GridData) boxes.getLayoutData()).horizontalSpan = numColumns - 1;
    }

    @Override
    protected void doFillIntoGrid(Composite parent, int numColumns) {
        Label label = getLabelControl(parent);
        label.setLayoutData(new GridData(SWT.BEGINNING, SWT.BEGINNING, false, false));

        boxes = new Composite(parent, SWT.NONE);
        boxes.setFont(parent.getFont());
        GridLayout layout = new GridLayout(CHECKBOX_COLUMNS, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = 12;
        boxes.setLayout(layout);
        boxes.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, numColumns - 1, 1));
        DEFAULTS.forEach((criterion, name) -> {
            Button checkbox = new Button(boxes, SWT.CHECK);
            checkbox.setFont(parent.getFont());
            checkbox.setText(name);
            checkbox.setToolTipText(criterion);
            checkbox.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> changed()));
            checkboxes.put(criterion, checkbox);
        });

        othersLink = new Link(boxes, SWT.WRAP);
        othersLink.setFont(parent.getFont());
        GridData othersLayout = new GridData(SWT.FILL, SWT.TOP, true, false, CHECKBOX_COLUMNS, 1);
        othersLayout.exclude = true;
        othersLink.setLayoutData(othersLayout);
        othersLink.setVisible(false);
        othersLink.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            others = List.of();
            showOthers();
            changed();
        }));
    }

    @Override
    public int getNumberOfControls() {
        return 2;
    }

    @Override
    protected void doLoad() {
        show(getPreferenceStore().getString(getPreferenceName()));
    }

    @Override
    protected void doLoadDefault() {
        show(getPreferenceStore().getDefaultString(getPreferenceName()));
        update();
    }

    @Override
    protected void doStore() {
        getPreferenceStore().setValue(getPreferenceName(), value);
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    @Override
    public void setFocus() {
        checkboxes.values().iterator().next().setFocus();
    }

    @Override
    public void setEnabled(boolean enabled, Composite parent) {
        super.setEnabled(enabled, parent);
        checkboxes.values().forEach(c -> c.setEnabled(enabled));
        othersLink.setEnabled(enabled);
    }

    private void show(String stored) {
        loaded = parse(stored);
        List<String> unknown = new ArrayList<>();
        for (String criterion : loaded) {
            if (!DEFAULTS.containsKey(criterion)) {
                unknown.add(criterion);
            }
        }
        others = List.copyOf(unknown);
        boolean all = loaded.isEmpty();
        checkboxes.forEach((criterion, checkbox) -> checkbox.setSelection(all || loaded.contains(criterion)));
        showOthers();
        value = current();
    }

    private void showOthers() {
        othersLink.setText(others.isEmpty() ? "" : "Also " + String.join(", ", others) + " (<a>remove</a>)");
        othersLink.setVisible(!others.isEmpty());
        ((GridData) othersLink.getLayoutData()).exclude = others.isEmpty();
        boxes.getParent().layout(true, true);
    }

    private Set<String> ticked() {
        Set<String> ticked = new LinkedHashSet<>();
        checkboxes.forEach((criterion, checkbox) -> {
            if (checkbox.getSelection()) {
                ticked.add(criterion);
            }
        });
        return ticked;
    }

    private String current() {
        return format(loaded, ticked(), others);
    }

    private void changed() {
        setPresentsDefaultValue(false);
        update();
    }

    /** Tells the page about a new value, and that no criterion at all is not one. */
    private void update() {
        String oldValue = value;
        value = current();
        boolean wasValid = valid;
        valid = !ticked().isEmpty() || !others.isEmpty();
        if (valid) {
            clearErrorMessage();
        } else {
            showErrorMessage("Select at least one coverage criterion.");
        }
        if (valid != wasValid) {
            fireStateChanged(IS_VALID, wasValid, valid);
        }
        if (!value.equals(oldValue)) {
            fireValueChanged(VALUE, oldValue, value);
        }
    }
}
