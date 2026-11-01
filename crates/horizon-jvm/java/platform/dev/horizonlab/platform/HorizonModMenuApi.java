package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Mod Menu's API, for mods' settings screens without Mod Menu. Mods hand their settings screen
 * to Mod Menu through a "modmenu" entrypoint implementing {@code ModMenuApi}; without Mod Menu
 * installed that class can't load, and Horizon's Mods page would list nothing. When Mod Menu
 * isn't there, the API's interfaces (ModMenuApi, ConfigScreenFactory, UpdateChecker, as Mod Menu
 * declares them, with their default methods answering "none") are made here and put on the
 * mods' class path before any entrypoint is read. With Mod Menu installed, its own are used.
 */
final class HorizonModMenuApi {
    private HorizonModMenuApi() {}

    private static boolean done;

    private static final String API = "com/terraformersmc/modmenu/api/";
    private static final String SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";

    static synchronized void ensure() {
        if (done) return;
        done = true;
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("modmenu")) return;
        try {
            Path jar = Files.createTempFile("horizon-modmenu-api", ".jar");
            jar.toFile().deleteOnExit();
            try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
                write(out, API + "ConfigScreenFactory", configScreenFactory());
                write(out, API + "UpdateChecker", emptyInterface(API + "UpdateChecker"));
                write(out, API + "ModMenuApi", modMenuApi());
            }
            net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().addToClassPath(jar);
        } catch (Throwable t) {
            Natives.log("Horizon's client: Mod Menu's API couldn't be made: " + t);
        }
    }

    private static void write(JarOutputStream out, String name, byte[] bytes) throws java.io.IOException {
        out.putNextEntry(new JarEntry(name + ".class"));
        out.write(bytes);
        out.closeEntry();
    }

    private static ClassWriter iface(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null,
                "java/lang/Object", null);
        return cw;
    }

    private static byte[] emptyInterface(String name) {
        ClassWriter cw = iface(name);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code interface ConfigScreenFactory<S extends Screen> { S create(Screen parent); }} */
    private static byte[] configScreenFactory() {
        ClassWriter cw = iface(API + "ConfigScreenFactory");
        cw.visitAnnotation("Ljava/lang/FunctionalInterface;", true).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "create", "(" + SCREEN + ")" + SCREEN, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code interface ModMenuApi}, its default methods answering "none". */
    private static byte[] modMenuApi() {
        ClassWriter cw = iface(API + "ModMenuApi");
        defaultReturningNull(cw, "getModConfigScreenFactory", "()L" + API + "ConfigScreenFactory;");
        defaultReturningNull(cw, "getUpdateChecker", "()L" + API + "UpdateChecker;");
        defaultReturningEmptyMap(cw, "getProvidedConfigScreenFactories");
        defaultReturningEmptyMap(cw, "getProvidedUpdateCheckers");
        MethodVisitor badges = cw.visitMethod(Opcodes.ACC_PUBLIC, "attachModpackBadges", "(Ljava/util/function/Consumer;)V", null, null);
        badges.visitCode();
        badges.visitInsn(Opcodes.RETURN);
        badges.visitMaxs(0, 0);
        badges.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void defaultReturningNull(ClassWriter cw, String name, String descriptor) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void defaultReturningEmptyMap(ClassWriter cw, String name) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()Ljava/util/Map;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
