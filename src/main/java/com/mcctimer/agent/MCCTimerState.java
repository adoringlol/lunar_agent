package com.mcctimer.agent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class MCCTimerState {
    // Timer state
    public static boolean running = false;
    public static boolean visible = false;
    public static long startTime = 0;

    // Timer position/scale
    public static float timerX = 10;
    public static float timerY = 10;
    public static float timerScale = 2.0f;

    // TNT HUD position/scale/settings
    public static float tntHudX = Float.MAX_VALUE; // auto-center if unset
    public static float tntHudY = Float.MAX_VALUE; // auto 35% from top if unset
    public static float tntHudScale = 1.0f;
    public static float tntDetectDistance = 40.0f;
    public static boolean tntHudEnabled = true;

    // Fireball HUD position/scale
    public static float fireballHudX = Float.MAX_VALUE; // MAX_VALUE = auto-center
    public static float fireballHudY = Float.MAX_VALUE; // MAX_VALUE = auto 25% from top
    public static float fireballHudScale = 1.0f;
    public static float fireballDetectDistance = 7.0f;
    public static float fireballBlastRadius = -1; // -1 = auto-detect per server
    public static boolean fireballThroughWalls = false; // if false, native occlusion/fov culling applies
    public static boolean fireballRaycastEnabled = true; // disable to hide ray + explosion preview

    // Per-server blast radius memory
    public static Map<String, Float> serverBlastRadii = new HashMap<>();
    public static String currentServerIP = "";

    // Edit mode
    public static boolean editMode = false;
    public static boolean dragging = false;
    public static float dragOffsetX = 0;
    public static float dragOffsetY = 0;
    public static boolean tntDragging = false;
    public static float tntDragOffsetX = 0;
    public static float tntDragOffsetY = 0;
    public static boolean fireballDragging = false;
    public static float fireballDragOffsetX = 0;
    public static float fireballDragOffsetY = 0;

    // Notification state
    public static String notificationTitle = "";
    public static String notificationMessage = "";
    public static String notificationPlayerName = "";
    public static long notificationStartTime = 0;

    // Flag to open the edit screen on the next render tick
    public static boolean pendingEditScreen = false;

    // Block hit sound
    public static String blockHitSound = "";
    public static boolean blockHitEnabled = false;
    public static float blockHitVolume = 1.0f;
    public static float blockHitPitch = 1.0f;

    // Config file
    private static final File CONFIG_FILE = new File(System.getProperty("user.home"), "mcctimer-config.properties");

    // Reflection cache for server IP detection
    private static Method getMinecraftMethod;
    private static Method getCurrentServerDataMethod;
    private static Field serverIPField;
    private static boolean serverIPInitialized = false;

    /**
     * Gets the current server IP via reflection.
     * Returns empty string if in singleplayer or detection fails.
     */
    public static String detectServerIP() {
        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null) return "";

            if (!serverIPInitialized) {
                serverIPInitialized = true;
                try {
                    Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, gameLoader);
                    getMinecraftMethod = mcClass.getMethod("getMinecraft");
                    getCurrentServerDataMethod = mcClass.getMethod("getCurrentServerData");
                    Class<?> serverDataClass = Class.forName("net.minecraft.client.multiplayer.ServerData", true, gameLoader);
                    serverIPField = serverDataClass.getField("serverIP");
                } catch (Exception e) {
                    try {
                        Class<?> mcClass = Class.forName("ave", true, gameLoader);
                        getMinecraftMethod = mcClass.getMethod("A");
                        // getCurrentServerData - Notch: try "E" or "getCurrentServerData"
                        for (Method m : mcClass.getDeclaredMethods()) {
                            if (m.getParameterTypes().length == 0 && m.getReturnType().getSimpleName().length() <= 4
                                && !m.getReturnType().isPrimitive() && !m.getReturnType().equals(String.class)) {
                                // ServerData is a short class name in Notch
                                try {
                                    Field ipField = m.getReturnType().getField("b"); // serverIP in Notch
                                    if (ipField.getType() == String.class) {
                                        getCurrentServerDataMethod = m;
                                        serverIPField = ipField;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception e2) {
                        AgentLogger.log("MCCTimerState: Failed to init server IP detection");
                    }
                }
            }

            if (getMinecraftMethod == null || getCurrentServerDataMethod == null || serverIPField == null) return "";

            Object mc = getMinecraftMethod.invoke(null);
            if (mc == null) return "";
            Object serverData = getCurrentServerDataMethod.invoke(mc);
            if (serverData == null) return "";
            String ip = (String) serverIPField.get(serverData);
            return ip != null ? ip.toLowerCase().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Gets the effective blast radius for the current server.
     * Priority: manual override > per-server memory > default 1.2
     */
    public static float getEffectiveBlastRadius() {
        if (fireballBlastRadius > 0) return fireballBlastRadius; // manual override
        String ip = currentServerIP;
        if (!ip.isEmpty() && serverBlastRadii.containsKey(ip)) {
            return serverBlastRadii.get(ip);
        }
        return 1.2f; // default
    }

    /**
     * Records a blast radius for the current server.
     */
    public static void recordServerBlastRadius(float radius) {
        String ip = currentServerIP;
        if (ip.isEmpty()) {
            ip = detectServerIP();
            currentServerIP = ip;
        }
        if (!ip.isEmpty()) {
            serverBlastRadii.put(ip, radius);
            saveConfig();
            AgentLogger.log("Saved blast radius " + radius + " for server: " + ip);
        }
    }

    public static void saveConfig() {
        try {
            Properties props = new Properties();
            props.setProperty("timerX", String.valueOf(timerX));
            props.setProperty("timerY", String.valueOf(timerY));
            props.setProperty("timerScale", String.valueOf(timerScale));
            props.setProperty("tntHudX", String.valueOf(tntHudX));
            props.setProperty("tntHudY", String.valueOf(tntHudY));
            props.setProperty("tntHudScale", String.valueOf(tntHudScale));
            props.setProperty("tntDetectDistance", String.valueOf(tntDetectDistance));
            props.setProperty("tntHudEnabled", String.valueOf(tntHudEnabled));
            props.setProperty("fireballHudX", String.valueOf(fireballHudX));
            props.setProperty("fireballHudY", String.valueOf(fireballHudY));
            props.setProperty("fireballHudScale", String.valueOf(fireballHudScale));
            props.setProperty("fireballDetectDistance", String.valueOf(fireballDetectDistance));
            props.setProperty("fireballBlastRadius", String.valueOf(fireballBlastRadius));
            props.setProperty("fireballThroughWalls", String.valueOf(fireballThroughWalls));
            props.setProperty("fireballRaycastEnabled", String.valueOf(fireballRaycastEnabled));
            props.setProperty("blockHitSound", blockHitSound);
            props.setProperty("blockHitEnabled", String.valueOf(blockHitEnabled));
            props.setProperty("blockHitVolume", String.valueOf(blockHitVolume));
            props.setProperty("blockHitPitch", String.valueOf(blockHitPitch));

            // Save per-server blast radii
            for (Map.Entry<String, Float> entry : serverBlastRadii.entrySet()) {
                props.setProperty("serverRadius." + entry.getKey(), String.valueOf(entry.getValue()));
            }

            try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
                props.store(fos, "MCCTimer Agent Config");
            }
            AgentLogger.log("Config saved to " + CONFIG_FILE.getAbsolutePath());
        } catch (Exception e) {
            AgentLogger.log("Failed to save config!");
            AgentLogger.log(e);
        }
    }

    public static void loadConfig() {
        if (!CONFIG_FILE.exists())
            return;
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
                props.load(fis);
            }
            timerX = Float.parseFloat(props.getProperty("timerX", "10"));
            timerY = Float.parseFloat(props.getProperty("timerY", "10"));
            timerScale = Float.parseFloat(props.getProperty("timerScale", "2.0"));
            tntHudX = Float.parseFloat(props.getProperty("tntHudX", String.valueOf(Float.MAX_VALUE)));
            tntHudY = Float.parseFloat(props.getProperty("tntHudY", String.valueOf(Float.MAX_VALUE)));
            tntHudScale = Float.parseFloat(props.getProperty("tntHudScale", "1.0"));
            tntDetectDistance = Float.parseFloat(props.getProperty("tntDetectDistance", "40.0"));
            tntHudEnabled = Boolean.parseBoolean(props.getProperty("tntHudEnabled", "true"));
            fireballHudX = Float.parseFloat(props.getProperty("fireballHudX", String.valueOf(Float.MAX_VALUE)));
            fireballHudY = Float.parseFloat(props.getProperty("fireballHudY", String.valueOf(Float.MAX_VALUE)));
            fireballHudScale = Float.parseFloat(props.getProperty("fireballHudScale", "1.0"));
            fireballDetectDistance = Float.parseFloat(props.getProperty("fireballDetectDistance", "7.0"));
            fireballBlastRadius = Float.parseFloat(props.getProperty("fireballBlastRadius", "-1"));
            fireballThroughWalls = Boolean.parseBoolean(props.getProperty("fireballThroughWalls", "false"));
            fireballRaycastEnabled = Boolean.parseBoolean(props.getProperty("fireballRaycastEnabled", "true"));
            blockHitSound = props.getProperty("blockHitSound", "");
            blockHitEnabled = Boolean.parseBoolean(props.getProperty("blockHitEnabled", "false"));
            blockHitVolume = Float.parseFloat(props.getProperty("blockHitVolume", "1.0"));
            blockHitPitch = Float.parseFloat(props.getProperty("blockHitPitch", "1.0"));

            // Load per-server blast radii
            serverBlastRadii.clear();
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("serverRadius.")) {
                    String serverIP = key.substring("serverRadius.".length());
                    float radius = Float.parseFloat(props.getProperty(key));
                    serverBlastRadii.put(serverIP, radius);
                }
            }
            if (!serverBlastRadii.isEmpty()) {
                AgentLogger.log("Loaded blast radii for " + serverBlastRadii.size() + " servers");
            }

            AgentLogger.log("Config loaded from " + CONFIG_FILE.getAbsolutePath());
        } catch (Exception e) {
            AgentLogger.log("Failed to load config!");
            AgentLogger.log(e);
        }
    }

    /**
     * Called from Minecraft.loadWorld() via ASM hook.
     * Resets the timer when the world changes (disconnect, server switch, etc.)
     */
    public static void onWorldChange() {
        if (running) {
            AgentLogger.log("World change detected - resetting timer.");
            running = false;
            visible = false;
            startTime = 0;
        }
        // Detect new server IP
        currentServerIP = "";
    }
}
