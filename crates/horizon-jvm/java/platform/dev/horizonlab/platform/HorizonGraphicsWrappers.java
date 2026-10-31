package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Mods' own subclasses of GuiGraphicsExtractor that wrap the one a screen draws with (REI's
 * {@code compat.GuiGraphics.of(graphics)}: a new extractor on the same render state), made
 * for a {@link HorizonGraphics}: a class made here, extending the mod's, whose every method
 * HorizonGraphics records (and {@code pose()}, so the wrapper's transforms are its) goes to the
 * HorizonGraphics. Without it the wrapper draws as vanilla does, through atlases the headless
 * client lacks, and nothing is recorded.
 */
public final class HorizonGraphicsWrappers {
    private HorizonGraphicsWrappers() {}

    /** What every wrapper made here is: the HorizonGraphics it records into. */
    public interface Wrapper {
        HorizonGraphics horizon$graphics();
    }

    /** The HorizonGraphics an extractor records into: itself, a wrapper's, or null. */
    public static HorizonGraphics of(Object extractor) {
        if (extractor instanceof HorizonGraphics graphics) return graphics;
        if (extractor instanceof Wrapper wrapper) return wrapper.horizon$graphics();
        return null;
    }

    private static final Map<Class<?>, java.lang.reflect.Constructor<?>> MADE = new ConcurrentHashMap<>();
    private static final java.util.Set<Class<?>> FAILED = ConcurrentHashMap.newKeySet();

    /**
     * {@code graphics} as an instance of {@code base} (a subclass of GuiGraphicsExtractor with a
     * public constructor taking a GuiGraphicsExtractor), or null if one can't be made.
     */
    public static Object wrap(Class<?> base, HorizonGraphics graphics) {
        try {
            var make = MADE.computeIfAbsent(base, HorizonGraphicsWrappers::define);
            return make.newInstance(graphics);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            if (FAILED.add(base)) {
                Natives.log("Horizon's client: " + base.getName() + " can't wrap Horizon's graphics: " + root);
                StackTraceElement[] frames = root.getStackTrace();
                for (int f = 0; f < Math.min(frames.length, 8); f++) Natives.log("    at " + frames[f]);
            }
            return null;
        }
    }

    private static java.lang.reflect.Constructor<?> define(Class<?> base) {
        String name = "dev/horizonlab/platform/generated/" + base.getName().replace('.', '_') + "$Horizon";
        String baseName = Type.getInternalName(base);
        String delegate = Type.getInternalName(HorizonGraphics.class);
        String extractor = Type.getDescriptor(GuiGraphicsExtractor.class);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return base.getClassLoader();
            }
        };
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, null, baseName,
                new String[] {Type.getInternalName(Wrapper.class)});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "horizon", "L" + delegate + ";", null, null).visitEnd();
        // (HorizonGraphics graphics): this.horizon = graphics; super(graphics). The field first:
        // the super constructor already calls methods this forwards (guiWidth).
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + delegate + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, name, "horizon", "L" + delegate + ";");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, baseName, "<init>", "(" + extractor + ")V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        java.util.Set<String> done = new java.util.HashSet<>();
        java.util.List<Method> forwarded = new java.util.ArrayList<>(java.util.List.of(HorizonGraphics.class.getDeclaredMethods()));
        try {
            forwarded.add(GuiGraphicsExtractor.class.getMethod("pose"));
        } catch (NoSuchMethodException e) {
            // No pose(): the wrapper keeps its own.
        }
        for (Method m : forwarded) {
            int mod = m.getModifiers();
            if (!Modifier.isPublic(mod) || Modifier.isStatic(mod) || m.isSynthetic() || m.isBridge()) continue;
            // Only methods of GuiGraphicsExtractor's (what a wrapper inherits), not the base's
            // finals.
            Method inherited;
            try {
                inherited = GuiGraphicsExtractor.class.getMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                continue;
            }
            if (Modifier.isFinal(inherited.getModifiers())) continue;
            try {
                if (Modifier.isFinal(base.getMethod(m.getName(), m.getParameterTypes()).getModifiers())) continue;
            } catch (NoSuchMethodException e) {
                continue;
            }
            String desc = Type.getMethodDescriptor(inherited);
            if (!done.add(m.getName() + desc)) continue;
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getName(), desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, name, "horizon", "L" + delegate + ";");
            int slot = 1;
            for (Type arg : Type.getArgumentTypes(desc)) {
                mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
                slot += arg.getSize();
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, delegate, m.getName(), desc, false);
            mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        // Wrapper.horizon$graphics(): the HorizonGraphics (GuiGraphicsMixin forwards vanilla's
        // private methods mods widen, which a subclass can't override, through it).
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "horizon$graphics", "()L" + delegate + ";", null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitFieldInsn(Opcodes.GETFIELD, name, "horizon", "L" + delegate + ";");
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        ClassLoader parent = base.getClassLoader();
        Class<?> made = new ClassLoader(parent) {
            Class<?> make() {
                return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.make();
        try {
            return made.getConstructor(HorizonGraphics.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
