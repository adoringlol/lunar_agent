package com.mcctimer.agent;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * Generates a GuiScreen subclass at runtime using ASM and injects it via
 * MethodHandles.Lookup.defineClass(). The generated EditScreen overrides
 * drawScreen to call TimerRenderer.handleEditInput() each frame for smooth
 * dragging.
 */
public class EditScreenFactory {

    private static Class<?> editScreenClass;
    private static boolean attempted = false;

    public static Object createEditScreen() {
        try {
            if (!attempted) {
                attempted = true;
                injectEditScreen();
            }
            if (editScreenClass != null) {
                return editScreenClass.getDeclaredConstructor().newInstance();
            }
        } catch (Exception e) {
            AgentLogger.log("Failed to create EditScreen instance!");
            AgentLogger.log(e);
        }
        return null;
    }

    private static void injectEditScreen() {
        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null) {
                AgentLogger.log("EditScreenFactory: No context classloader!");
                return;
            }

            // Find GuiScreen class
            String guiScreenInternalName;
            Class<?> guiScreenClass;
            try {
                guiScreenClass = Class.forName("net.minecraft.client.gui.GuiScreen", true, gameLoader);
                guiScreenInternalName = "net/minecraft/client/gui/GuiScreen";
            } catch (ClassNotFoundException e) {
                try {
                    guiScreenClass = Class.forName("avb", true, gameLoader);
                    guiScreenInternalName = "avb";
                } catch (ClassNotFoundException e2) {
                    AgentLogger.log("EditScreenFactory: Can't find GuiScreen class!");
                    return;
                }
            }

            AgentLogger.log("EditScreenFactory: GuiScreen = " + guiScreenInternalName);

            // EditScreen must be in the SAME package as GuiScreen
            String pkg = guiScreenInternalName.contains("/")
                    ? guiScreenInternalName.substring(0, guiScreenInternalName.lastIndexOf('/') + 1)
                    : "";
            String editScreenInternalName = pkg + "EditScreen";

            // Generate bytecode
            byte[] classBytes = generateEditScreenClass(guiScreenInternalName, editScreenInternalName);

            // MethodHandles.privateLookupIn + defineClass (Java 9+, called via reflection)
            Class<?> mhClass = MethodHandles.class;
            Object baseLookup = mhClass.getMethod("lookup").invoke(null);
            Class<?> lookupClass = Class.forName("java.lang.invoke.MethodHandles$Lookup");
            Method privateLookupIn = mhClass.getMethod("privateLookupIn", Class.class, lookupClass);
            Object lookup = privateLookupIn.invoke(null, guiScreenClass, baseLookup);
            Method defineClass = lookupClass.getMethod("defineClass", byte[].class);
            editScreenClass = (Class<?>) defineClass.invoke(lookup, classBytes);

            AgentLogger.log("EditScreenFactory: SUCCESS! Defined " + editScreenClass.getName());

        } catch (Exception e) {
            AgentLogger.log("EditScreenFactory: Failed to inject EditScreen!");
            AgentLogger.log(e);
            attempted = false; // Allow retry on next attempt
        }
    }

    /**
     * Generates bytecode for EditScreen extends GuiScreen with:
     * - doesGuiPauseGame() returns false
     * - drawScreen(int mouseX, int mouseY, float partialTicks) calls
     * super.drawScreen() then TimerRenderer.handleEditInput(mouseX, mouseY)
     * via System CL reflection
     */
    private static byte[] generateEditScreenClass(String guiScreenInternalName, String editScreenInternalName) {
        // COMPUTE_FRAMES generates StackMapTable entries required by Java 17.
        // Override getCommonSuperClass to avoid triggering Lunar's classloader.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };

        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                editScreenInternalName, null, guiScreenInternalName, null);

        // Constructor
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, guiScreenInternalName, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // doesGuiPauseGame() -> false
        MethodVisitor mv2 = cw.visitMethod(Opcodes.ACC_PUBLIC, "doesGuiPauseGame", "()Z", null, null);
        mv2.visitCode();
        mv2.visitInsn(Opcodes.ICONST_0);
        mv2.visitInsn(Opcodes.IRETURN);
        mv2.visitMaxs(1, 1);
        mv2.visitEnd();

        // drawScreen(int mouseX, int mouseY, float partialTicks)
        // Calls super.drawScreen, then calls TimerRenderer.handleEditInput(mouseX,
        // mouseY) via reflection
        MethodVisitor mv3 = cw.visitMethod(Opcodes.ACC_PUBLIC, "drawScreen", "(IIF)V", null, null);
        mv3.visitCode();

        // super.drawScreen(mouseX, mouseY, partialTicks)
        mv3.visitVarInsn(Opcodes.ALOAD, 0);
        mv3.visitVarInsn(Opcodes.ILOAD, 1); // mouseX
        mv3.visitVarInsn(Opcodes.ILOAD, 2); // mouseY
        mv3.visitVarInsn(Opcodes.FLOAD, 3); // partialTicks
        mv3.visitMethodInsn(Opcodes.INVOKESPECIAL, guiScreenInternalName, "drawScreen", "(IIF)V", false);

        // try { reflection call to TimerRenderer.handleEditInput(mouseX, mouseY) }
        // catch (Throwable) {}
        Label startTry = new Label();
        Label endTry = new Label();
        Label catchHandler = new Label();
        Label afterCatch = new Label();

        mv3.visitTryCatchBlock(startTry, endTry, catchHandler, "java/lang/Throwable");

        mv3.visitLabel(startTry);

        // Class.forName("com.mcctimer.agent.TimerRenderer", true,
        // ClassLoader.getSystemClassLoader())
        mv3.visitLdcInsn("com.mcctimer.agent.TimerRenderer");
        mv3.visitInsn(Opcodes.ICONST_1);
        mv3.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader",
                "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
        mv3.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class",
                "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);

        // getDeclaredMethod("handleEditInput", int.class, int.class)
        mv3.visitLdcInsn("handleEditInput");
        mv3.visitInsn(Opcodes.ICONST_2);
        mv3.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv3.visitInsn(Opcodes.DUP);
        mv3.visitInsn(Opcodes.ICONST_0);
        mv3.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
        mv3.visitInsn(Opcodes.AASTORE);
        mv3.visitInsn(Opcodes.DUP);
        mv3.visitInsn(Opcodes.ICONST_1);
        mv3.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
        mv3.visitInsn(Opcodes.AASTORE);
        mv3.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);

        // invoke(null, new Object[]{ Integer.valueOf(mouseX), Integer.valueOf(mouseY)
        // })
        mv3.visitInsn(Opcodes.ACONST_NULL);
        mv3.visitInsn(Opcodes.ICONST_2);
        mv3.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv3.visitInsn(Opcodes.DUP);
        mv3.visitInsn(Opcodes.ICONST_0);
        mv3.visitVarInsn(Opcodes.ILOAD, 1); // mouseX
        mv3.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        mv3.visitInsn(Opcodes.AASTORE);
        mv3.visitInsn(Opcodes.DUP);
        mv3.visitInsn(Opcodes.ICONST_1);
        mv3.visitVarInsn(Opcodes.ILOAD, 2); // mouseY
        mv3.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        mv3.visitInsn(Opcodes.AASTORE);
        mv3.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
                "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv3.visitInsn(Opcodes.POP);

        mv3.visitLabel(endTry);
        mv3.visitJumpInsn(Opcodes.GOTO, afterCatch);
        mv3.visitLabel(catchHandler);
        mv3.visitInsn(Opcodes.POP);
        mv3.visitLabel(afterCatch);

        mv3.visitInsn(Opcodes.RETURN);
        mv3.visitMaxs(8, 4);
        mv3.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
