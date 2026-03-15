package com.mcctimer.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Renders a simple overlay showing the nearest primed TNT fuse time.
 * Uses the same edit-mode flow as the timer + fireball HUD.
 */
public class TntHUD {

    // Minecraft reflection handles
    private static Class<?> minecraftClass;
    private static Method getMinecraftMethod;
    private static Field fontRendererField;
    private static Method drawStringWithShadowMethod;
    private static Method getStringWidthMethod;
    private static Field theWorldField;
    private static Field thePlayerField;
    private static Field loadedEntityListField;

    // ScaledResolution
    private static java.lang.reflect.Constructor<?> scaledResCtor;
    private static Method getScaledWidthMethod;
    private static Method getScaledHeightMethod;

    // GL11
    private static Method glPushMatrix, glPopMatrix, glTranslatef, glScalef;
    private static Method glEnable, glDisable, glColor4f, glLineWidth;
    private static Method glBegin, glEnd, glVertex2f;
    private static Field GL_TEXTURE_2D, GL_BLEND, GL_LIGHTING, GL_LINES;

    // RenderItem for TNT icon
    private static Method renderItemMethod;
    private static Object tntItemStack;

    // Entity fields
    private static Class<?> entityClass;
    private static Field posXField, posYField, posZField;

    // TNT specific
    private static Class<?> tntClass;
    private static final String[] knownTntNames = new String[]{
        "net.minecraft.entity.item.EntityTNTPrimed", // MCP
        "aex", "aew", "aep", "aeo", "aen", "ael"      // common 1.8.x obf guesses
    };
    private static Method getFuseMethod;
    private static Field fuseField;

    private static boolean initialized = false;
    private static boolean initFailed = false;
    private static boolean configLoaded = false;

    // Exposed for edit hitboxes
    public static float lastHudWidth = 160;
    public static float lastHudHeight = 26;

    private static final int COLOR_GREEN = 0x55FF55;
    private static final int COLOR_RED = 0xFF5555;
    private static final int DEFAULT_FUSE_TICKS = 80; // vanilla TNT = 4 seconds

    private static final int FONT_HEIGHT = 9;

    private static void ensureConfigLoaded() {
        if (!configLoaded) {
            MCCTimerState.loadConfig();
            configLoaded = true;
        }
    }

    private static void init() {
        if (initialized || initFailed) return;
        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null) return;

            // GL11
            try {
                Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11", true, gameLoader);
                glPushMatrix = gl11.getMethod("glPushMatrix");
                glPopMatrix = gl11.getMethod("glPopMatrix");
                glTranslatef = gl11.getMethod("glTranslatef", float.class, float.class, float.class);
                glScalef = gl11.getMethod("glScalef", float.class, float.class, float.class);
                glEnable = gl11.getMethod("glEnable", int.class);
                glDisable = gl11.getMethod("glDisable", int.class);
                glColor4f = gl11.getMethod("glColor4f", float.class, float.class, float.class, float.class);
                glLineWidth = gl11.getMethod("glLineWidth", float.class);
                glBegin = gl11.getMethod("glBegin", int.class);
                glEnd = gl11.getMethod("glEnd");
                glVertex2f = gl11.getMethod("glVertex2f", float.class, float.class);
                GL_TEXTURE_2D = gl11.getField("GL_TEXTURE_2D");
                GL_BLEND = gl11.getField("GL_BLEND");
                GL_LIGHTING = gl11.getField("GL_LIGHTING");
                GL_LINES = gl11.getField("GL_LINES");
            } catch (Exception e) {
                AgentLogger.log("TntHUD: Failed GL init");
            }

            // Minecraft + font
            try {
                minecraftClass = Class.forName("net.minecraft.client.Minecraft", true, gameLoader);
                getMinecraftMethod = minecraftClass.getMethod("getMinecraft");
                fontRendererField = minecraftClass.getField("fontRendererObj");
                Class<?> fontRendererClass = Class.forName("net.minecraft.client.gui.FontRenderer", true, gameLoader);
                drawStringWithShadowMethod = fontRendererClass.getMethod("drawStringWithShadow", String.class, float.class, float.class, int.class);
                getStringWidthMethod = fontRendererClass.getMethod("getStringWidth", String.class);
                try { theWorldField = minecraftClass.getField("theWorld"); } catch (Exception e) { theWorldField = minecraftClass.getField("f"); }
                try { thePlayerField = minecraftClass.getField("thePlayer"); } catch (Exception e) { thePlayerField = minecraftClass.getField("h"); }
            } catch (Exception e) {
                try {
                    minecraftClass = Class.forName("ave", true, gameLoader);
                    getMinecraftMethod = minecraftClass.getMethod("A");
                    fontRendererField = minecraftClass.getField("k");
                    Class<?> fontRendererClass = Class.forName("bjn", true, gameLoader);
                    drawStringWithShadowMethod = fontRendererClass.getMethod("a", String.class, float.class, float.class, int.class);
                    try {
                        getStringWidthMethod = fontRendererClass.getMethod("getStringWidth", String.class);
                    } catch (Exception ex) {
                        getStringWidthMethod = fontRendererClass.getMethod("a", String.class);
                    }
                    try { theWorldField = minecraftClass.getField("f"); } catch (Exception e2) { theWorldField = minecraftClass.getField("theWorld"); }
                    try { thePlayerField = minecraftClass.getField("h"); } catch (Exception e2) { thePlayerField = minecraftClass.getField("thePlayer"); }
                } catch (Exception e2) {
                    AgentLogger.log("TntHUD: Failed MC init");
                    AgentLogger.log(e2);
                    initFailed = true;
                    return;
                }
            }

            // ScaledResolution
            try {
                Class<?> srClass = Class.forName("net.minecraft.client.gui.ScaledResolution", true, gameLoader);
                scaledResCtor = srClass.getConstructor(minecraftClass);
                getScaledWidthMethod = srClass.getMethod("getScaledWidth");
                getScaledHeightMethod = srClass.getMethod("getScaledHeight");
            } catch (Exception e) {
                try {
                    Class<?> srClass = Class.forName("avr", true, gameLoader);
                    scaledResCtor = srClass.getConstructor(minecraftClass);
                    getScaledWidthMethod = srClass.getMethod("a");
                    getScaledHeightMethod = srClass.getMethod("b");
                } catch (Exception ignored) {}
            }

            // Entity + position fields
            try {
                entityClass = Class.forName("net.minecraft.entity.Entity", true, gameLoader);
            } catch (Exception e) {
                entityClass = Class.forName("pk", true, gameLoader);
            }
            try { posXField = entityClass.getField("posX"); } catch (Exception e) { posXField = entityClass.getField("s"); }
            try { posYField = entityClass.getField("posY"); } catch (Exception e) { posYField = entityClass.getField("t"); }
            try { posZField = entityClass.getField("posZ"); } catch (Exception e) { posZField = entityClass.getField("u"); }

            // TNT class (MCP) + fallback guess
            try {
                tntClass = Class.forName("net.minecraft.entity.item.EntityTNTPrimed", true, gameLoader);
            } catch (Exception e) {
                try {
                    tntClass = Class.forName("aex", true, gameLoader);
                } catch (Exception ignored) {}
            }

            // loadedEntityList from World
            Class<?> worldClass;
            try {
                worldClass = Class.forName("net.minecraft.world.World", true, gameLoader);
            } catch (Exception e) {
                worldClass = Class.forName("adm", true, gameLoader);
            }
            try { loadedEntityListField = worldClass.getField("loadedEntityList"); } catch (Exception e) { loadedEntityListField = worldClass.getField("e"); }

            // RenderItem + TNT item stack
            try {
                Class<?> itemClass;
                try { itemClass = Class.forName("net.minecraft.item.Item", true, gameLoader); }
                catch (Exception e) { itemClass = Class.forName("acw", true, gameLoader); }
                Method getItemById;
                try { getItemById = itemClass.getMethod("getItemById", int.class); }
                catch (Exception e) { getItemById = itemClass.getMethod("d", int.class); }
                Object tntItem = getItemById.invoke(null, 46); // TNT block item id

                Class<?> itemStackClass;
                try { itemStackClass = Class.forName("net.minecraft.item.ItemStack", true, gameLoader); }
                catch (Exception e) { itemStackClass = Class.forName("zx", true, gameLoader); }
                tntItemStack = itemStackClass.getConstructor(itemClass).newInstance(tntItem);

                Class<?> renderItemClass;
                try { renderItemClass = Class.forName("net.minecraft.client.renderer.entity.RenderItem", true, gameLoader); }
                catch (Exception e) { renderItemClass = Class.forName("biq", true, gameLoader); }
                try {
                    renderItemMethod = renderItemClass.getMethod("renderItemAndEffectIntoGUI", itemStackClass, int.class, int.class);
                } catch (Exception e) {
                    for (Method m : renderItemClass.getDeclaredMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 3 && p[0] == itemStackClass && p[1] == int.class && p[2] == int.class) {
                            renderItemMethod = m;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                AgentLogger.log("TntHUD: Failed RenderItem init");
            }

            initialized = true;
        } catch (Exception e) {
            AgentLogger.log("TntHUD: Fatal init error");
            AgentLogger.log(e);
            initFailed = true;
        }
    }

    private static void resolveFuseAccessors(Class<?> cls) {
        if (cls == null) return;
        if (getFuseMethod != null || fuseField != null) return;

        for (String name : new String[]{"getFuse", "func_70515_d", "l", "m"}) {
            try {
                Method m = cls.getMethod(name);
                if (m.getReturnType() == int.class) {
                    m.setAccessible(true);
                    getFuseMethod = m;
                    return;
                }
            } catch (Exception ignored) {}
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0 && m.getReturnType() == int.class) {
                try {
                    m.setAccessible(true);
                    getFuseMethod = m;
                    return;
                } catch (Exception ignored) {}
            }
        }
        for (String name : new String[]{"fuse", "field_70516_a", "a", "b"}) {
            try {
                Field f = cls.getDeclaredField(name);
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    fuseField = f;
                    return;
                }
            } catch (Exception ignored) {}
        }
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType() == int.class) {
                try {
                    f.setAccessible(true);
                    fuseField = f;
                    return;
                } catch (Exception ignored) {}
            }
        }
    }

    private static Integer readFuseTicks(Object entity) {
        if (entity == null) return null;
        try {
            if (getFuseMethod != null) {
                return (Integer) getFuseMethod.invoke(entity);
            }
            if (fuseField != null) {
                return fuseField.getInt(entity);
            }
            resolveFuseAccessors(entity.getClass());
            if (getFuseMethod != null) {
                return (Integer) getFuseMethod.invoke(entity);
            }
            if (fuseField != null) {
                return fuseField.getInt(entity);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isTntEntity(Object entity) {
        if (entity == null) return false;
        if (tntClass != null && tntClass.isInstance(entity)) {
            return readFuseTicks(entity) != null;
        }

        Class<?> cls = entity.getClass();
        String fullName = cls.getName().toLowerCase();
        if (fullName.contains("creeper")) return false; // explicit skip

        // Known names / obvious TNT substring
        boolean nameMatch = false;
        for (String n : knownTntNames) {
            if (cls.getName().equals(n) || cls.getSimpleName().equals(n)) {
                nameMatch = true;
                break;
            }
        }
        if (!nameMatch && fullName.contains("tnt")) {
            nameMatch = true;
        }
        if (!nameMatch) {
            return false;
        }

        resolveFuseAccessors(cls);
        Integer val = readFuseTicks(entity);
        if (val != null && val >= 0 && val <= 400) {
            tntClass = cls;
            return true;
        }
        return false; // no readable fuse -> ignore
    }

    public static float getHudX(int screenW) {
        if (MCCTimerState.tntHudX == Float.MAX_VALUE) {
            return screenW / 2f - 70;
        }
        return MCCTimerState.tntHudX;
    }

    public static float getHudY(int screenH) {
        if (MCCTimerState.tntHudY == Float.MAX_VALUE) {
            return screenH * 0.35f;
        }
        return MCCTimerState.tntHudY;
    }

    public static void renderTntOverlay(float partialTicks) {
        ensureConfigLoaded();
        if (!MCCTimerState.tntHudEnabled && !MCCTimerState.editMode) return;

        init();
        if (!initialized || initFailed) return;
        if (getMinecraftMethod == null || drawStringWithShadowMethod == null || glPushMatrix == null) return;

        try {
            Object mc = getMinecraftMethod.invoke(null);
            if (mc == null) return;

            Object fontRenderer = fontRendererField.get(mc);
            if (fontRenderer == null) return;

            int screenW = 0, screenH = 0;
            if (scaledResCtor != null) {
                Object sr = scaledResCtor.newInstance(mc);
                screenW = (int) getScaledWidthMethod.invoke(sr);
                screenH = (int) getScaledHeightMethod.invoke(sr);
            }
            if (screenW == 0 || screenH == 0) return;

            boolean isEditMode = MCCTimerState.editMode;
            double closestDist = Double.MAX_VALUE;
            double closestFuseTicks = -1;

            if (!isEditMode && MCCTimerState.tntHudEnabled) {
                Object world = theWorldField.get(mc);
                Object player = thePlayerField.get(mc);
                if (world == null || player == null) return;

                double px = posXField.getDouble(player);
                double py = posYField.getDouble(player);
                double pz = posZField.getDouble(player);

                List<?> entities = (List<?>) loadedEntityListField.get(world);
                for (Object entity : entities) {
                    if (!isTntEntity(entity)) continue;
                    double ex = posXField.getDouble(entity);
                    double ey = posYField.getDouble(entity);
                    double ez = posZField.getDouble(entity);
                    double dx = ex - px, dy = ey - py, dz = ez - pz;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

                    if (dist > MCCTimerState.tntDetectDistance || dist >= closestDist) continue;
                    Integer fuse = readFuseTicks(entity);
                    if (fuse == null || fuse < 0) continue;

                    closestDist = dist;
                    closestFuseTicks = fuse;
                }

                if (closestDist == Double.MAX_VALUE) {
                    return; // no TNT nearby
                }
            } else if (!isEditMode) {
                return;
            }

            float hudX = getHudX(screenW);
            float hudY = getHudY(screenH);
            float scale = MCCTimerState.tntHudScale;

            String title;
            if (isEditMode) {
                title = "\u00A7c\u00A7lTNT in: \u00A7e3.5s";
                closestFuseTicks = 70;
            } else {
                double fuseSeconds = closestFuseTicks / 20.0;
                if (fuseSeconds < 0) fuseSeconds = 0;
                title = String.format("\u00A7c\u00A7lTNT in: \u00A7e%.1fs", fuseSeconds);
            }

            int titleWidth = (int) getStringWidthMethod.invoke(fontRenderer, title);
            float totalWidth = 20 + titleWidth;
            float totalHeight = 16 + 2 + FONT_HEIGHT; // icon + gap + one line
            lastHudWidth = totalWidth * scale;
            lastHudHeight = totalHeight * scale;

            glPushMatrix.invoke(null);
            glTranslatef.invoke(null, hudX, hudY, 0f);
            glScalef.invoke(null, scale, scale, 1.0f);

            // Draw TNT icon
            if (renderItemMethod != null && tntItemStack != null) {
                try {
                    Object ri = null;
                    try {
                        ri = mc.getClass().getMethod("getRenderItem").invoke(mc);
                    } catch (Exception e) {
                        for (Field f : mc.getClass().getDeclaredFields()) {
                            String typeName = f.getType().getName();
                            if (typeName.contains("RenderItem") || typeName.contains("biq")) {
                                f.setAccessible(true);
                                ri = f.get(mc);
                                break;
                            }
                        }
                    }
                    if (ri != null) {
                        renderItemMethod.invoke(ri, tntItemStack, 0, 0);
                        if (GL_LIGHTING != null && glDisable != null) glDisable.invoke(null, GL_LIGHTING.getInt(null));
                        if (GL_BLEND != null && glEnable != null) glEnable.invoke(null, GL_BLEND.getInt(null));
                        if (glColor4f != null) glColor4f.invoke(null, 1.0f, 1.0f, 1.0f, 1.0f);
                    }
                } catch (Exception ignored) {}
            }

            // Text
            float textX = 20;
            int color = computeFuseColor(closestFuseTicks);
            drawStringWithShadowMethod.invoke(fontRenderer, title, textX, 1f, color);

            glPopMatrix.invoke(null);

            if (isEditMode) {
                drawEditBorder(hudX, hudY, lastHudWidth, lastHudHeight,
                        MCCTimerState.tntDragging ? 0x00FF00 : 0xFF4444);
            }
        } catch (Exception e) {
            // Silent failure to avoid crashing render thread
        }
    }

    private static void drawEditBorder(float x, float y, float w, float h, int color) {
        try {
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;

            int texId = GL_TEXTURE_2D.getInt(null);
            int linesId = GL_LINES.getInt(null);

            glDisable.invoke(null, texId);
            glColor4f.invoke(null, r, g, b, 1.0f);
            glLineWidth.invoke(null, 2.0f);

            glBegin.invoke(null, linesId);
            glVertex2f.invoke(null, x - 1, y - 1);
            glVertex2f.invoke(null, x + w + 1, y - 1);
            glVertex2f.invoke(null, x + w + 1, y - 1);
            glVertex2f.invoke(null, x + w + 1, y + h + 1);
            glVertex2f.invoke(null, x + w + 1, y + h + 1);
            glVertex2f.invoke(null, x - 1, y + h + 1);
            glVertex2f.invoke(null, x - 1, y + h + 1);
            glVertex2f.invoke(null, x - 1, y - 1);
            glEnd.invoke(null);

            glEnable.invoke(null, texId);
            glColor4f.invoke(null, 1.0f, 1.0f, 1.0f, 1.0f);
        } catch (Exception ignored) {}
    }

    private static int computeFuseColor(double fuseTicks) {
        // Map fuse to a hue sweep (green -> yellow -> red) like Lunar's gradient
        double t = Math.max(0.0, Math.min(1.0, fuseTicks / DEFAULT_FUSE_TICKS));
        // Hue 120 (green) at full, down to 0 (red) at zero fuse
        double hue = 120.0 * t;
        return hsvToRgb(hue, 1.0, 1.0);
    }

    private static int hsvToRgb(double hueDeg, double sat, double val) {
        double h = ((hueDeg % 360) + 360) % 360;
        double c = val * sat;
        double x = c * (1 - Math.abs((h / 60.0) % 2 - 1));
        double m = val - c;
        double rPrime = 0, gPrime = 0, bPrime = 0;
        if (h < 60)      { rPrime = c; gPrime = x; bPrime = 0; }
        else if (h < 120){ rPrime = x; gPrime = c; bPrime = 0; }
        else if (h < 180){ rPrime = 0; gPrime = c; bPrime = x; }
        else if (h < 240){ rPrime = 0; gPrime = x; bPrime = c; }
        else if (h < 300){ rPrime = x; gPrime = 0; bPrime = c; }
        else             { rPrime = c; gPrime = 0; bPrime = x; }
        int r = (int) Math.round((rPrime + m) * 255.0);
        int g = (int) Math.round((gPrime + m) * 255.0);
        int b = (int) Math.round((bPrime + m) * 255.0);
        return (r << 16) | (g << 8) | b;
    }
}
