package com.mcctimer.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class MCCTimerAgent {

    private static final String GUIINGAME_NOTCH = "avo";
    private static final String GUINEWCHAT_NOTCH = "bxh";
    private static final String ENTITYPLAYERSP_NOTCH = "bew";
    private static final String MINECRAFT_NOTCH = "ave";
    private static final String GUIINGAME_MCP = "net/minecraft/client/gui/GuiIngame";
    private static final String GUINEWCHAT_MCP = "net/minecraft/client/gui/GuiNewChat";
    private static final String ENTITYPLAYERSP_MCP = "net/minecraft/client/entity/EntityPlayerSP";
    private static final String MINECRAFT_MCP = "net/minecraft/client/Minecraft";
    private static final String NETHANDLERPLAYCLIENT_NOTCH = "bcy";
    private static final String NETHANDLERPLAYCLIENT_MCP = "net/minecraft/client/network/NetHandlerPlayClient";
    private static final String RENDERGLOBAL_NOTCH = "bfr";
    private static final String RENDERGLOBAL_MCP = "net/minecraft/client/renderer/RenderGlobal";
    private static final String ENTITYLIVINGBASE_NOTCH = "pk";
    private static final String ENTITYLIVINGBASE_MCP = "net/minecraft/entity/EntityLivingBase";

    public static Instrumentation instrumentation;

    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        AgentLogger.log("[MCCTimerAgent] Agent premain called. Using pure inline reflection.");

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (className == null)
                    return null;
                try {
                    if (className.equals(GUIINGAME_NOTCH) || className.equals(GUIINGAME_MCP)) {
                        AgentLogger.log("Transforming GuiIngame class: " + className);
                        return transformGuiIngame(classfileBuffer, className);
                    }
                    if (className.equals(GUINEWCHAT_NOTCH) || className.equals(GUINEWCHAT_MCP)) {
                        AgentLogger.log("Transforming GuiNewChat class: " + className);
                        return transformGuiNewChat(classfileBuffer, className);
                    }
                    if (className.equals(ENTITYPLAYERSP_NOTCH) || className.equals(ENTITYPLAYERSP_MCP)) {
                        AgentLogger.log("Transforming EntityPlayerSP class: " + className);
                        return transformEntityPlayerSP(classfileBuffer, className);
                    }
                    if (className.equals(MINECRAFT_NOTCH) || className.equals(MINECRAFT_MCP)) {
                        AgentLogger.log("Transforming Minecraft class: " + className);
                        return transformMinecraft(classfileBuffer, className);
                    }
                    if (className.equals(NETHANDLERPLAYCLIENT_NOTCH) || className.equals(NETHANDLERPLAYCLIENT_MCP)) {
                        AgentLogger.log("Transforming NetHandlerPlayClient class: " + className);
                        return transformNetHandlerPlayClient(classfileBuffer, className);
                    }
                    if (className.equals(RENDERGLOBAL_NOTCH) || className.equals(RENDERGLOBAL_MCP)) {
                        AgentLogger.log("Transforming RenderGlobal class: " + className);
                        return transformRenderGlobal(classfileBuffer, className);
                    }
                    if (className.equals("aqo") || className.equals("net/minecraft/world/Explosion")) {
                        AgentLogger.log("Transforming Explosion class: " + className);
                        return transformExplosion(classfileBuffer, className);
                    }
                    
                    // Dynamic Chat Copy Hook
                    byte[] transformedChatCopy = com.mcctimer.agent.ChatCopyTransformer.transformChatCopyLocally(classfileBuffer, className);
                    if (transformedChatCopy != null) {
                        return transformedChatCopy;
                    }
                } catch (Exception e) {
                    AgentLogger.log("Error transforming " + className);
                    AgentLogger.log(e);
                }
                return null;
            }
        }, true);

        AgentLogger.log("[MCCTimerAgent] ClassFileTransformer registered.");
        
        // Retransform already loaded classes (essential when injecting mid-game)
        try {
            java.util.List<Class<?>> classesToRetransform = new java.util.ArrayList<>();
            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                if (!inst.isModifiableClass(clazz)) {
                    continue; // Skip unmodifiable classes like primitives or arrays
                }
                String name = clazz.getName().replace('.', '/');
                if (name.equals(GUIINGAME_NOTCH) || name.equals(GUIINGAME_MCP) ||
                    name.equals(GUINEWCHAT_NOTCH) || name.equals(GUINEWCHAT_MCP) ||
                    name.equals(ENTITYPLAYERSP_NOTCH) || name.equals(ENTITYPLAYERSP_MCP) ||
                    name.equals(MINECRAFT_NOTCH) || name.equals(MINECRAFT_MCP) ||
                    name.equals(NETHANDLERPLAYCLIENT_NOTCH) || name.equals(NETHANDLERPLAYCLIENT_MCP) ||
                    name.equals(RENDERGLOBAL_NOTCH) || name.equals(RENDERGLOBAL_MCP) ||
                    name.equals("aqo") || name.equals("net/minecraft/world/Explosion")) {
                    
                    classesToRetransform.add(clazz);
                } else if (!name.startsWith("java/") && !name.startsWith("sun/") && !name.startsWith("jdk/") && !name.startsWith("com/mcctimer/")) {
                    // We must retransform Lunar Client classes to find the chat copy class
                    // We'll queue everything else that's not a core java class or our own agent
                    classesToRetransform.add(clazz);
                }
            }
            if (!classesToRetransform.isEmpty()) {
                AgentLogger.log("[MCCTimerAgent] Retransforming " + classesToRetransform.size() + " already loaded classes.");
                inst.retransformClasses(classesToRetransform.toArray(new Class[0]));
            }
        } catch (Exception e) {
            AgentLogger.log("Failed to retransform already loaded classes.");
            AgentLogger.log(e);
        }
    }

    /**
     * A ClassWriter that avoids loading classes when computing frames.
     * This prevents crashes from Lunar Client's custom classloader rejecting
     * unknown types.
     */
    private static ClassWriter createSafeClassWriter(ClassReader cr) {
        return new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // Always return Object to avoid triggering class resolution
                return "java/lang/Object";
            }
        };
    }

    private static byte[] transformGuiIngame(byte[] classfileBuffer, String className) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = createSafeClassWriter(cr);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                boolean isTarget = false;
                if (className.equals(GUIINGAME_NOTCH)) {
                    isTarget = name.equals("a") && descriptor.equals("(F)V");
                } else {
                    isTarget = name.equals("renderGameOverlay") && descriptor.equals("(F)V");
                }

                if (isTarget) {
                    AgentLogger.log("Found render method: " + name + descriptor + " in " + className);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    injectReflectiveCall(mv,
                                            "com.mcctimer.agent.TimerRenderer",
                                            "renderTimerOverlay",
                                            true, // hasFloatArg
                                            1); // float var index
                                    injectReflectiveCall(mv,
                                            "com.mcctimer.agent.NotificationRenderer",
                                            "renderNotificationOverlay",
                                            true, // hasFloatArg
                                            1); // float var index
                                    injectReflectiveCall(mv,
                                            "com.mcctimer.agent.TntHUD",
                                            "renderTntOverlay",
                                            true, // hasFloatArg
                                            1); // float var index
                                    injectReflectiveCall(mv,
                                            "com.mcctimer.agent.FireballHUD",
                                            "renderFireballOverlay",
                                            true, // hasFloatArg
                                            1); // float var index
                                    injectNoArgCall(mv,
                                            "com.mcctimer.agent.BlockHitDetector",
                                            "tick");
                                }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);

        return cw.toByteArray();
    }

    private static byte[] transformGuiNewChat(byte[] classfileBuffer, String className) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = createSafeClassWriter(cr);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                boolean isTarget = false;
                if (className.equals(GUINEWCHAT_NOTCH)) {
                    isTarget = name.equals("a") && descriptor.startsWith("(L") && descriptor.endsWith(";)V");
                } else {
                    isTarget = name.equals("printChatMessage");
                }

                if (isTarget) {
                    AgentLogger.log("Found chat method: " + name + descriptor + " in " + className);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            injectReflectiveCall(mv,
                                    "com.mcctimer.agent.ChatInterceptor",
                                    "onChatMessage",
                                    false, // no float, has Object arg
                                    1); // Object var index
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);

        return cw.toByteArray();
    }

    private static byte[] transformEntityPlayerSP(byte[] classfileBuffer, String className) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = createSafeClassWriter(cr);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                boolean isTarget = false;
                if (className.equals(ENTITYPLAYERSP_NOTCH)) {
                    // Notch: sendChatMessage is 'e' with (Ljava/lang/String;)V
                    isTarget = name.equals("e") && descriptor.equals("(Ljava/lang/String;)V");
                } else {
                    isTarget = name.equals("sendChatMessage") && descriptor.equals("(Ljava/lang/String;)V");
                }

                if (isTarget) {
                    AgentLogger.log("Found sendChatMessage: " + name + descriptor + " in " + className);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            injectCommandInterceptor(mv);
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);

        return cw.toByteArray();
    }

    /**
     * Hooks Minecraft.loadWorld to detect world changes/disconnect.
     * When loadWorld is called, we reset the timer if it's running.
     */
    private static byte[] transformMinecraft(byte[] classfileBuffer, String className) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = createSafeClassWriter(cr);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                // loadWorld has descriptor (L<WorldClient>;)V or (L<WorldClient>;Ljava/lang/String;)V
                // Match by name: MCP = "loadWorld", Notch = "a" with appropriate desc
                boolean isTarget = false;
                if (className.equals(MINECRAFT_MCP)) {
                    isTarget = name.equals("loadWorld");
                } else {
                    // Notch: match "a" with a descriptor containing a single object arg + void return
                    // loadWorld(WorldClient) in Notch is a(bjb)V
                    isTarget = name.equals("a") && descriptor.startsWith("(L") && descriptor.endsWith(")V")
                            && !descriptor.contains(";L"); // only one argument
                }

                if (isTarget) {
                    AgentLogger.log("Found loadWorld candidate: " + name + descriptor + " in " + className);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // Call MCCTimerState.onWorldChange() via reflection
                            injectWorldChangeCall(mv);
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);

        return cw.toByteArray();
    }

    /**
     * Injects clean inline reflection bytecode that calls a static method
     * via ClassLoader.getSystemClassLoader(), wrapped in a try-catch.
     */

    private static void injectReflectiveCall(MethodVisitor mv, String targetClass, String methodName,
            boolean isFloatArg, int argVarIndex) {
        Label startTry = new Label();
        Label endTry = new Label();
        Label catchHandler = new Label();
        Label afterCatch = new Label();

        mv.visitTryCatchBlock(startTry, endTry, catchHandler, "java/lang/Throwable");

        // === TRY BLOCK ===
        mv.visitLabel(startTry);

        // Class.forName(targetClass, true, ClassLoader.getSystemClassLoader())
        mv.visitLdcInsn(targetClass);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader",
                "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class",
                "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        // Stack: [Class]

        // clazz.getDeclaredMethod(methodName, new Class[]{ argType })
        mv.visitLdcInsn(methodName);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        if (isFloatArg) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Float", "TYPE", "Ljava/lang/Class;");
        } else {
            mv.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
        }
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        // Stack: [Method]

        // method.invoke(null, new Object[]{ arg })
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        if (isFloatArg) {
            mv.visitVarInsn(Opcodes.FLOAD, argVarIndex);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float",
                    "valueOf", "(F)Ljava/lang/Float;", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, argVarIndex);
        }
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
                "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP); // discard return value

        mv.visitLabel(endTry);
        mv.visitJumpInsn(Opcodes.GOTO, afterCatch);

        // === CATCH BLOCK ===
        mv.visitLabel(catchHandler);
        mv.visitInsn(Opcodes.POP); // pop the Throwable

        mv.visitLabel(afterCatch);
    }

    /**
     * Injects a command interceptor at the start of sendChatMessage.
     * If CommandHandler.handleCommand returns true, the method returns early
     * (message is NOT sent to server).
     */

    private static void injectCommandInterceptor(MethodVisitor mv) {
        Label startTry = new Label();
        Label endTry = new Label();
        Label catchHandler = new Label();
        Label continueMethod = new Label();

        mv.visitTryCatchBlock(startTry, endTry, catchHandler, "java/lang/Throwable");

        // === TRY BLOCK ===
        mv.visitLabel(startTry);

        // Class.forName("com.mcctimer.agent.CommandHandler", true,
        // ClassLoader.getSystemClassLoader())
        mv.visitLdcInsn("com.mcctimer.agent.CommandHandler");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader",
                "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class",
                "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);

        // getDeclaredMethod("handleCommand", new Class[]{ Object.class })
        mv.visitLdcInsn("handleCommand");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);

        // invoke(null, new Object[]{ msg })
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 1); // the chat message String
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
                "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);

        // Cast to Boolean and unbox
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean",
                "booleanValue", "()Z", false);

        mv.visitLabel(endTry);
        // If false (not handled), continue to original method
        mv.visitJumpInsn(Opcodes.IFEQ, continueMethod);
        // If true (handled), return early
        mv.visitInsn(Opcodes.RETURN);

        // === CATCH BLOCK ===
        mv.visitLabel(catchHandler);
        mv.visitInsn(Opcodes.POP); // pop the Throwable, continue normally

        mv.visitLabel(continueMethod);
    }

    /**
     * Injects a call to MCCTimerState.onWorldChange() via System CL reflection.
     * Called at the start of Minecraft.loadWorld().
     */
    private static void injectWorldChangeCall(MethodVisitor mv) {
        Label startTry = new Label();
        Label endTry = new Label();
        Label catchHandler = new Label();
        Label afterCatch = new Label();

        mv.visitTryCatchBlock(startTry, endTry, catchHandler, "java/lang/Throwable");

        mv.visitLabel(startTry);

        // Class.forName("com.mcctimer.agent.MCCTimerState", true, ClassLoader.getSystemClassLoader())
        mv.visitLdcInsn("com.mcctimer.agent.MCCTimerState");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader",
                "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class",
                "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);

        // getDeclaredMethod("onWorldChange", new Class[0])
        mv.visitLdcInsn("onWorldChange");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);

        // invoke(null, new Object[0])
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
                "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);

        mv.visitLabel(endTry);
        mv.visitJumpInsn(Opcodes.GOTO, afterCatch);
        mv.visitLabel(catchHandler);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(afterCatch);
    }

    /**
     * Generic helper: injects a no-arg static call to targetClass.methodName() via System CL reflection.
     */
    private static void injectNoArgCall(MethodVisitor mv, String targetClass, String methodName) {
        Label startTry = new Label();
        Label endTry = new Label();
        Label catchHandler = new Label();
        Label afterCatch = new Label();

        mv.visitTryCatchBlock(startTry, endTry, catchHandler, "java/lang/Throwable");

        mv.visitLabel(startTry);

        mv.visitLdcInsn(targetClass);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader",
                "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class",
                "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);

        mv.visitLdcInsn(methodName);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);

        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
                "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);

        mv.visitLabel(endTry);
        mv.visitJumpInsn(Opcodes.GOTO, afterCatch);
        mv.visitLabel(catchHandler);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(afterCatch);
    }

    private static void injectReflectiveBooleanCancel(MethodVisitor mv, String targetClass, String methodName, int argVarIndex) {
        Label startTry = new Label();
        Label endTry = new Label();
        Label catchHandler = new Label();
        Label afterCatch = new Label();

        mv.visitTryCatchBlock(startTry, endTry, catchHandler, "java/lang/Throwable");

        mv.visitLabel(startTry);

        // Class.forName(targetClass, true, ClassLoader.getSystemClassLoader())
        mv.visitLdcInsn(targetClass);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader",
                "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class",
                "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);

        // clazz.getDeclaredMethod(methodName, new Class[]{ Object.class })
        mv.visitLdcInsn(methodName);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLdcInsn(Type.getType("Ljava/lang/Object;"));
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);

        // method.invoke(null, new Object[]{ arg })
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, argVarIndex);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
                "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        
        // Cast to java/lang/Boolean
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
        // unbox
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean",
                "booleanValue", "()Z", false);
        
        // If true, return from method
        Label continueLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, continueLabel); // If false, jump to continueLabel
        mv.visitInsn(Opcodes.RETURN); // Return early
        mv.visitLabel(continueLabel);
        
        mv.visitLabel(endTry);
        mv.visitJumpInsn(Opcodes.GOTO, afterCatch);

        mv.visitLabel(catchHandler);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(afterCatch);
    }

    private static byte[] transformNetHandlerPlayClient(byte[] classfileBuffer, String className) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = createSafeClassWriter(cr);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                boolean isTarget = false;
                if (className.equals(NETHANDLERPLAYCLIENT_NOTCH)) {
                    isTarget = name.equals("a") && descriptor.equals("(Lff;)V");
                } else {
                    isTarget = name.equals("addToSendQueue") && descriptor.equals("(Lnet/minecraft/network/Packet;)V");
                }

                if (isTarget) {
                    AgentLogger.log("Found addToSendQueue: " + name + descriptor + " in " + className);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            injectReflectiveBooleanCancel(mv, "com.mcctimer.agent.ChatInterceptor", "onPacketSend", 1);
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);

        return cw.toByteArray();
    }

    private static byte[] transformRenderGlobal(byte[] classfileBuffer, String className) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = createSafeClassWriter(cr);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                boolean isTarget = false;
                if (className.equals(RENDERGLOBAL_NOTCH)) {
                    isTarget = name.equals("a") && descriptor.equals("(Lpk;Lbia;F)V");
                } else {
                    isTarget = name.equals("renderEntities") && descriptor.equals("(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V");
                }

                if (isTarget) {
                    AgentLogger.log("Found renderEntities: " + name + descriptor + " in " + className);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                // Called at the very end of renderEntities
                                injectReflectiveCall(mv,
                                        "com.mcctimer.agent.TrajectoryRenderer",
                                        "renderTrajectories",
                                        true, // hasFloatArg
                                        3); // partialTicks is at index 3
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);

        return cw.toByteArray();
    }

    private static byte[] transformExplosion(byte[] classfileBuffer, String className) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = createSafeClassWriter(cr);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                boolean isTarget = (name.equals("doExplosionB") || name.equals("a")) && descriptor.equals("(Z)V");
                if (isTarget) {
                    AgentLogger.log("Found doExplosionB: " + name + descriptor + " in " + className);
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // Reflective call avoids classloader mismatch when the game can't see the agent jar
                            injectReflectiveCall(mv,
                                    "com.mcctimer.agent.TrajectoryRenderer",
                                    "recordExplosion",
                                    false, // object arg
                                    0);    // this
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);

        return cw.toByteArray();
    }
}
