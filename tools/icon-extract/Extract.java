import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 从 material-icons-extended 的 XxxKt.class 中提取 ImageVector 构造参数 + path 命令序列，
 * 生成等价 Kotlin 源码，避免手抄贝塞尔曲线数据。
 *
 * 输出格式（每行一条）：
 *   ### Name|pkg|autoMirror|defaultW|defaultH|viewportW|viewportH
 *   PATH cmd(a, b, ...)
 */
public class Extract {

    static final Map<String, Integer> ARITY = new HashMap<>();

    static {
        ARITY.put("moveTo", 2);
        ARITY.put("moveToRelative", 2);
        ARITY.put("lineTo", 2);
        ARITY.put("lineToRelative", 2);
        ARITY.put("horizontalLineTo", 1);
        ARITY.put("horizontalLineToRelative", 1);
        ARITY.put("verticalLineTo", 1);
        ARITY.put("verticalLineToRelative", 1);
        ARITY.put("curveTo", 6);
        ARITY.put("curveToRelative", 6);
        ARITY.put("reflectiveCurveTo", 4);
        ARITY.put("reflectiveCurveToRelative", 4);
        ARITY.put("quadTo", 4);
        ARITY.put("quadToRelative", 4);
        ARITY.put("arcTo", 7);
        ARITY.put("arcToRelative", 7);
        ARITY.put("close", 0);
    }

    static String f2s(float f) {
        if (f == (long) f && Math.abs(f) < 1e7f) return ((long) f) + ".0f";
        return Float.toString(f) + "f";
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        List<Path> files = new ArrayList<>();
        Files.walk(root).filter(p -> p.toString().endsWith(".class")).forEach(files::add);
        Collections.sort(files);
        for (Path p : files) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            ClassNode cn = new ClassNode();
            new ClassReader(Files.readAllBytes(p)).accept(cn, 0);
            for (MethodNode mn : cn.methods) {
                if (!mn.name.startsWith("get")) continue;
                if (!mn.desc.endsWith("Landroidx/compose/ui/graphics/vector/ImageVector;")) continue;
                emit(cn, mn, rel);
                break;
            }
        }
    }

    static void emit(ClassNode cn, MethodNode mn, String rel) {
        List<Float> stack = new ArrayList<>();
        List<String> cmds = new ArrayList<>();
        List<List<String>> segs = new ArrayList<>();
        boolean inPath = false;
        List<Float> builderFloats = new ArrayList<>();
        List<Float> dims = new ArrayList<>();
        int autoMirror = 0;
        Integer lastInt = null;
        int autoMirrorLocal = -1;

        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode) {
                TypeInsnNode t = (TypeInsnNode) insn;
                if (t.getOpcode() == Opcodes.NEW && t.desc.contains("PathBuilder")) inPath = true;
                if (t.getOpcode() == Opcodes.NEW && t.desc.contains("ImageVector$Builder")) inPath = false;
                continue;
            }
            if (insn instanceof VarInsnNode) {
                VarInsnNode v = (VarInsnNode) insn;
                if (v.getOpcode() == Opcodes.ISTORE && autoMirrorLocal < 0 && lastInt != null && v.var >= 1 && v.var <= 3) {
                    // 首次出现的整型局部变量赋值，通常是 autoMirror 槽（name 占 1，autoMirror 占 2）
                    if (v.var == 2) autoMirror = lastInt;
                    autoMirrorLocal = v.var;
                }
                continue;
            }
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (m.name.equals("getNodes") && m.owner.endsWith("PathBuilder")) {
                    // 每段 path 以 getNodes() 收尾（随后传给 addPath），此处切段
                    if (!cmds.isEmpty()) { segs.add(new ArrayList<>(cmds)); cmds.clear(); }
                    inPath = false;
                    continue;
                }
                if (m.owner.endsWith("ImageVector$Builder") && m.name.equals("<init>")) {
                    builderFloats.clear();
                    builderFloats.addAll(dims);
                    stack.clear();
                    continue;
                }
                if (m.owner.endsWith("androidx/compose/ui/unit/Dp") && m.name.startsWith("constructor-impl")) {
                    // 每个尺寸参数都是 `ldc float; Dp.constructor-impl` 形式，取栈顶即真实值
                    if (!stack.isEmpty()) {
                        dims.add(stack.remove(stack.size() - 1));
                    }
                    continue;
                }
                if (inPath && m.owner.endsWith("/PathBuilder")) {
                    Integer a = ARITY.get(m.name);
                    if (a == null) continue;
                    if (a == 0) {
                        cmds.add("close()");
                    } else {
                        List<Float> sub = new ArrayList<>(stack.subList(stack.size() - a, stack.size()));
                        for (int i = 0; i < a; i++) stack.remove(stack.size() - 1);
                        StringBuilder sb = new StringBuilder(m.name).append('(');
                        for (int i = 0; i < a; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(f2s(sub.get(i)));
                        }
                        sb.append(')');
                        cmds.add(sb.toString());
                    }
                    continue;
                }
                if (inPath && m.owner.endsWith("PathBuilder") && m.name.equals("<init>")) {
                    // PathBuilder 构造清空栈（避免残留）
                    stack.clear();
                }
                continue;
            }
            if (insn instanceof LdcInsnNode) {
                Object c = ((LdcInsnNode) insn).cst;
                if (c instanceof Float) stack.add((Float) c);
                else if (c instanceof Integer) { stack.add((float) (Integer) c); lastInt = (Integer) c; }
                else if (c instanceof Double) stack.add(((Double) c).floatValue());
                continue;
            }
            if (insn instanceof InsnNode) {
                int op = insn.getOpcode();
                if (op >= Opcodes.ICONST_0 && op <= Opcodes.ICONST_5) {
                    int v = op - Opcodes.ICONST_0;
                    stack.add((float) v);
                    lastInt = v;
                } else if (op == Opcodes.FCONST_0) stack.add(0f);
                else if (op == Opcodes.FCONST_1) stack.add(1f);
                else if (op == Opcodes.FCONST_2) stack.add(2f);
                continue;
            }
            if (insn instanceof IntInsnNode) {
                IntInsnNode i = (IntInsnNode) insn;
                if (i.getOpcode() == Opcodes.BIPUSH || i.getOpcode() == Opcodes.SIPUSH) {
                    stack.add((float) i.operand);
                    lastInt = i.operand;
                }
            }
        }

        while (dims.size() < 4) dims.add(24f);

        String pkg = rel.substring(0, rel.lastIndexOf('/')).replace('/', '.');
        String cls = rel.substring(rel.lastIndexOf('/') + 1).replace("Kt.class", "");

        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(cls).append('|').append(pkg)
          .append('|').append(autoMirror).append('|').append(segs.size())
          .append('|').append(dims.get(0)).append('|').append(dims.get(1))
          .append('|').append(dims.get(2)).append('|').append(dims.get(3))
          .append('\n');
        for (int si = 0; si < segs.size(); si++) {
            sb.append("SEG ").append(si).append('\n');
            for (String c : segs.get(si)) sb.append("PATH ").append(c).append('\n');
        }
        System.out.print(sb);
    }
}
