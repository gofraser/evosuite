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
}
