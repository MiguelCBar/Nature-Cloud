package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;
import javassist.expr.ExprEditor;
import javassist.expr.NewArray;
import javassist.expr.NewExpr;

public class RequestMetricsTool extends AbstractJavassistTool {

    public RequestMetricsTool(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);

        String methodName = block.getBehavior().getLongName();
        if (methodName.startsWith("pt.ulisboa.tecnico.cnv.common.metrics.")) {
            return;
        }

        int memoryWeight;

        try {
            memoryWeight = computeBlockMemoryAccessWeight(block);
        } catch (Exception e) {
            memoryWeight = 0;
        }

        String insertCode =
            "pt.ulisboa.tecnico.cnv.common.metrics.Metrics.countMemory(" + memoryWeight + "L);";

        block.behavior.insertAt(block.line, insertCode);
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);

        String methodName = behavior.getLongName();
        if (methodName.startsWith("pt.ulisboa.tecnico.cnv.common.metrics.")) {
            return;
        }

        // Memory metric: count allocations at runtime, scaled by actual allocation size.
        behavior.instrument(new ExprEditor() {
            @Override
            public void edit(NewExpr e) throws CannotCompileException {
                String className = e.getClassName();
                
                // Special handling for BufferedImage: capture width and height
                if (className.equals("java.awt.image.BufferedImage")) {
                    String code = "{ "
                                + "int width = $1; "
                                + "int height = $2; "
                                + "long pixelCount = (long)width * height; "
                                + "pt.ulisboa.tecnico.cnv.common.metrics.Metrics.countMemory(pixelCount * 4L); "
                                + "$_ = $proceed($$); "
                                + "}";
                    e.replace(code);
                } else {
                    int weight = memoryWeightForClass(className);
                    e.replace("{ " + "pt.ulisboa.tecnico.cnv.common.metrics.Metrics" + ".countMemory(" + weight + "L); $_ = $proceed($$); }");
                }
            }

            @Override
            public void edit(NewArray a) throws CannotCompileException {
                // Capture array dimensions and estimate memory: 8 bytes per element average
                int dimensions = 1;
                try {
                    dimensions = a.getCreatedDimensions();
                } catch (Exception ignored) {}

                String code = "{ ";
                // Build code to multiply dimensions
                if (dimensions == 1) {
                    code += "long arraySize = (long)$1; ";
                } else if (dimensions == 2) {
                    code += "long arraySize = (long)$1 * $2; ";
                } else if (dimensions == 3) {
                    code += "long arraySize = (long)$1 * $2 * $3; ";
                } else {
                    // Conservative fallback for very high dimensions
                    code += "long arraySize = 100; ";
                }
                code += "pt.ulisboa.tecnico.cnv.common.metrics.Metrics.countMemory(arraySize * 8L); "
                      + "$_ = $proceed($$); "
                      + "}";

                a.replace(code);
            }
        });
    }

    private int memoryWeightForClass(String className) {
        if (className == null) {
            return 1;
        }

        // Tiny or control-like allocations
        if (className.endsWith("Exception")) {
            return 1;
        }
        if (className.equals("java.lang.Object")) {
            return 1;
        }

        // Small but relevant objects
        if (className.equals("java.lang.StringBuilder")) {
            return 1;
        }
        if (className.equals("java.math.BigInteger")) {
            return 2;
        }

        // Collections / containers
        if (className.equals("java.util.Vector")
                || className.equals("java.util.ArrayList")
                || className.equals("java.util.HashMap")
                || className.equals("java.util.LinkedList")) {
            return 3;
        }

        // Heavy image/buffer-like allocations
        if (className.equals("java.awt.image.BufferedImage")
                || className.contains("BufferedImage")
                || className.contains("ByteBuffer")
                || className.contains("IntBuffer")) {
            return 6;
        }

        return 1;
    }

    private int computeBlockMemoryAccessWeight(BasicBlock block) throws Exception {
        CtBehavior behavior = block.getBehavior();
        MethodInfo mi = behavior.getMethodInfo2();
        CodeAttribute ca = mi.getCodeAttribute();
        if (ca == null) {
            return 0;
        }

        CodeIterator it = ca.iterator();
        int start = block.getPosition();
        int end = start + block.getLength();
        int score = 0;

        while (it.hasNext()) {
            int pos = it.next();
            if (pos < start || pos >= end) {
                continue;
            }

            int opcode = it.byteAt(pos);
            score += memoryOpcodeWeight(opcode);
        }

        return score;
    }

    private int memoryOpcodeWeight(int opcode) {
        switch (opcode) {
            // Field accesses: very relevant for memory traffic
            case Opcode.GETFIELD:
            case Opcode.PUTFIELD:
            case Opcode.GETSTATIC:
            case Opcode.PUTSTATIC:
                return 3;

            // Array element accesses: also memory-heavy
            case Opcode.AALOAD:
            case Opcode.AASTORE:
            case Opcode.BALOAD:
            case Opcode.BASTORE:
            case Opcode.CALOAD:
            case Opcode.CASTORE:
            case Opcode.DALOAD:
            case Opcode.DASTORE:
            case Opcode.FALOAD:
            case Opcode.FASTORE:
            case Opcode.IALOAD:
            case Opcode.IASTORE:
            case Opcode.LALOAD:
            case Opcode.LASTORE:
            case Opcode.SALOAD:
            case Opcode.SASTORE:
                return 3;

            // Local variable loads/stores: lighter memory signal
            case Opcode.ILOAD:
            case Opcode.LLOAD:
            case Opcode.FLOAD:
            case Opcode.DLOAD:
            case Opcode.ALOAD:
            case Opcode.ISTORE:
            case Opcode.LSTORE:
            case Opcode.FSTORE:
            case Opcode.DSTORE:
            case Opcode.ASTORE:
                return 1;

            default:
                return 0;
        }
    }
}