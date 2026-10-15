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
package org.evosuite.eclipse.maven;

/**
 * The password of a server in Maven's settings.xml, or why there is none.
 *
 * @param outcome  whether the password was found, and if not, why
 * @param serverId the server's id
 * @param password the decrypted password, or {@code null} if it was not found
 * @param detail   what went wrong reading or decrypting, or {@code null}
 */
public record ServerPassword(Outcome outcome, String serverId, String password, String detail) {

    public enum Outcome {
        FOUND,
        /** m2e is not installed, so the settings cannot be read. */
        NO_M2E,
        /** Maven's settings cannot be read. */
        SETTINGS_UNREADABLE,
        NO_SERVER,
        /** The server has no password, or an empty one. */
        NO_PASSWORD,
        /** The password is encrypted, but cannot be decrypted, eg for want of settings-security.xml. */
        NOT_DECRYPTED
    }

    static ServerPassword found(String serverId, String password) {
        return new ServerPassword(Outcome.FOUND, serverId, password, null);
    }

    static ServerPassword missing(Outcome outcome, String serverId, String detail) {
        return new ServerPassword(outcome, serverId, null, detail);
    }

    public boolean isFound() {
        return outcome == Outcome.FOUND;
    }

    /**
     * Why there is no password, as a sentence for the user, eg "Maven's settings.xml has no
     * server llm."; {@code null} if it was found.
     */
    public String problem() {
        String detailed = detail == null || detail.isBlank() ? "" : ": " + detail.trim();
        switch (outcome) {
            case NO_M2E:
                return "Maven's settings.xml cannot be read without m2e (Maven support for Eclipse).";
            case SETTINGS_UNREADABLE:
                return "Maven's settings.xml cannot be read" + detailed + ".";
            case NO_SERVER:
                return "Maven's settings.xml has no server " + serverId + ".";
            case NO_PASSWORD:
                return "The server " + serverId + " in Maven's settings.xml has no password.";
            case NOT_DECRYPTED:
                return "The password of the server " + serverId + " in Maven's settings.xml cannot be decrypted"
                        + detailed + ".";
            default:
                return null;
        }
    }
}
