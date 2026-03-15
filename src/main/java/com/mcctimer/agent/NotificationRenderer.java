package com.mcctimer.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Obfuscation-proof Lunar Client notification renderer.
 *
 * Discovers at runtime:
 *  1. Notification manager — by ConcurrentLinkedQueue field + (String,String) method
 *  2. Head utility — by HashMap<UUID,?> fields + static UUID→ResourceLocation method
 *  3. Icon notification method — by (ResourceLocation, String) signature on notif manager
 *
 * Falls back to OpenGL rendering if discovery fails.
 */
public class NotificationRenderer {

    // === NATIVE ===
    private static Class<?> notifManagerClass;
    private static Object notifManagerInstance;
    private static Method displayMethod;           // (String, String)
    private static Method getHeadMethod;           // static UUID → ResourceLocation (head utility)

    // Authentic Friend Notification hooks
    private static Class<?> genericNotifClass;
    private static Method addNotifMethod;
    private static Class<?> profileClass;
    private static java.lang.reflect.Constructor<?> profileConstructor; // (UUID, String)
    private static Class<?> friendNotifClass;
    private static java.lang.reflect.Constructor<?> friendNotifConstructor; // (UUID, Profile, String)

    // === FALLBACK ===
    private static Class<?> minecraftClass;
    private static Method getMinecraftMethod;
    private static Field fontRendererField;
    private static Method drawStringWithShadowMethod;
    private static Method getStringWidthMethod;
    private static java.lang.reflect.Constructor<?> scaledResCtor;
    private static Method getScaledWidthMethod;
    private static Method glPushMatrix, glPopMatrix;
    private static Method glEnable, glDisable, glColor4f;
    private static Method glBegin, glEnd, glVertex2f;
    private static Method glBlendFunc, glTexCoord2f;
    private static Field GL_TEXTURE_2D, GL_QUADS, GL_BLEND, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA;
    private static Field GL_TRIANGLE_FAN;

    private static boolean initialized = false;
    private static boolean nativeAvailable = false;

    private static final long SLIDE_MS = 250;
    private static final long DISPLAY_MS = 5000;
    private static final int FONT_HEIGHT = 8;

    private static void init() {
        if (initialized) return;
        initialized = true;

        try {
            if (discoverNativeNotifs()) {
                nativeAvailable = true;
                AgentLogger.log("NotificationRenderer: Native system found!");
            } else {
                AgentLogger.log("NotificationRenderer: Native not available, using fallback.");
            }
        } catch (Exception e) {
            AgentLogger.log("NotificationRenderer: Discovery failed - " + e.getMessage());
        }

        initFallback();
    }

    private static boolean discoverNativeNotifs() {
        Instrumentation inst = MCCTimerAgent.instrumentation;
        if (inst == null) return false;

        notifManagerClass = null;
        Method showMethod = null;

        Class<?>[] allClasses = inst.getAllLoadedClasses();

        // Step 1: Find notification manager class
        for (Class<?> c : allClasses) {
            try {
                if (c.getName() == null || !c.getName().startsWith("com.moonsworth.lunar.client.")) continue;
                boolean hasQueue = false;
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == ConcurrentLinkedQueue.class) { hasQueue = true; break; }
                }
                if (!hasQueue) continue;

                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == String.class && p[1] == String.class
                        && m.getReturnType() != void.class && m.getReturnType() != boolean.class) {
                        notifManagerClass = c;
                        showMethod = m;
                        break;
                    }
                }
                if (notifManagerClass != null) break;
            } catch (Exception | NoClassDefFoundError e) { /* skip */ }
        }

        if (notifManagerClass == null) {
            AgentLogger.log("NotificationRenderer: Notif manager class not found");
            return false;
        }
        AgentLogger.log("NotificationRenderer: Found notif manager: " + notifManagerClass.getName());

        // Step 2: Find head utility class FIRST to get the exact ResourceLocation type
        // Signature: class in com.moonsworth.lunar.client.util with 2+ Map fields
        // and a static method taking UUID, returning some ResourceLocation-like type
        AgentLogger.log("NotificationRenderer: Scanning for head utility in util package...");
        for (Class<?> c : allClasses) {
            try {
                if (c.getName() == null || !c.getName().startsWith("com.moonsworth.lunar.client.util.")) continue;

                int mapCount = 0;
                int staticUuidMethods = 0;
                for (Field f : c.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) mapCount++;
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == UUID.class) {
                        staticUuidMethods++;
                    }
                }

                // Log every class that has either maps or uuid methods
                if (mapCount > 0 || staticUuidMethods > 0) {
                    AgentLogger.log("NotificationRenderer: util class " + c.getSimpleName()
                        + " maps=" + mapCount + " staticUUID=" + staticUuidMethods);
                }

                if (mapCount < 2) continue;

                // Find FIRST static method: UUID → non-primitive (that's the head method)
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == UUID.class
                        && !m.getReturnType().isPrimitive() && m.getReturnType() != void.class
                        && m.getReturnType() != boolean.class) {
                        getHeadMethod = m;
                        getHeadMethod.setAccessible(true);
                        AgentLogger.log("NotificationRenderer: Found head utility: "
                            + c.getName() + "." + m.getName()
                            + " returns " + m.getReturnType().getName());
                        break;
                    }
                }
                if (getHeadMethod != null) break;
            } catch (Exception | NoClassDefFoundError e) {
                AgentLogger.log("NotificationRenderer: Error scanning " + c.getName() + ": " + e.getMessage());
            }
        }
        AgentLogger.log("NotificationRenderer: Head utility search complete. Found=" + (getHeadMethod != null));

        // Step 3: (Removed) Legacy icon method search
        AgentLogger.log("NotificationRenderer: Skipping legacy icon method search.");

        // Step 4: Find the singleton
        Object settingsManager = null;
        Object notifManager = null;

        for (Class<?> c : allClasses) {
            try {
                if (c.getName() == null || !c.getName().startsWith("com.moonsworth.lunar.client.util.")) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != 0) continue;
                    Class<?> ret = m.getReturnType();
                    if (ret == null || ret.isPrimitive()) continue;

                    for (Method subM : ret.getDeclaredMethods()) {
                        if (subM.getParameterCount() != 0) continue;
                        if (subM.getReturnType() == notifManagerClass
                            || notifManagerClass.isAssignableFrom(subM.getReturnType())) {
                            try {
                                m.setAccessible(true);
                                settingsManager = m.invoke(null);
                                if (settingsManager != null) {
                                    subM.setAccessible(true);
                                    notifManager = subM.invoke(settingsManager);
                                }
                            } catch (Exception e) { /* try next */ }
                            if (notifManager != null) break;
                        }
                    }
                    if (notifManager != null) break;
                }
                if (notifManager != null) break;
            } catch (Exception | NoClassDefFoundError e) { /* skip */ }
        }

        if (notifManager == null) {
            AgentLogger.log("NotificationRenderer: Could not find notif manager instance");
            return false;
        }

        notifManagerInstance = notifManager;
        showMethod.setAccessible(true);
        displayMethod = showMethod;
        AgentLogger.log("NotificationRenderer: Ready! headMethod=" + (getHeadMethod != null));
        return true;
    }

    // ============================
    // RENDER
    // ============================

    public static void renderNotificationOverlay(float partialTicks) {
        init();
        if (MCCTimerState.notificationMessage == null || MCCTimerState.notificationMessage.isEmpty()) return;

        if (nativeAvailable) {
            renderNative();
        } else {
            renderFallback(partialTicks);
        }
    }

    /**
     * Pre-fetch player head data on a background thread.
     * Called from CommandHandler before setting notification state.
     * This ensures UUID resolution, discovery, and texture download happen off the render thread.
     */
    public static void prefetchPlayerHead(String playerName) {
        // Trigger lazy discovery if needed
        if (nativeAvailable && notifManagerInstance != null) {
            lazyDiscoverHead();
        }

        // Resolve UUID (may hit Mojang API — that's fine, we're on a background thread)
        UUID uuid = getPlayerUUID(playerName);
        AgentLogger.log("NotificationRenderer: Prefetched UUID for " + playerName + " = " + uuid);

        // DO NOT call getHeadMethod here! Texture creation needs OpenGL context,
        // which only exists on the main render thread!
    }

    private static void renderNative() {
        try {
            String message = MCCTimerState.notificationMessage;
            String playerName = MCCTimerState.notificationPlayerName;

            MCCTimerState.notificationMessage = "";
            MCCTimerState.notificationTitle = "";
            MCCTimerState.notificationPlayerName = "";
            MCCTimerState.notificationStartTime = 0;

            if (notifManagerInstance == null) { nativeAvailable = false; return; }

            if (playerName != null && !playerName.isEmpty()) {
                AgentLogger.log("NotificationRenderer: Authentic Friend notif for '" + playerName
                    + "' profile=" + (profileConstructor != null)
                    + " friendNotif=" + (friendNotifConstructor != null)
                    + " addMethod=" + (addNotifMethod != null));

                if (profileConstructor != null && friendNotifConstructor != null && addNotifMethod != null) {
                    UUID uuid = getPlayerUUID(playerName);
                    AgentLogger.log("NotificationRenderer: UUID for " + playerName + " = " + uuid);

                    if (uuid != null) {
                        try {
                            // 1. Create Profile(UUID, String)
                            Object profile = profileConstructor.newInstance(uuid, playerName);
                            
                            // 2. Create FriendNotification(UUID, Profile, String)
                            Object friendNotif = friendNotifConstructor.newInstance(uuid, profile, message);
                            
                            // 3. Add to NotificationManager
                            Object result = addNotifMethod.invoke(notifManagerInstance, friendNotif);
                            AgentLogger.log("NotificationRenderer: Authentic Friend notif sent! result=" + result);
                            
                            // Trigger head texture cache load so it starts downloading immediately on render thread
                            if (getHeadMethod != null) {
                                getHeadMethod.invoke(null, uuid);
                            }
                            return;
                        } catch (Exception e) {
                            AgentLogger.log("NotificationRenderer: Authentic Friend notif call failed!");
                            AgentLogger.log(e);
                        }
                    }
                }
                AgentLogger.log("NotificationRenderer: Falling back to generic for " + playerName);
            }

            // Generic notification fallback
            String title = MCCTimerState.notificationTitle; // Re-read title for generic fallback
            if (title != null && !title.isEmpty()) {
                displayMethod.invoke(notifManagerInstance, title, message);
            } else {
                displayMethod.invoke(notifManagerInstance, "Notification", message);
            }

        } catch (Exception e) {
            AgentLogger.log("NotificationRenderer: Native call failed");
            AgentLogger.log(e);
            nativeAvailable = false;
            MCCTimerState.notificationMessage = "";
        }
    }

    /**
     * Lazy discovery: find icon method and head utility at notification time.
     * Uses direct Class.forName as the primary strategy.
     */
    private static boolean headDiscoveryAttempted = false;

    private static void lazyDiscoverHead() {
        // If hot-reloaded, fields might be null even if attempted is true
        if (headDiscoveryAttempted && addNotifMethod != null) return;
        headDiscoveryAttempted = true;

        if (notifManagerClass == null) return;
        ClassLoader cl = notifManagerClass.getClassLoader();

        try {
            // STEP 1: Find the addNotification method and generic notification class
            // public RCIRCHRRCIRCIROCRICORRRIHRIRCC addNotification(RCIRCHRRCIRCIROCRICORRRIHRIRCC notif)
            for (Method m : notifManagerClass.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && m.getReturnType() == p[0] && !p[0].isPrimitive() && p[0] != String.class && p[0] != Object.class) {
                    genericNotifClass = p[0];
                    addNotifMethod = m;
                    addNotifMethod.setAccessible(true);
                    AgentLogger.log("NotificationRenderer: Found addNotifMethod: " + m.getName() + " with class " + genericNotifClass.getSimpleName());
                    break;
                }
            }

            if (genericNotifClass == null) {
                AgentLogger.log("NotificationRenderer: genericNotifClass not found, aborting authentic discovery.");
                return;
            }

            // STEP 2: Find Profile and FriendNotif classes via JAR scan
            java.net.URL location = notifManagerClass.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                java.io.File jarFile = null;
                if (location.getProtocol().equals("file")) {
                    jarFile = new java.io.File(location.toURI());
                } else if (location.getProtocol().equals("jar")) {
                    String path = location.getPath();
                    if (path.startsWith("file:")) {
                        int bang = path.indexOf('!');
                        path = path.substring(5, bang > -1 ? bang : path.length());
                        jarFile = new java.io.File(java.net.URLDecoder.decode(path, "UTF-8"));
                    }
                }

                if (jarFile != null && jarFile.exists() && jarFile.isFile()) {
                    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarFile)) {
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            java.util.zip.ZipEntry entry = entries.nextElement();
                            String name = entry.getName();
                            
                            // We are looking for something that extends genericNotifClass (directly or indirectly)
                            // FriendNotif is in com.moonsworth.lunar.client...
                            if (name.startsWith("com/moonsworth/lunar/client/") && name.endsWith(".class")) {
                                if (name.indexOf('$') != -1) continue;
                                String className = name.replace('/', '.').substring(0, name.length() - 6);
                                
                                try {
                                    Class<?> c = Class.forName(className, false, cl);
                                    
                                    // Is it the Profile class?
                                    // The true Profile class has a (UUID) constructor AND a (UUID, String) constructor.
                                    if (profileClass == null) {
                                        boolean hasUuidOnly = false;
                                        boolean hasUuidString = false;
                                        java.lang.reflect.Constructor<?> targetCtor = null;
                                        for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                                            Class<?>[] p = ctor.getParameterTypes();
                                            if (p.length == 1 && p[0] == UUID.class) hasUuidOnly = true;
                                            if (p.length == 2 && p[0] == UUID.class && p[1] == String.class) {
                                                hasUuidString = true;
                                                targetCtor = ctor;
                                            }
                                        }
                                        if (hasUuidOnly && hasUuidString && targetCtor != null) {
                                            profileClass = c;
                                            profileConstructor = targetCtor;
                                            profileConstructor.setAccessible(true);
                                            AgentLogger.log("NotificationRenderer: Found Profile class: " + className);
                                        }
                                    }

                                    // Is it the FriendNotif class? Extends genericNotifClass and has (UUID, X, String) ctor
                                    if (friendNotifClass == null && genericNotifClass.isAssignableFrom(c) && c != genericNotifClass) {
                                        for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                                            Class<?>[] p = ctor.getParameterTypes();
                                            if (p.length == 3 && p[0] == UUID.class && p[2] == String.class && !p[1].isPrimitive() && p[1] != String.class) {
                                                friendNotifClass = c;
                                                friendNotifConstructor = ctor;
                                                friendNotifConstructor.setAccessible(true);
                                                AgentLogger.log("NotificationRenderer: Found FriendNotif class: " + className);
                                                break;
                                            }
                                        }
                                    }

                                    // Also find head utility while we're at it, since it's in util
                                    if (getHeadMethod == null && name.startsWith("com/moonsworth/lunar/client/util/")) {
                                        checkAndSetHeadMethod(c);
                                    }

                                    if (profileConstructor != null && friendNotifConstructor != null && getHeadMethod != null) {
                                        break; // Found everything!
                                    }
                                } catch (Throwable t) { /* skip */ }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            AgentLogger.log("NotificationRenderer: Error during authentic discovery: " + e.getMessage());
        }

        AgentLogger.log("NotificationRenderer: Discovery complete."
            + " profile=" + (profileConstructor != null)
            + " friendNotif=" + (friendNotifConstructor != null)
            + " head=" + (getHeadMethod != null));
    }

    private static void checkAndSetHeadMethod(Class<?> c) {
        try {
            int mapCount = 0;
            for (Field f : c.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) mapCount++;
            }
            if (mapCount < 2) return;

            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == UUID.class
                    && !m.getReturnType().isPrimitive() && m.getReturnType() != void.class
                    && m.getReturnType() != String.class && m.getReturnType() != Object.class) {
                    getHeadMethod = m;
                    getHeadMethod.setAccessible(true);
                    AgentLogger.log("NotificationRenderer: Found head utility: "
                        + c.getName() + "." + m.getName() + " returns " + m.getReturnType().getSimpleName());
                    return;
                }
            }
        } catch (Exception | NoClassDefFoundError e) { /* skip */ }
    }

    // UUID cache: name → UUID (so we don't call Mojang API repeatedly)
    private static final Map<String, UUID> uuidCache = new HashMap<>();

    private static UUID getPlayerUUID(String name) {
        // Check cache first
        String nameLower = name.toLowerCase();
        if (uuidCache.containsKey(nameLower)) {
            return uuidCache.get(nameLower);
        }

        // Try tab list first (works if player is on same server)
        UUID uuid = getPlayerUUIDFromTabList(name);

        // Fallback to Mojang API (works for any player, even offline)
        if (uuid == null) {
            uuid = getPlayerUUIDFromMojang(name);
        }

        // Cache result
        if (uuid != null) {
            uuidCache.put(nameLower, uuid);
        }

        return uuid;
    }

    private static UUID getPlayerUUIDFromTabList(String name) {
        try {
            Object mc = getMinecraftMethod.invoke(null);
            if (mc == null) return null;

            Object player = null;
            try { player = mc.getClass().getField("thePlayer").get(mc); }
            catch (Exception e) { player = mc.getClass().getField("h").get(mc); }
            if (player == null) return null;

            Object netHandler = null;
            try { netHandler = player.getClass().getField("sendQueue").get(player); }
            catch (Exception e) {
                for (Field f : player.getClass().getDeclaredFields()) {
                    if (f.getType().getName().contains("NetHandlerPlayClient")
                        || f.getType().getName().equals("bcy")) {
                        f.setAccessible(true); netHandler = f.get(player); break;
                    }
                }
            }
            if (netHandler == null) return null;

            Object playerInfo = null;
            for (Method m : netHandler.getClass().getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == String.class
                    && m.getReturnType() != null && !m.getReturnType().isPrimitive()
                    && m.getReturnType() != String.class) {
                    try {
                        m.setAccessible(true);
                        playerInfo = m.invoke(netHandler, name);
                        if (playerInfo != null) break;
                    } catch (Exception e) { /* next */ }
                }
            }
            if (playerInfo == null) return null;

            try {
                Method gp = playerInfo.getClass().getMethod("getGameProfile");
                Object profile = gp.invoke(playerInfo);
                if (profile != null) return (UUID) profile.getClass().getMethod("getId").invoke(profile);
            } catch (Exception e) {
                for (Method m : playerInfo.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType().getName().contains("GameProfile")) {
                        m.setAccessible(true);
                        Object profile = m.invoke(playerInfo);
                        if (profile != null) return (UUID) profile.getClass().getMethod("getId").invoke(profile);
                    }
                }
            }
        } catch (Exception e) {
            AgentLogger.log("NotificationRenderer: Tab list UUID lookup failed for " + name);
        }
        return null;
    }

    /**
     * Resolves a player's UUID from the Mojang API.
     * GET https://api.mojang.com/users/profiles/minecraft/{name}
     * Returns JSON: {"id":"hex-no-dashes","name":"..."}
     */
    private static UUID getPlayerUUIDFromMojang(String name) {
        try {
            URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + name);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() != 200) {
                AgentLogger.log("NotificationRenderer: Mojang API returned " + conn.getResponseCode() + " for " + name);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            // Parse UUID from JSON response: {"id":"<hex>","name":"..."}
            String json = sb.toString();
            int idIdx = json.indexOf("\"id\"");
            if (idIdx < 0) return null;
            int colonIdx = json.indexOf(':', idIdx);
            int startQuote = json.indexOf('"', colonIdx + 1);
            int endQuote = json.indexOf('"', startQuote + 1);
            String hex = json.substring(startQuote + 1, endQuote);

            // Convert "abcdef1234..." to "abcdef12-3456-7890-abcd-ef1234567890"
            if (hex.length() == 32) {
                String formatted = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                    + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
                UUID uuid = UUID.fromString(formatted);
                AgentLogger.log("NotificationRenderer: Mojang API resolved " + name + " → " + uuid);
                return uuid;
            }
        } catch (Exception e) {
            AgentLogger.log("NotificationRenderer: Mojang API failed for " + name + ": " + e.getMessage());
        }
        return null;
    }

    // ============================
    // FALLBACK (OpenGL)
    // ============================

    private static void initFallback() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) return;

            Class<?> gl = Class.forName("org.lwjgl.opengl.GL11", true, cl);
            glPushMatrix = gl.getMethod("glPushMatrix");
            glPopMatrix = gl.getMethod("glPopMatrix");
            glEnable = gl.getMethod("glEnable", int.class);
            glDisable = gl.getMethod("glDisable", int.class);
            glColor4f = gl.getMethod("glColor4f", float.class, float.class, float.class, float.class);
            glBegin = gl.getMethod("glBegin", int.class);
            glEnd = gl.getMethod("glEnd");
            glVertex2f = gl.getMethod("glVertex2f", float.class, float.class);
            glBlendFunc = gl.getMethod("glBlendFunc", int.class, int.class);
            glTexCoord2f = gl.getMethod("glTexCoord2f", float.class, float.class);
            GL_TEXTURE_2D = gl.getField("GL_TEXTURE_2D");
            GL_QUADS = gl.getField("GL_QUADS");
            GL_BLEND = gl.getField("GL_BLEND");
            GL_SRC_ALPHA = gl.getField("GL_SRC_ALPHA");
            GL_ONE_MINUS_SRC_ALPHA = gl.getField("GL_ONE_MINUS_SRC_ALPHA");
            GL_TRIANGLE_FAN = gl.getField("GL_TRIANGLE_FAN");

            try {
                minecraftClass = Class.forName("net.minecraft.client.Minecraft", true, cl);
                getMinecraftMethod = minecraftClass.getMethod("getMinecraft");
                fontRendererField = minecraftClass.getField("fontRendererObj");
                Class<?> fr = Class.forName("net.minecraft.client.gui.FontRenderer", true, cl);
                drawStringWithShadowMethod = fr.getMethod("drawStringWithShadow",
                    String.class, float.class, float.class, int.class);
                getStringWidthMethod = fr.getMethod("getStringWidth", String.class);
            } catch (Exception e) {
                minecraftClass = Class.forName("ave", true, cl);
                getMinecraftMethod = minecraftClass.getMethod("A");
                fontRendererField = minecraftClass.getField("k");
                Class<?> fr = Class.forName("bjn", true, cl);
                drawStringWithShadowMethod = fr.getMethod("a",
                    String.class, float.class, float.class, int.class);
                try { getStringWidthMethod = fr.getMethod("getStringWidth", String.class); }
                catch (Exception ex) { getStringWidthMethod = fr.getMethod("a", String.class); }
            }

            try {
                Class<?> sr = Class.forName("net.minecraft.client.gui.ScaledResolution", true, cl);
                scaledResCtor = sr.getConstructor(minecraftClass);
                getScaledWidthMethod = sr.getMethod("getScaledWidth");
            } catch (Exception e) {
                Class<?> sr = Class.forName("avr", true, cl);
                scaledResCtor = sr.getConstructor(minecraftClass);
                getScaledWidthMethod = sr.getMethod("a");
            }
        } catch (Exception e) {
            AgentLogger.log("NotificationRenderer: Fallback init failed");
        }
    }

    private static void renderFallback(float partialTicks) {
        if (getMinecraftMethod == null || drawStringWithShadowMethod == null) return;

        long now = System.currentTimeMillis();
        long startTime = MCCTimerState.notificationStartTime;
        if (startTime == 0) { MCCTimerState.notificationStartTime = now; startTime = now; }
        long elapsed = now - startTime;
        long totalDuration = SLIDE_MS + DISPLAY_MS + SLIDE_MS;

        if (elapsed > totalDuration) {
            MCCTimerState.notificationMessage = "";
            MCCTimerState.notificationTitle = "";
            MCCTimerState.notificationPlayerName = "";
            MCCTimerState.notificationStartTime = 0;
            return;
        }

        try {
            Object mc = getMinecraftMethod.invoke(null);
            if (mc == null) return;
            Object font = fontRendererField.get(mc);
            if (font == null) return;
            Object sr = scaledResCtor.newInstance(mc);
            int screenW = (int) getScaledWidthMethod.invoke(sr);
            if (screenW == 0) return;

            String title = MCCTimerState.notificationTitle;
            String msg = MCCTimerState.notificationMessage;
            String playerName = MCCTimerState.notificationPlayerName;
            boolean hasTitle = title != null && !title.isEmpty();
            boolean hasPlayer = playerName != null && !playerName.isEmpty();

            float iconPad = hasPlayer ? 22f : 6f;
            int titleW = hasTitle ? (int) getStringWidthMethod.invoke(font, "\u00A7l" + title) : 0;
            int msgW = (int) getStringWidthMethod.invoke(font, msg);
            float boxW = Math.max(125f, Math.min(200f, Math.max(titleW, msgW) + iconPad + 6f));
            float boxH = hasTitle
                ? Math.max(20f, 10f + FONT_HEIGHT * 2 + 2)
                : Math.max(20f, 10f + FONT_HEIGHT);

            float slideProgress;
            if (elapsed < SLIDE_MS) {
                float t = (float) elapsed / SLIDE_MS;
                slideProgress = 1f - (1f - t) * (1f - t);
            } else if (elapsed > SLIDE_MS + DISPLAY_MS) {
                float t = (float)(elapsed - SLIDE_MS - DISPLAY_MS) / SLIDE_MS;
                slideProgress = 1f - t * t;
            } else {
                slideProgress = 1f;
            }
            float slideOffset = (10f + boxW) * (1f - slideProgress);
            float x = screenW - boxW - 4f + slideOffset;
            float y = 4f;

            int texId     = (int) GL_TEXTURE_2D.get(null);
            int quadsId   = (int) GL_QUADS.get(null);
            int blendId   = (int) GL_BLEND.get(null);
            int srcAlpha  = (int) GL_SRC_ALPHA.get(null);
            int oneMinSrc = (int) GL_ONE_MINUS_SRC_ALPHA.get(null);
            int triFanId  = (int) GL_TRIANGLE_FAN.get(null);

            glPushMatrix.invoke(null);
            glDisable.invoke(null, texId);
            glEnable.invoke(null, blendId);
            glBlendFunc.invoke(null, srcAlpha, oneMinSrc);

            // Background
            glColor4f.invoke(null, 0f, 0f, 0f, 0.69f);
            drawRoundedRect(x - 1, y - 1, boxW + 2, boxH + 2, 5f, quadsId, triFanId);
            glColor4f.invoke(null, 0.17f, 0.17f, 0.15f, 0.25f);
            drawRoundedRect(x, y, boxW, boxH, 4f, quadsId, triFanId);
            glColor4f.invoke(null, 1f, 1f, 1f, 0.13f);
            drawRoundedRect(x, y, boxW, boxH, 4f, quadsId, triFanId);

            // Player head from MC skin
            if (hasPlayer) {
                try {
                    drawPlayerHead(mc, playerName, x + 4f, y + boxH / 2f - 8f, texId, quadsId, blendId);
                } catch (Exception e) { /* silent */ }
            }

            // Text
            glEnable.invoke(null, texId);
            glDisable.invoke(null, blendId);
            float textX = x + iconPad;
            if (hasTitle) {
                drawStringWithShadowMethod.invoke(font, "\u00A7l" + title, textX, y + 5f, -1);
                drawStringWithShadowMethod.invoke(font, msg, textX, y + 5f + FONT_HEIGHT + 2, 0xFFAAAAAA);
            } else {
                float textY = y + (boxH - FONT_HEIGHT) / 2f;
                drawStringWithShadowMethod.invoke(font, msg, textX, textY, -1);
            }

            glPopMatrix.invoke(null);
        } catch (Exception e) { /* silent */ }
    }

    /**
     * Draws a player's face from their MC skin texture.
     */
    private static void drawPlayerHead(Object mc, String playerName,
                                        float x, float y, int texId, int quadsId, int blendId) throws Exception {
        Object player = null;
        try { player = mc.getClass().getField("thePlayer").get(mc); }
        catch (Exception e) { player = mc.getClass().getField("h").get(mc); }
        if (player == null) return;

        Object netHandler = null;
        try { netHandler = player.getClass().getField("sendQueue").get(player); }
        catch (Exception e) {
            for (Field f : player.getClass().getDeclaredFields()) {
                if (f.getType().getName().contains("NetHandlerPlayClient") || f.getType().getName().equals("bcy")) {
                    f.setAccessible(true); netHandler = f.get(player); break;
                }
            }
        }
        if (netHandler == null) return;

        // Get NetworkPlayerInfo for the player
        Object playerInfo = null;
        for (Method m : netHandler.getClass().getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == String.class
                && m.getReturnType() != null && !m.getReturnType().isPrimitive()
                && m.getReturnType() != String.class) {
                try { m.setAccessible(true); playerInfo = m.invoke(netHandler, playerName); if (playerInfo != null) break; }
                catch (Exception e) { /* next */ }
            }
        }
        if (playerInfo == null) return;

        // Get skin ResourceLocation
        Object skinLoc = null;
        try { skinLoc = playerInfo.getClass().getMethod("getLocationSkin").invoke(playerInfo); }
        catch (Exception e) {
            for (Method m : playerInfo.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getName().contains("ResourceLocation")) {
                    m.setAccessible(true); skinLoc = m.invoke(playerInfo); break;
                }
            }
        }
        if (skinLoc == null) return;

        // Bind texture
        Object texManager = null;
        try { texManager = mc.getClass().getMethod("getTextureManager").invoke(mc); }
        catch (Exception e) {
            try { texManager = mc.getClass().getField("renderEngine").get(mc); }
            catch (Exception e2) {
                for (Field f : mc.getClass().getDeclaredFields()) {
                    if (f.getType().getName().contains("TextureManager")) {
                        f.setAccessible(true); texManager = f.get(mc); break;
                    }
                }
            }
        }
        if (texManager == null) return;

        Method bindTex = null;
        for (Method m : texManager.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && skinLoc.getClass().isAssignableFrom(m.getParameterTypes()[0])) {
                bindTex = m; break;
            }
        }
        if (bindTex == null) {
            for (Method m : texManager.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(skinLoc.getClass())) {
                    bindTex = m; break;
                }
            }
        }
        if (bindTex == null) return;

        glEnable.invoke(null, texId);
        glColor4f.invoke(null, 1f, 1f, 1f, 1f);
        bindTex.setAccessible(true);
        bindTex.invoke(texManager, skinLoc);

        // Draw face (8x8 from skin atlas at UV 8,8→16,16 on 64x64, scaled to 16x16)
        float u1 = 8f/64f, v1 = 8f/64f, u2 = 16f/64f, v2 = 16f/64f;

        glBegin.invoke(null, quadsId);
        glTexCoord2f.invoke(null, u1, v1); glVertex2f.invoke(null, x, y);
        glTexCoord2f.invoke(null, u1, v2); glVertex2f.invoke(null, x, y + 16f);
        glTexCoord2f.invoke(null, u2, v2); glVertex2f.invoke(null, x + 16f, y + 16f);
        glTexCoord2f.invoke(null, u2, v1); glVertex2f.invoke(null, x + 16f, y);
        glEnd.invoke(null);

        // Hat overlay (UV 40,8→48,16 on 64x64)
        glEnable.invoke(null, blendId);
        float hu1 = 40f/64f, hv1 = 8f/64f, hu2 = 48f/64f, hv2 = 16f/64f;

        glBegin.invoke(null, quadsId);
        glTexCoord2f.invoke(null, hu1, hv1); glVertex2f.invoke(null, x - 0.5f, y - 0.5f);
        glTexCoord2f.invoke(null, hu1, hv2); glVertex2f.invoke(null, x - 0.5f, y + 16.5f);
        glTexCoord2f.invoke(null, hu2, hv2); glVertex2f.invoke(null, x + 16.5f, y + 16.5f);
        glTexCoord2f.invoke(null, hu2, hv1); glVertex2f.invoke(null, x + 16.5f, y - 0.5f);
        glEnd.invoke(null);
    }

    // ============================
    // GL HELPERS
    // ============================

    private static void drawRoundedRect(float x, float y, float w, float h,
                                         float r, int quadsId, int triFanId) throws Exception {
        glBegin.invoke(null, quadsId);
        glVertex2f.invoke(null, x + r, y); glVertex2f.invoke(null, x + r, y + h);
        glVertex2f.invoke(null, x + w - r, y + h); glVertex2f.invoke(null, x + w - r, y);
        glVertex2f.invoke(null, x, y + r); glVertex2f.invoke(null, x, y + h - r);
        glVertex2f.invoke(null, x + r, y + h - r); glVertex2f.invoke(null, x + r, y + r);
        glVertex2f.invoke(null, x + w - r, y + r); glVertex2f.invoke(null, x + w - r, y + h - r);
        glVertex2f.invoke(null, x + w, y + h - r); glVertex2f.invoke(null, x + w, y + r);
        glEnd.invoke(null);
        int s = 8;
        drawCornerArc(x + r, y + r, r, 180, 270, s, triFanId);
        drawCornerArc(x + w - r, y + r, r, 270, 360, s, triFanId);
        drawCornerArc(x + w - r, y + h - r, r, 0, 90, s, triFanId);
        drawCornerArc(x + r, y + h - r, r, 90, 180, s, triFanId);
    }

    private static void drawCornerArc(float cx, float cy, float r,
                                        int sa, int ea, int seg, int triFanId) throws Exception {
        glBegin.invoke(null, triFanId);
        glVertex2f.invoke(null, cx, cy);
        for (int i = 0; i <= seg; i++) {
            double a = Math.toRadians(sa + (ea - sa) * i / (double) seg);
            glVertex2f.invoke(null, cx + (float)(Math.cos(a) * r), cy + (float)(Math.sin(a) * r));
        }
        glEnd.invoke(null);
    }
}
