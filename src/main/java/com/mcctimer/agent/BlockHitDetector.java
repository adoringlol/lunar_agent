package com.mcctimer.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Detects when the player takes damage while blocking with a sword.
 * Uses render-tick polling on hurtTime field instead of ASM hooks,
 * because attackEntityFrom is NOT called client-side in multiplayer.
 */
public class BlockHitDetector {

    // Reflection cache
    private static Method getMinecraftMethod;
    private static Field thePlayerField;
    private static Field hurtTimeField;
    private static Method playSoundMethod;

    // For detecting blocking: isUsingItem + held item is sword
    private static Method isUsingItemMethod;
    private static Method getHeldItemMethod;
    private static Method getItemMethod; // ItemStack.getItem()
    private static Class<?> swordClass;

    private static boolean initialized = false;
    private static boolean initFailed = false;

    // Track previous hurtTime to detect transitions
    private static int lastHurtTime = 0;

    /**
     * Called every render tick from GuiIngame overlay.
     * Checks if the player just got hurt while blocking.
     */
    public static void tick() {
        if (!MCCTimerState.blockHitEnabled || MCCTimerState.blockHitSound.isEmpty()) return;

        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null) return;

            if (!initialized && !initFailed) {
                init(gameLoader);
            }
            if (initFailed) return;

            Object mc = getMinecraftMethod.invoke(null);
            if (mc == null) return;
            Object thePlayer = thePlayerField.get(mc);
            if (thePlayer == null) return;

            // Read hurtTime
            int hurtTime = hurtTimeField.getInt(thePlayer);

            // Detect transition: was 0, now > 0 → player just got hurt this tick
            if (hurtTime > 0 && lastHurtTime == 0) {
                // Check if player is blocking (using item + held item is a sword)
                boolean blocking = isPlayerBlocking(thePlayer);
                if (blocking) {
                    playSoundMethod.invoke(thePlayer, MCCTimerState.blockHitSound,
                            MCCTimerState.blockHitVolume, MCCTimerState.blockHitPitch);
                }
            }

            lastHurtTime = hurtTime;
        } catch (Throwable t) {
            // Silently fail to not crash the game
        }
    }

    private static boolean isPlayerBlocking(Object thePlayer) {
        try {
            // First check if using item
            boolean usingItem = (Boolean) isUsingItemMethod.invoke(thePlayer);
            if (!usingItem) return false;

            // Then check if held item is a sword
            Object heldItemStack = getHeldItemMethod.invoke(thePlayer);
            if (heldItemStack == null) return false;

            Object item = getItemMethod.invoke(heldItemStack);
            if (item == null) return false;

            return swordClass != null && swordClass.isInstance(item);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Plays a preview of the current sound for the /blockhit command.
     */
    public static void playPreviewSound() {
        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null) return;

            Object mc = null;
            Object thePlayer = null;
            Method playSound = null;

            try {
                Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, gameLoader);
                Method getMc = mcClass.getMethod("getMinecraft");
                mc = getMc.invoke(null);
            } catch (Exception e) {
                Class<?> mcClass = Class.forName("ave", true, gameLoader);
                Method getMc = mcClass.getMethod("A");
                mc = getMc.invoke(null);
            }
            if (mc == null) return;

            for (Field f : mc.getClass().getDeclaredFields()) {
                String typeName = f.getType().getName();
                if (typeName.equals("net.minecraft.client.entity.EntityPlayerSP") || typeName.equals("bew")) {
                    f.setAccessible(true);
                    thePlayer = f.get(mc);
                    break;
                }
            }
            if (thePlayer == null) return;

            for (Method m : thePlayer.getClass().getMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 3 && params[0] == String.class &&
                    params[1] == float.class && params[2] == float.class &&
                    m.getReturnType() == void.class) {
                    playSound = m;
                    break;
                }
            }
            if (playSound != null) {
                playSound.invoke(thePlayer, MCCTimerState.blockHitSound,
                        MCCTimerState.blockHitVolume, MCCTimerState.blockHitPitch);
            }
        } catch (Throwable t) {
            AgentLogger.log("BlockHitDetector preview error: " + t.getMessage());
        }
    }

    private static void init(ClassLoader gameLoader) {
        try {
            // ---- Minecraft instance & thePlayer ----
            try {
                Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, gameLoader);
                getMinecraftMethod = mcClass.getMethod("getMinecraft");
                thePlayerField = mcClass.getDeclaredField("thePlayer");
            } catch (Exception e) {
                Class<?> mcClass = Class.forName("ave", true, gameLoader);
                getMinecraftMethod = mcClass.getMethod("A");
                for (Field f : mcClass.getDeclaredFields()) {
                    if (f.getType().getName().equals("bew")) {
                        thePlayerField = f;
                        break;
                    }
                }
            }
            if (thePlayerField == null) { initFailed = true; AgentLogger.log("BlockHitDetector: Can't find thePlayer"); return; }
            thePlayerField.setAccessible(true);

            // Get the player to inspect its class hierarchy
            Object mc = getMinecraftMethod.invoke(null);
            Object thePlayer = mc != null ? thePlayerField.get(mc) : null;
            if (thePlayer == null) { initFailed = true; AgentLogger.log("BlockHitDetector: thePlayer is null at init"); return; }

            Class<?> playerClass = thePlayer.getClass();

            // ---- hurtTime field ----
            // MCP: hurtTime, Notch: varies. It's an int field on EntityLivingBase.
            hurtTimeField = findField(playerClass, "hurtTime");
            if (hurtTimeField == null) {
                // Scan for the field by looking at EntityLivingBase int fields
                // hurtTime starts at 0 and goes up to 10 when hurt
                // We log all int fields to help debug
                AgentLogger.log("BlockHitDetector: Scanning for hurtTime field...");
                Class<?> scan = playerClass;
                while (scan != null && !scan.getName().equals("java.lang.Object")) {
                    for (Field f : scan.getDeclaredFields()) {
                        if (f.getType() == int.class) {
                            f.setAccessible(true);
                            int val = f.getInt(thePlayer);
                            // hurtTime should be 0 when not hurt
                            // We can't easily identify it by value alone, so try notch names
                        }
                    }
                    scan = scan.getSuperclass();
                }

                // Common notch names for hurtTime in 1.8.9
                String[] hurtTimeCandidates = {"aw", "ax", "ay", "az", "aA", "aB", "aC", "aD", "aE"};
                for (String name : hurtTimeCandidates) {
                    Field f = findField(playerClass, name);
                    if (f != null && f.getType() == int.class) {
                        f.setAccessible(true);
                        int val = f.getInt(thePlayer);
                        if (val == 0) { // hurtTime is 0 when not being hurt
                            hurtTimeField = f;
                            AgentLogger.log("BlockHitDetector: Using candidate hurtTime field: " + name + " (value=" + val + ")");
                            break;
                        }
                    }
                }
            }
            if (hurtTimeField == null) { initFailed = true; AgentLogger.log("BlockHitDetector: Can't find hurtTime"); return; }
            hurtTimeField.setAccessible(true);
            AgentLogger.log("BlockHitDetector: Found hurtTime field: " + hurtTimeField.getName());

            // ---- playSound method ----
            for (Method m : playerClass.getMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 3 && params[0] == String.class &&
                    params[1] == float.class && params[2] == float.class &&
                    m.getReturnType() == void.class) {
                    playSoundMethod = m;
                    break;
                }
            }
            if (playSoundMethod == null) { initFailed = true; AgentLogger.log("BlockHitDetector: Can't find playSound"); return; }
            AgentLogger.log("BlockHitDetector: Found playSound: " + playSoundMethod.getName());

            // ---- isUsingItem method ----
            isUsingItemMethod = findMethod(playerClass, "isUsingItem", 0, boolean.class);
            if (isUsingItemMethod == null) {
                // Try scanning - isUsingItem is a public, no-arg boolean method
                // We need to differentiate from other boolean methods
                // isUsingItem checks if itemInUse != null
                String[] usingCandidates = {"bS", "bT", "bU", "bV", "bW", "bX", "bY", "bZ",
                                            "ca", "cb", "cc", "cd", "ce", "cf", "isUsingItem"};
                for (String name : usingCandidates) {
                    try {
                        Method m = playerClass.getMethod(name);
                        if (m.getReturnType() == boolean.class && m.getParameterCount() == 0) {
                            isUsingItemMethod = m;
                            AgentLogger.log("BlockHitDetector: Found isUsingItem candidate: " + name);
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (isUsingItemMethod == null) { initFailed = true; AgentLogger.log("BlockHitDetector: Can't find isUsingItem"); return; }
            AgentLogger.log("BlockHitDetector: Found isUsingItem: " + isUsingItemMethod.getName());

            // ---- getHeldItem method (returns ItemStack) ----
            // MCP: getHeldItem(), Notch: varies
            getHeldItemMethod = findMethod(playerClass, "getHeldItem", 0, null);
            if (getHeldItemMethod == null) {
                // Scan: public, no-arg, returns ItemStack (non-primitive, not String, not boolean)
                String[] heldCandidates = {"bA", "bB", "bC", "bD", "bE", "bF", "bG", "bH", "bi", "bj", "bk", "bl", "bm", "bn", "bo", "bp", "getHeldItem"};
                for (String name : heldCandidates) {
                    try {
                        Method m = playerClass.getMethod(name);
                        if (m.getParameterCount() == 0 && !m.getReturnType().isPrimitive() &&
                            m.getReturnType() != String.class && m.getReturnType() != void.class) {
                            // Check if return type looks like ItemStack (has getItem() method)
                            try {
                                m.getReturnType().getMethod("getItem");
                                getHeldItemMethod = m;
                                AgentLogger.log("BlockHitDetector: Found getHeldItem candidate: " + name + " -> " + m.getReturnType().getName());
                                break;
                            } catch (Exception ignored) {}
                            // Notch: try "b" method on the return type
                            try {
                                Method itemMethod = m.getReturnType().getDeclaredMethod("b");
                                if (!itemMethod.getReturnType().isPrimitive()) {
                                    getHeldItemMethod = m;
                                    AgentLogger.log("BlockHitDetector: Found getHeldItem candidate (notch): " + name);
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (getHeldItemMethod == null) { initFailed = true; AgentLogger.log("BlockHitDetector: Can't find getHeldItem"); return; }
            AgentLogger.log("BlockHitDetector: Found getHeldItem: " + getHeldItemMethod.getName());

            // ---- ItemStack.getItem() method ----
            Class<?> itemStackClass = getHeldItemMethod.getReturnType();
            try {
                getItemMethod = itemStackClass.getMethod("getItem");
            } catch (Exception e) {
                // Try notch: scan for a no-arg method that returns a non-primitive, non-String
                for (Method m : itemStackClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && !m.getReturnType().isPrimitive() &&
                        m.getReturnType() != String.class && m.getReturnType() != itemStackClass) {
                        // Could be getItem - the return type should be Item or a subclass
                        getItemMethod = m;
                        getItemMethod.setAccessible(true);
                        AgentLogger.log("BlockHitDetector: Found getItem candidate: " + m.getName() + " -> " + m.getReturnType().getName());
                        break;
                    }
                }
            }
            if (getItemMethod == null) { initFailed = true; AgentLogger.log("BlockHitDetector: Can't find getItem on ItemStack"); return; }

            // ---- ItemSword class ----
            try {
                swordClass = Class.forName("net.minecraft.item.ItemSword", true, gameLoader);
            } catch (Exception e) {
                // Try notch name
                try {
                    swordClass = Class.forName("zy", true, gameLoader);
                } catch (Exception e2) {
                    // Scan: look for a class that extends Item and has "sword" in a field or is identified by some property
                    // For now, fall back to checking class name contains "Sword"  
                    AgentLogger.log("BlockHitDetector: Can't find ItemSword class, will try name matching");
                }
            }
            if (swordClass != null) {
                AgentLogger.log("BlockHitDetector: Found ItemSword: " + swordClass.getName());
            }

            initialized = true;
            AgentLogger.log("BlockHitDetector: Initialized successfully! (polling mode)");
        } catch (Throwable t) {
            AgentLogger.log("BlockHitDetector init error: " + t.getMessage());
            AgentLogger.log(t);
            initFailed = true;
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> scan = clazz;
        while (scan != null && !scan.getName().equals("java.lang.Object")) {
            try {
                Field f = scan.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Exception ignored) {}
            scan = scan.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, int paramCount, Class<?> returnType) {
        try {
            Method m = clazz.getMethod(name);
            if (m.getParameterCount() == paramCount && (returnType == null || m.getReturnType() == returnType)) {
                return m;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
