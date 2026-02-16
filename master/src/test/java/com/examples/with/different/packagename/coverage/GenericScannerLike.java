package com.examples.with.different.packagename.coverage;

import java.util.ArrayList;
import java.util.LinkedList;

public class GenericScannerLike<E extends GenericScannerLike.ExecLexem> {

    public static class ExecLexem {
        public final String token;

        public ExecLexem(String token) {
            this.token = token;
        }
    }

    public static class AdvLexem extends ExecLexem {
        public AdvLexem(String token) {
            super(token);
        }
    }

    public ArrayList<E> fieldScope = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public Class<LinkedList<String>> publicListType = (Class<LinkedList<String>>) (Class<?>) LinkedList.class;

    public int printLexems(ArrayList<ExecLexem> values) {
        if (values == null) {
            return -1;
        }
        return values.isEmpty() ? 0 : 1;
    }

    public int printAdvLexems(ArrayList<AdvLexem> values) {
        if (values == null) {
            return -1;
        }
        return values.isEmpty() ? 0 : 2;
    }

    public int checkStructure(ArrayList<ExecLexem> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        ExecLexem first = values.get(0);
        if (first instanceof AdvLexem) {
            return 1;
        }
        return first.token.length() > 3 ? 2 : 3;
    }
}
