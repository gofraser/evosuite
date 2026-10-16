package org.evosuite.graphs.interprocedural.var;

import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.interprocedural.ConstructionPath;
import org.evosuite.graphs.interprocedural.Relation;
import org.evosuite.utils.MethodUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This class should detailedly describe the dependent variables, including whether it is
 * a parameter, field, global variables, etc.
 * @author linyun
 *
 */
public abstract class DepVariable {

    private static final Logger logger = LoggerFactory.getLogger(DepVariable.class);

    private static final String OTHER_TYPE = "unknown";

    private String className;
    private String varName;
    private BytecodeInstruction instruction;

    private String recommendedImplementation;

    private int type;

    public static final int PARAMETER = 1;
    public static final int STATIC_FIELD = 2;
    public static final int INSTANCE_FIELD = 3;
    public static final int THIS = 4;
    public static final int ARRAY_ELEMENT = 5;
    public static final int OTHER = 6;

    public static int OPERAND_NUM_LIMIT = 30;

    /**
     * storing children
     * given a child, this variable is its i-th operand (assume that no instruction takes over
     * OPERAND_NUM_LIMIT operands).
     */
    @SuppressWarnings("unchecked")
    private List<DepVariable>[] relations = new ArrayList[OPERAND_NUM_LIMIT];

    /**
     * storing parents
     * given a parent, this variable takes it its i-th operand (assume that no instruction takes over
     * OPERAND_NUM_LIMIT operands).
     */
    @SuppressWarnings("unchecked")
    private List<DepVariable>[] reverseRelations = new ArrayList[OPERAND_NUM_LIMIT];

    protected DepVariable(BytecodeInstruction ins) {
        this.className = ins.getClassName();
        this.setInstruction(ins);
    }


    public boolean isConstant() {
        return this.instruction.isConstant();
    }

    public boolean isStateVariable() {
        return this.isStaticField() || this.isInstaceField() || this.isLoadArrayElement();
    }

    /**
     * Infers the kind of relation between this variable and a child that uses it as an operand.
     *
     * @param childVar        the variable using this variable as an operand
     * @param operandPosition the operand position at which the child uses this variable
     * @return one of the {@link Relation} constants
     */
    public String inferRelationWithChild(DepVariable childVar, int operandPosition) {
        BytecodeInstruction instruction = childVar.getInstruction();
        AbstractInsnNode insNode = instruction.getASMNode();

        int type = insNode.getType();
        String relation = null;
        if (type == AbstractInsnNode.FIELD_INSN) {
            relation = Relation.FIELD;
        } else {
            if (instruction.isArrayLoadInstruction() && operandPosition == 0) {
                relation = Relation.ARRAY_ELEMENT;
            } else {
                relation = Relation.OTHER;
            }
        }

        return relation;
    }

    @Override
    public String toString() {
        return "DepVariable [\nclassName=" + getClassName()  + "\ntype=" + getTypeString()
                + "\nvarName=" + varName + "\ninstruction=" + getInstruction() + "]";
    }

    protected void setType(int type) {
        this.type = type;
    }

    public boolean isParameter() {
        return this.instruction.isParameter();
    }

    public boolean isMethodCall() {
        return this.instruction.isMethodCall();
    }

    /**
     * Returns a short label for the kind of this variable.
     *
     * @return the label, such as {@code PARAMETER} or {@code FIELD_S}
     */
    public String getTypeString() {
        if (type == DepVariable.PARAMETER) {
            return "PARAMETER";
        } else if (type == DepVariable.INSTANCE_FIELD) {
            return "FIELD_i";
        } else if (type == DepVariable.STATIC_FIELD) {
            return "FIELD_S";
        } else if (type == DepVariable.THIS) {
            return "THIS";
        } else if (type == DepVariable.ARRAY_ELEMENT) {
            return "ARRAY_ELE";
        } else {
            return "OTHER";
        }

    }

    public BytecodeInstruction getInstruction() {
        return instruction;
    }

    public void setInstruction(BytecodeInstruction instruction) {
        this.instruction = instruction;
    }

    public boolean isStaticField() {
        return this.instruction.getASMNode().getOpcode() == Opcodes.GETSTATIC;
    }

    public boolean isInstaceField() {
        return this.instruction.getASMNode().getOpcode() == Opcodes.GETFIELD;
    }

    public boolean isLoadArrayElement() {
        return this.instruction.isArrayLoadInstruction();
    }


    public boolean referenceToThis() {
        return this.instruction.loadsReferenceToThis();
    }


    /**
     * Records that {@code outputVar} uses this variable as its operand at the given position, and the reverse.
     *
     * @param outputVar the variable that consumes this variable
     * @param position  the operand position of {@code outputVar} at which this variable is used
     */
    public void buildRelation(DepVariable outputVar, int position) {
        List<DepVariable> list = this.getRelations()[position];
        if (list == null) {
            list = new ArrayList<DepVariable>();
        }

        if (!list.contains(outputVar)) {
            list.add(outputVar);
        }

        this.getRelations()[position] = list;

        outputVar.addReverseRelation(this, position);
    }


    private void addReverseRelation(DepVariable inputVar, int position) {
        List<DepVariable> list = this.reverseRelations[position];
        if (list == null) {
            list = new ArrayList<DepVariable>();
        }

        if (!list.contains(inputVar)) {
            list.add(inputVar);
        }

        this.reverseRelations[position] = list;
    }

    /**
     * Hash on the underlying instruction so it matches {@link #equals(Object)} (which compares
     * instructions). Hashing on {@link #type} instead collapses every variable of the same kind
     * into one bucket, degrading set/map operations on large graphs to linear scans.
     */
    @Override
    public int hashCode() {
        return getInstruction() == null ? 0 : getInstruction().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DepVariable)) {
            return false;
        }
        DepVariable var = (DepVariable) obj;
        if (this.getInstruction() == null || var.getInstruction() == null) {
            return this == obj;
        }
        return var.getInstruction().equals(this.getInstruction());
    }


    public int getType() {
        return type;
    }


    public String getClassName() {
        return className;
    }


    @SuppressWarnings("unchecked")
    private void getRootVar(Map<DepVariable, ArrayList<ConstructionPath>> roots, DepVariable parent, DepVariable child,
            Map<DepVariable, List<DepVariable>> visited, ArrayList<DepVariable> partialPath,
            ArrayList<Integer> operandPosList) {
        List<DepVariable> visitedDirections = visited.get(parent);
        if (visitedDirections == null) {
            visitedDirections = new ArrayList<DepVariable>();
        }

        if (visitedDirections.contains(child)) {
            return;
        }

        visitedDirections.add(child);
        visited.put(parent, visitedDirections);

        if (parent.getType() == DepVariable.THIS
                || parent.getType() == DepVariable.PARAMETER
                || parent.getType() == DepVariable.STATIC_FIELD) {
            ArrayList<ConstructionPath> pathList = roots.get(parent);
            if (pathList == null) {
                pathList = new ArrayList<ConstructionPath>();
            }

            ConstructionPath path = new ConstructionPath(partialPath, operandPosList);
            if (!pathList.contains(path)) {
                pathList.add(path);
                roots.put(parent, pathList);
            }

        } else {
            for (int i = 0; i < parent.reverseRelations.length; i++) {

                if (parent.reverseRelations[i] == null) {
                    continue;
                }

                for (DepVariable grandPar : parent.reverseRelations[i]) {
                    ArrayList<DepVariable> newParialPath = (ArrayList<DepVariable>) partialPath.clone();
                    ArrayList<Integer> newOperandPosList = (ArrayList<Integer>) operandPosList.clone();

                    newParialPath.add(grandPar);
                    newOperandPosList.add(i);
                    getRootVar(roots, grandPar, parent, visited, newParialPath, newOperandPosList);
                }
            }
        }
    }

    /**
     * Returns the root variables, each along with the paths from the root variable to this variable.
     *
     * @return the construction paths, keyed by root variable
     */
    @SuppressWarnings("unchecked")
    public Map<DepVariable, ArrayList<ConstructionPath>> getRootVars() {
        Map<DepVariable, ArrayList<ConstructionPath>> roots = new LinkedHashMap<>();
        // keep a map to avoid circle in the graph, <a node, its child>
        Map<DepVariable, List<DepVariable>> visited = new LinkedHashMap<>();

        ArrayList<DepVariable> path = new ArrayList<DepVariable>();
        path.add(this);
        for (int i = 0; i < this.reverseRelations.length; i++) {
            List<DepVariable> directParents = this.reverseRelations[i];
            if (directParents != null && !directParents.isEmpty()) {
                DepVariable p = directParents.get(0);
                String relation = p.inferRelationWithChild(this, i);
                if (relation.equals(Relation.FIELD) || relation.equals(Relation.ARRAY_ELEMENT)
                        || (this.isMethodCall() && relation.equals(Relation.OTHER))) {
                    for (DepVariable parent : directParents) {
                        ArrayList<DepVariable> partialPath = (ArrayList<DepVariable>) path.clone();
                        partialPath.add(parent);

                        ArrayList<Integer> operandPosList = new ArrayList<Integer>();
                        operandPosList.add(i);

                        getRootVar(roots, parent, this, visited, partialPath, operandPosList);
                    }
                }

            }
        }

        if (roots.isEmpty()) {
            ArrayList<ConstructionPath> list = new ArrayList<>();
            ArrayList<DepVariable> partialPath = new ArrayList<>();
            partialPath.add(this);
            ConstructionPath p = new ConstructionPath(partialPath, new ArrayList<>());
            list.add(p);
            roots.put(this, list);
        }

        return roots;
    }

    public void setName(String name) {
        this.varName = name;
    }

    public String getName() {
        return this.varName;
    }

    /**
     * Returns the exact order of the parameter regarding static method, starting at 1.
     *
     * @return the order, or -1 if this variable is not a parameter
     */
    public int getParamOrder() {
        if (this.instruction.isParameter()) {
            int order = this.instruction.getParameterPosition() + 1;
            return order;
        }

        return -1;
    }


    /**
     * Finds the path from this variable to the given variable.
     *
     * @param var the variable to reach
     * @return the path, or null if such a path does not exist
     */
    public ConstructionPath findPath(DepVariable var) {
        ArrayList<DepVariable> path = new ArrayList<DepVariable>();
        ArrayList<Integer> positions = new ArrayList<Integer>();

        path.add(this);

        ConstructionPath constructionPath = findPath(path, positions, var);
        return constructionPath;
    }


    @SuppressWarnings("unchecked")
    private ConstructionPath findPath(ArrayList<DepVariable> path, ArrayList<Integer> positions, DepVariable var) {
        DepVariable lastNode = path.get(path.size() - 1);

        if (lastNode.equals(var)) {
            return new ConstructionPath(path, positions);
        }

        ConstructionPath foundPath = null;
        for (int i = 0; i < lastNode.getRelations().length; i++) {
            List<DepVariable> children = lastNode.getRelations()[i];
            if (children != null) {
                for (DepVariable child : children) {
                    if (path.contains(child)) {
                        continue;
                    }

                    if (child.equals(var)) {
                        path.add(child);
                        positions.add(i);
                        foundPath = new ConstructionPath(path, positions);
                        break;
                    } else {
                        ArrayList<DepVariable> clonePath = (ArrayList<DepVariable>) path.clone();
                        ArrayList<Integer> clonePositions = (ArrayList<Integer>) positions.clone();
                        clonePath.add(child);
                        clonePositions.add(i);

                        ConstructionPath p = findPath(clonePath, clonePositions, var);
                        if (p != null) {
                            foundPath = p;
                            break;
                        }
                    }
                }

                if (foundPath != null) {
                    break;
                }
            }
        }

        return foundPath;
    }

    public String getRecommendedImplementation() {
        return recommendedImplementation;
    }

    public void setRecommendedImplementation(String recommendedImplementation) {
        this.recommendedImplementation = recommendedImplementation;
    }

    public List<DepVariable>[] getRelations() {
        return relations;
    }

    public List<DepVariable>[] getReverseRelations() {
        return reverseRelations;
    }

    /**
     * Checks whether this variable will be used as an index-th operand for a given node childVar.
     *
     * @param childVar the node that may use this variable as an operand
     * @param index    the operand position
     * @return true if this variable is the operand at that position of {@code childVar}
     */
    public boolean isSupportOperandFor(DepVariable childVar, int index) {
        List<DepVariable> list = this.relations[index];

        if (list == null) {
            return false;
        }

        for (DepVariable v : list) {
            if (v.equals(childVar)) {
                return true;
            }
        }

        return false;
    }

    public String getMethodName() {
        return this.getClassName() + "#" + this.getInstruction().getMethodName();
    }

    /**
     * Returns the simple class name and the method name without its descriptor, separated by {@code #}.
     *
     * @return the short method name
     */
    public String getShortMethodName() {
        String clazz = this.getClassName();
        String shortClass = clazz.substring(clazz.lastIndexOf(".") + 1, clazz.length());
        String method = this.getInstruction().getMethodName();
        String shortMethod = method.substring(0, method.indexOf("("));

        return shortClass + "#" + shortMethod;
    }

    /**
     * Returns the operand position at which the given variable feeds this variable.
     *
     * @param prevNode the variable whose instruction produces an operand of this variable
     * @return the operand position, or -1 if there is none
     */
    public int getInputOrder(DepVariable prevNode) {
        for (int i = 0; i < this.reverseRelations.length; i++) {
            List<DepVariable> list = this.reverseRelations[i];
            if (list != null) {
                for (DepVariable node : list) {
                    if (node.getInstruction().equals(prevNode.getInstruction())) {
                        return i;
                    }
                }
            }

        }

        return -1;
    }


    /**
     * Infers the data type of this variable from its instruction.
     *
     * @return the type name, or the generic object type if it cannot be determined
     */
    public String getDataType() {
        if (this.referenceToThis()) {
            return MethodUtil.convertType(this.className);
        } else if (this.isParameter()) {
            String methodSig = this.instruction.getMethodName();
            String[] splitted = MethodUtil.parseSignature(methodSig);
            int order = this.getParamOrder();
            if (order != -1) {
                String parameterType = splitted[order - 1];
                return parameterType;
            } else {
                return OTHER_TYPE;
            }
        } else if (this.isStaticField()) {
            return MethodUtil.convertType(this.instruction.getFieldType());
        } else if (this.isInstaceField()) {
            return MethodUtil.convertType(this.instruction.getFieldType());
        } else if (this.isLoadArrayElement()) {
            return OTHER_TYPE;
        } else if (this.isMethodCall()) {
            String methodSig = this.getInstruction().getCalledMethod();
            String[] splitted = MethodUtil.parseSignature(methodSig);

            return splitted[splitted.length - 1];
        } else {
            int slotNum = this.getInstruction().getLocalVariableSlot();
            if (slotNum != -1) {
                String methodSig = this.instruction.getMethodName();
                String[] splitted = MethodUtil.parseSignature(methodSig);
                int paramNum = splitted.length - 1;
                if (slotNum < paramNum) {
                    return splitted[slotNum];
                } else {
                    BytecodeInstruction ins = extractedDefinition(slotNum);
                    if (ins != null) {
                        List<BytecodeInstruction> sourceInsList = ins.getSourceOfStackInstructionList(0);
                        if (sourceInsList != null && !sourceInsList.isEmpty()) {
                            BytecodeInstruction sourceIns = sourceInsList.get(0);
                            DepVariable node = DepVariableFactory.createVariableInstance(sourceIns);
                            String type = node.getDataType();
                            return type;
                        }
                    }
                }
            }

            String type = inferType(this.getInstruction().getInstructionType());
            return type;

        }
    }

    private BytecodeInstruction extractedDefinition(int slotNum) {
        BytecodeInstruction ins = this.getInstruction();
        while (ins != null) {
            int opcode = ins.getASMNode().getOpcode();
            if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                int storeSlot = ins.getLocalVariableSlot();
                if (storeSlot == slotNum) {
                    return ins;
                }
            }
            ins = ins.getPreviousInstruction();
        }
        return null;
    }


    private String inferType(String instructionType) {
        switch (instructionType) {
            case "ALOAD":
            case "ASTORE":
            case "AALOAD":
            case "AASTORE":
            case "ACONST_NULL":
            case "ALOAD_0":
            case "ALOAD_1":
            case "ALOAD_2":
            case "ALOAD_3":
            case "ANEWARRAY":
            case "ARETURN":
            case "ARRAYLENGTH":
            case "ASTORE_0":
            case "ASTORE_1":
            case "ASTORE_2":
            case "ASTORE_3":
            case "BREAKPOINT":
            case "ATHROW":
                return OTHER_TYPE;
            case "BLOAD":
            case "BSTORE":
                return "byte";
            case "BIPUSH":
                return "int";
            case "CALOAD":
                return "char";
            case "CASTORE":
                return "char";
            case "CHECKCAST":
                AbstractInsnNode node = this.instruction.getASMNode();
                if (node instanceof TypeInsnNode) {
                    TypeInsnNode typeNode = (TypeInsnNode) node;
                    String type = typeNode.desc.contains("/") ? "L" + typeNode.desc : typeNode.desc;
                    return MethodUtil.convertType(type);
                }
                return OTHER_TYPE;
            case "D2F":
                return "double";
            case "D2I":
                return "int";
            case "D2L":
                return "long";
            case "DADD":
                return "double";
            case "DASTORE":
                return "double";
            case "DCMPG":
            case "DCMPL":
            case "DCONST_0":
            case "DCONST_1":
            case "DDIV":
            case "DLOAD":
            case "DLOAD_0":
            case "DLOAD_1":
            case "DLOAD_2":
            case "DLOAD_3":
            case "DMUL":
            case "DNEG":
            case "DREM":
            case "DRETURN":
            case "DSTORE":
            case "DSTORE_0":
            case "DSTORE_1":
            case "DSTORE_2":
            case "DSTORE_3":
            case "DSUB":
                return "double";
            case "DUP":
            case "DUP_X1":
            case "DUP_X2":
            case "DUP2":
            case "DUP2_X1":
            case "DUP2_X2":
                return OTHER_TYPE;
            case "F2D":
                return "double";
            case "F2I":
                return "int";
            case "F2L":
                return "long";
            case "FADD":
            case "FALOAD":
            case "FASTORE":
            case "FCMPG":
            case "FCMPL":
            case "FCONST_0":
            case "FCONST_1":
            case "FCONST_2":
            case "FDIV":
            case "FLOAD":
            case "FLOAD_0":
            case "FLOAD_1":
            case "FLOAD_2":
            case "FLOAD_3":
            case "FMUL":
            case "FNEG":
            case "FREM":
            case "FRETURN":
            case "FSTORE":
            case "FSTORE_0":
            case "FSTORE_1":
            case "FSTORE_2":
            case "FSTORE_3":
            case "FSUB":
                return "float";
            case "I2B":
                return "byte";
            case "I2C":
                return "char";
            case "I2D":
                return "double";
            case "I2F":
                return "float";
            case "I2L":
                return "long";
            case "I2S":
                return "short";
            case "IADD":
            case "IALOAD":
            case "IAND":
            case "IASTORE":
            case "ICONST_M1":
            case "ICONST_0":
            case "ICONST_1":
            case "ICONST_2":
            case "ICONST_3":
            case "ICONST_4":
            case "ICONST_5":
            case "IDIV":
            case "IINC":
            case "ILOAD":
            case "ILOAD_0":
            case "ILOAD_1":
            case "ILOAD_2":
            case "ILOAD_3":
            case "IMPDEP1":
            case "IMPDEP2":
            case "IMUL":
            case "INEG":
                return "int";
            case "IOR":
            case "IREM":
            case "ISHL":
            case "ISHR":
            case "ISTORE":
            case "ISTORE_0":
            case "ISTORE_1":
            case "ISTORE_2":
            case "ISTORE_3":
            case "ISUB":
            case "IUSHR":
            case "IXOR":
                return "int";
            case "L2D":
                return "double";
            case "L2F":
                return "float";
            case "L2I":
                return "int";
            case "LADD":
            case "LALOAD":
            case "LAND":
            case "LASTORE":
            case "LCMP":
            case "LCONST_0":
            case "LCONST_1":
                return "long";
            case "LDC":
            case "LDC_W":
            case "LDC2_W":
                node = this.instruction.getASMNode();
                if (node instanceof LdcInsnNode) {
                    LdcInsnNode ldcNode = (LdcInsnNode) node;
                    Class<?> clazz = ldcNode.cst.getClass();
                    return clazz.getCanonicalName();
                }
                return "java.lang.Object";
            case "LDIV":
            case "LLOAD":
            case "LLOAD_0":
            case "LLOAD_1":
            case "LLOAD_2":
            case "LLOAD_3":
            case "LMUL":
            case "LNEG":
            case "LOR":
            case "LREM":
            case "LSHL":
            case "LSHR":
            case "LSTORE":
            case "LSTORE_0":
            case "LSTORE_1":
            case "LSTORE_2":
            case "LSTORE_3":
            case "LSUB":
            case "LUSHR":
            case "LXOR":
                return "long";
            case "SALOAD":
                return "short";
            case "GETFIELD":
            case "GETSTATIC":
                FieldInsnNode fieldNode = (FieldInsnNode) this.instruction.getASMNode();
                String desc = fieldNode.desc;
                return MethodUtil.convertType(desc);
            default:
                break;
        }

        return OTHER_TYPE;
    }
}
