import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * 把 LDLib2 jar 里所有对 net.minecraft.resources.ResourceLocation 的
 * mojmap 静态工厂调用 (fromNamespaceAndPath / parse / withDefaultNamespace)
 * 重定向到 com.lowdragmc.lowdraglib2.utils.RLFactory 的同名方法.
 * 签名一致, 指令流与 StackMapFrame 无需改动.
 */
public class Patcher {
    static final String OLD_OWNER = "net/minecraft/resources/ResourceLocation";
    static final String RET = "Lnet/minecraft/resources/ResourceLocation;";
    static final String NEW_OWNER = "com/lowdragmc/lowdraglib2/utils/RLFactory";
    static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "fromNamespaceAndPath", "parse", "withDefaultNamespace"));

    public static void main(String[] args) throws Exception {
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        byte[] factory = Files.readAllBytes(Paths.get(args[2]));

        try (JarFile jf = new JarFile(in.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
            List<String> patched = new ArrayList<>();
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                // RLFactory.class 已在 jar 里则跳过 (重复打补丁时避免重复条目)
                if (e.getName().equals(NEW_OWNER + ".class")) continue;
                try (InputStream is = jf.getInputStream(e)) {
                    byte[] data = is.readAllBytes();
                    if (e.getName().endsWith(".class")) {
                        byte[] r = patch(data);
                        if (r != null) { data = r; patched.add(e.getName()); }
                    }
                    jos.putNextEntry(new JarEntry(e.getName()));
                    jos.write(data);
                    jos.closeEntry();
                }
            }
            // 加入工厂类
            jos.putNextEntry(new JarEntry(NEW_OWNER + ".class"));
            jos.write(factory);
            jos.closeEntry();
            System.out.println("patched classes: " + patched.size());
            patched.forEach(n -> System.out.println("  " + n));
        }
    }

    static byte[] patch(byte[] data) {
        final boolean[] hit = {false};
        ClassReader cr = new ClassReader(data);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC && owner.equals(OLD_OWNER)
                                && NAMES.contains(mname) && mdesc.endsWith(RET)) {
                            hit[0] = true;
                            super.visitMethodInsn(op, NEW_OWNER, mname, mdesc, false);
                        } else {
                            super.visitMethodInsn(op, owner, mname, mdesc, itf);
                        }
                    }
                };
            }
        }, 0);
        return hit[0] ? cw.toByteArray() : null;
    }
}
