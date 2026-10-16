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
package com.examples.with.different.packagename;

/**
 * The shape of SF110's OAIVerb hierarchy: sibling subclasses of an abstract verb, exposed through
 * members typed as the abstract verb.
 */
public class SiblingVerbs {

    public abstract static class Verb {
        public static final Verb LIST_METADATA_FORMATS = new ListMetadataFormats();

        public static Verb listMetadataFormats() {
            return LIST_METADATA_FORMATS;
        }

        public abstract String execute();
    }

    public static class ListRecords extends Verb {
        @Override
        public String execute() {
            return "records";
        }
    }

    public static class ListMetadataFormats extends Verb {
        @Override
        public String execute() {
            return "formats";
        }
    }
}
