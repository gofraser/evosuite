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

package org.evosuite.testcase.synthesizer;

import org.evosuite.graphs.cfg.BytecodeInstruction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * All the settings for calling a method to set a field with a specific value-setting-instruction.
 *
 * @author linyun
 */
public class ValueSettings {

    /**
     * The instruction to set the value.
     */
    public BytecodeInstruction valueSettingInstruction;

    /**
     * Call chain required to set the value of the variable, {@code <m_1, ..., m_n>}.
     * m_n is the method to call field setter instruction
     * m_1 is the method called by test
     */
    public List<BytecodeInstruction> callChain = new ArrayList<BytecodeInstruction>();

    /**
     * Relevant parameters to make the function call.
     */
    public Set<Integer> releventPrams = new HashSet<>();

    /**
     * Creates the settings for a value-setting instruction reached through a call chain.
     *
     * @param ins the instruction that sets the value
     * @param cascadingCallRelations the call chain leading to the instruction
     * @param targetClass the class under test
     * @param signature the signature of the method the chain starts from
     */
    public ValueSettings(BytecodeInstruction ins, List<BytecodeInstruction> cascadingCallRelations,
            String targetClass, String signature) {
        this.valueSettingInstruction = ins;
        this.callChain = new ArrayList<>(cascadingCallRelations);

        Set<Integer> validParamPos = DataDependencyUtil.checkValidParameterPositions(ins,
                targetClass, signature, new ArrayList<>(cascadingCallRelations));

        this.releventPrams = validParamPos;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ValueSettings) {
            ValueSettings v = (ValueSettings) obj;
            return this.valueSettingInstruction.equals(v.valueSettingInstruction);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return valueSettingInstruction.hashCode();
    }
}
