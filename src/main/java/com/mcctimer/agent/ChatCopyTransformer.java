package com.mcctimer.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class ChatCopyTransformer {
    
    // Scans classes to find the Chat Copy feature and injects our hook
    public static byte[] transformChatCopyLocally(byte[] classfileBuffer, String className) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);
        
        // Fingerprinting: Look for "copyChat" and "copiedMessage" strings
        boolean isChatCopyClass = false;
        boolean hasCopiedMessage = false;
        
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode) {
                    Object cst = ((LdcInsnNode) insn).cst;
                    if (cst instanceof String) {
                        String str = (String) cst;
                        if ("copyChat".equals(str) || "copyChatBind".equals(str)) {
                            isChatCopyClass = true;
                        }
                        if ("copiedMessage".equals(str)) {
                            hasCopiedMessage = true;
                        }
                    }
                }
            }
        }
        
        if (!isChatCopyClass || !hasCopiedMessage) {
            return null; // Not our target class
        }
        
        AgentLogger.log("[MCCTimerAgent] Found Chat Copy Class dynamically: " + className);
        
        boolean modified = false;
        
        // Find the method that copies the message
        for (MethodNode method : classNode.methods) {
            // It takes 0 arguments and returns boolean ()Z
            if (method.desc.equals("()Z")) {
                boolean isCopyMethod = false;
                
                // Confirm it has the "copiedMessage" string
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LdcInsnNode && "copiedMessage".equals(((LdcInsnNode) insn).cst)) {
                        isCopyMethod = true;
                        break;
                    }
                }
                
                if (isCopyMethod) {
                    AgentLogger.log("[MCCTimerAgent] Found copy method: " + method.name + method.desc);
                    
                    // Look for the INVOKESTATIC to getTextContent
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                            MethodInsnNode min = (MethodInsnNode) insn;
                            // The original method takes Component and returns String
                            if (min.desc.equals("(Lnet/kyori/adventure/text/Component;)Ljava/lang/String;")) {
                                AgentLogger.log("[MCCTimerAgent] Replacing INVOKESTATIC to " + min.owner + "." + min.name);
                                
                                // Store original method info for our hook
                                ChatCopyHooks.originalOwner = min.owner;
                                ChatCopyHooks.originalName = min.name;
                                ChatCopyHooks.originalDesc = min.desc;
                                
                                // Generate reflection bytecode to bypass Lunar's classloader boundary
                                InsnList il = new InsnList();
                                
                                // Stack has: [Component]
                                il.add(new LdcInsnNode("com.mcctimer.agent.ChatCopyHooks"));
                                il.add(new InsnNode(Opcodes.ICONST_1));
                                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false));
                                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
                                // Stack: [Component, Class]
                                
                                il.add(new LdcInsnNode("getTextOrLegacy"));
                                il.add(new InsnNode(Opcodes.ICONST_1));
                                il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
                                il.add(new InsnNode(Opcodes.DUP));
                                il.add(new InsnNode(Opcodes.ICONST_0));
                                il.add(new LdcInsnNode(Type.getType("Ljava/lang/Object;")));
                                il.add(new InsnNode(Opcodes.AASTORE));
                                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false));
                                // Stack: [Component, Method]
                                
                                il.add(new InsnNode(Opcodes.SWAP)); 
                                // Stack: [Method, Component]
                                
                                il.add(new InsnNode(Opcodes.ACONST_NULL)); 
                                // Stack: [Method, Component, null (for invoke target)]
                                il.add(new InsnNode(Opcodes.SWAP)); 
                                // Stack: [Method, null, Component]
                                
                                il.add(new InsnNode(Opcodes.ICONST_1));
                                il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object")); 
                                // Stack: [Method, null, Component, array]
                                il.add(new InsnNode(Opcodes.DUP_X1)); 
                                // Stack: [Method, null, array, Component, array]
                                il.add(new InsnNode(Opcodes.SWAP));   
                                // Stack: [Method, null, array, array, Component]
                                il.add(new InsnNode(Opcodes.ICONST_0)); 
                                // Stack: [Method, null, array, array, Component, 0]
                                il.add(new InsnNode(Opcodes.SWAP)); 
                                // Stack: [Method, null, array, array, 0, Component]
                                il.add(new InsnNode(Opcodes.AASTORE)); 
                                // array[0] = Component. Stack: [Method, null, array]
                                
                                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false));
                                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
                                
                                // Replace the original INVOKESTATIC with our reflection chain
                                method.instructions.insert(min, il);
                                method.instructions.remove(min);
                                
                                modified = true;
                            }
                        }
                    }
                }
            }
        }
        
        if (modified) {
            AgentLogger.log("[MCCTimerAgent] Successfully patched Chat Copy!");
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return "java/lang/Object";
                }
            };
            classNode.accept(cw);
            return cw.toByteArray();
        }
        
        return null; // Return null if we didn't inject anything
    }
}
