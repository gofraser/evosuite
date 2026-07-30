package com.examples.with.different.packagename.objectconstruction.tuning;

public class AliasedArrayAccessor {

    public static class Cell {
        public int state;
    }

    private final Cell[] cells;

    public AliasedArrayAccessor(Cell[] cells) {
        this.cells = cells;
    }

    private Cell getCell(int parameter) {
        return cells[parameter - 1];
    }

    public boolean accepts(int parameter) {
        return getCell(parameter).state == 7;
    }
}
