package com.mcctimer.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Renders a proximity warning HUD when a fireball entity is within 7 blocks.
 * Shows the fire charge item texture, plus "Approaching in: X.Xs | Distance: Y.Ym".
 * Position is adjustable via /gui edit, alongside the timer.
 * 
 * NOTE: Mouse/drag handling for edit mode is done inside TimerRenderer to prevent
 * drag conflicts and getDWheel() consumption races.
 */
public class FireballHUD {

    // Minecraft reflection handles
    private static Class<?> minecraftClass;
    private static Method getMinecraftMethod;
    private static Field fontRendererField;
    private static Method drawStringWithShadowMethod;
    private static Method getStringWidthMethod;

    // ScaledResolution
    private static java.lang.reflect.Constructor<?> scaledResCtor;
    private static Method getScaledWidthMethod;
    private static Method getScaledHeightMethod;

    // GL11
    private static Method glPushMatrix, glPopMatrix, glTranslatef, glScalef;
    private static Method glEnable, glDisable, glColor4f, glLineWidth;
    private static Method glBegin, glEnd, glVertex2f;
    private static Method glDepthMask;
    private static Field GL_TEXTURE_2D, GL_BLEND, GL_LIGHTING, GL_LINES, GL_DEPTH_TEST;

    // RenderItem for drawing the fire charge texture
    private static Method renderItemMethod;

    // ItemStack for fire charge
    private static Object fireChargeStack;

    // World / entity iteration
    private static Field theWorldField;
    private static Field thePlayerField;
    private static Field loadedEntityListField;
    private static Class<?> entityFireballClass;

    // Position/motion fields on Entity
    private static Field posXField, posYField, posZField;
    private static Field motionXField, motionYField, motionZField;

    private static boolean initialized = false;
    private static boolean initFailed = false;

    // Exposed HUD dimensions for TimerRenderer's edit-mode hitbox
    public static float lastHudWidth = 200;
    public static float lastHudHeight = 30;

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
                glDepthMask = gl11.getMethod("glDepthMask", boolean.class);
                GL_TEXTURE_2D = gl11.getField("GL_TEXTURE_2D");
                GL_BLEND = gl11.getField("GL_BLEND");
                GL_LIGHTING = gl11.getField("GL_LIGHTING");
                GL_LINES = gl11.getField("GL_LINES");
                GL_DEPTH_TEST = gl11.getField("GL_DEPTH_TEST");
            } catch (Exception e) {
                AgentLogger.log("FireballHUD: Failed GL11 init");
                AgentLogger.log(e);
            }

            // Minecraft instance
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
                    AgentLogger.log("FireballHUD: Failed MC init");
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
                } catch (Exception e2) {}
            }

            // Entity fields
            Class<?> entityClass;
            try {
                entityClass = Class.forName("net.minecraft.entity.Entity", true, gameLoader);
            } catch (Exception e) {
                entityClass = Class.forName("pk", true, gameLoader);
            }
            try { posXField = entityClass.getField("posX"); } catch (Exception e) { posXField = entityClass.getField("s"); }
            try { posYField = entityClass.getField("posY"); } catch (Exception e) { posYField = entityClass.getField("t"); }
            try { posZField = entityClass.getField("posZ"); } catch (Exception e) { posZField = entityClass.getField("u"); }
            try { motionXField = entityClass.getField("motionX"); } catch (Exception e) { motionXField = entityClass.getField("v"); }
            try { motionYField = entityClass.getField("motionY"); } catch (Exception e) { motionYField = entityClass.getField("w"); }
            try { motionZField = entityClass.getField("motionZ"); } catch (Exception e) { motionZField = entityClass.getField("x"); }

            // EntityFireball
            try {
                entityFireballClass = Class.forName("net.minecraft.entity.projectile.EntityFireball", true, gameLoader);
            } catch (Exception e) {
                entityFireballClass = Class.forName("we", true, gameLoader);
            }

            // loadedEntityList from World
            Class<?> worldClass;
            try {
                worldClass = Class.forName("net.minecraft.world.World", true, gameLoader);
            } catch (Exception e) {
                worldClass = Class.forName("adm", true, gameLoader);
            }
            try { loadedEntityListField = worldClass.getField("loadedEntityList"); } catch (Exception e) { loadedEntityListField = worldClass.getField("e"); }

            // Fire charge ItemStack
            try {
                Class<?> itemClass;
                try {
                    itemClass = Class.forName("net.minecraft.item.Item", true, gameLoader);
                } catch (Exception e) {
                    itemClass = Class.forName("acw", true, gameLoader);
                }
                Method getItemById;
                try {
                    getItemById = itemClass.getMethod("getItemById", int.class);
                } catch (Exception e) {
                    getItemById = itemClass.getMethod("d", int.class);
                }
                Object fireChargeItem = getItemById.invoke(null, 385);
                Class<?> itemStackClass;
                try {
                    itemStackClass = Class.forName("net.minecraft.item.ItemStack", true, gameLoader);
                } catch (Exception e) {
                    itemStackClass = Class.forName("zx", true, gameLoader);
                }
                fireChargeStack = itemStackClass.getConstructor(itemClass).newInstance(fireChargeItem);
            } catch (Exception e) {
                AgentLogger.log("FireballHUD: Failed to create fire_charge ItemStack");
                AgentLogger.log(e);
            }

            // RenderItem
            try {
                Class<?> renderItemClass;
                try {
                    renderItemClass = Class.forName("net.minecraft.client.renderer.entity.RenderItem", true, gameLoader);
                } catch (Exception e) {
                    renderItemClass = Class.forName("biq", true, gameLoader);
                }
                Class<?> itemStackClass;
                try {
                    itemStackClass = Class.forName("net.minecraft.item.ItemStack", true, gameLoader);
                } catch (Exception e) {
                    itemStackClass = Class.forName("zx", true, gameLoader);
                }
                try {
                    renderItemMethod = renderItemClass.getMethod("renderItemAndEffectIntoGUI", itemStackClass, int.class, int.class);
                } catch (Exception e) {
                    for (Method m : renderItemClass.getDeclaredMethods()) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 3 && params[0] == itemStackClass && params[1] == int.class && params[2] == int.class) {
                            renderItemMethod = m;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                AgentLogger.log("FireballHUD: Failed RenderItem init");
                AgentLogger.log(e);
            }

            initialized = true;
            AgentLogger.log("FireballHUD initialized successfully.");
        } catch (Exception e) {
            AgentLogger.log("FireballHUD: Fatal init error");
            AgentLogger.log(e);
            initFailed = true;
        }
    }

    /**
     * Computes the on-screen X position of the HUD.
     */
    public static float getHudX(int screenW) {
        if (MCCTimerState.fireballHudX == Float.MAX_VALUE) {
            return screenW / 2f - 100;
        }
        return MCCTimerState.fireballHudX;
    }

    /**
     * Computes the on-screen Y position of the HUD.
     */
    public static float getHudY(int screenH) {
        if (MCCTimerState.fireballHudY == Float.MAX_VALUE) {
            return screenH * 0.25f;
        }
        return MCCTimerState.fireballHudY;
    }

    /**
     * Called every frame from renderGameOverlay ASM hook.
     */
    public static void renderFireballOverlay(float partialTicks) {
        init();
        if (!initialized || initFailed) return;
        if (getMinecraftMethod == null || drawStringWithShadowMethod == null || glPushMatrix == null) return;

        try {
            Object mc = getMinecraftMethod.invoke(null);
            if (mc == null) return;

            Object fontRenderer = fontRendererField.get(mc);
            if (fontRenderer == null) return;

            // Get screen dimensions
            int screenW = 0, screenH = 0;
            if (scaledResCtor != null) {
                Object sr = scaledResCtor.newInstance(mc);
                screenW = (int) getScaledWidthMethod.invoke(sr);
                screenH = (int) getScaledHeightMethod.invoke(sr);
            }
            if (screenW == 0 || screenH == 0) return;

            boolean isEditMode = MCCTimerState.editMode;
            double closestDist = Double.MAX_VALUE;
            double closestSpeed = 0;
            boolean hasFireball = false;

            if (!isEditMode) {
                Object world = theWorldField.get(mc);
                Object player = thePlayerField.get(mc);
                if (world == null || player == null) return;

                double px = posXField.getDouble(player);
                double py = posYField.getDouble(player);
                double pz = posZField.getDouble(player);

                List<?> entities = (List<?>) loadedEntityListField.get(world);
                for (Object entity : entities) {
                    if (!entityFireballClass.isInstance(entity)) continue;
                    double ex = posXField.getDouble(entity);
                    double ey = posYField.getDouble(entity);
                    double ez = posZField.getDouble(entity);
                    double dx = ex - px, dy = ey - py, dz = ez - pz;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

                    if (dist <= MCCTimerState.fireballDetectDistance && dist < closestDist) {
                        closestDist = dist;
                        hasFireball = true;
                        double mx = motionXField.getDouble(entity);
                        double my = motionYField.getDouble(entity);
                        double mz = motionZField.getDouble(entity);
                        closestSpeed = Math.sqrt(mx * mx + my * my + mz * mz);
                    }
                }
                if (!hasFireball) return;
            }

            float hudX = getHudX(screenW);
            float hudY = getHudY(screenH);
            float scale = MCCTimerState.fireballHudScale;

            // Build text
            String subtitle;
            if (isEditMode) {
                subtitle = "\u00A7c\u00A7lApproaching in: \u00A7e3.2s \u00A77| \u00A7cDistance: \u00A7e5.0m";
            } else {
                double approachTime = closestSpeed > 0.01 ? closestDist / (closestSpeed * 20.0) : 99.9;
                if (approachTime > 99.9) approachTime = 99.9;
                subtitle = String.format("\u00A7c\u00A7lApproaching in: \u00A7e%.1fs \u00A77| \u00A7cDistance: \u00A7e%.1fm",
                        approachTime, closestDist);
            }

            int subtitleWidth = (int) getStringWidthMethod.invoke(fontRenderer, subtitle);

            // Calculate total HUD dimensions for edit-mode hitbox
            float totalWidth = Math.max(16, subtitleWidth);
            float totalHeight = 16 + 4 + 9; // icon(16) + gap(4) + text(9)
            lastHudWidth = totalWidth * scale;
            lastHudHeight = totalHeight * scale;

            // === Render ===
            glPushMatrix.invoke(null);
            glTranslatef.invoke(null, hudX, hudY, 0f);
            glScalef.invoke(null, scale, scale, 1.0f);

            float innerCenterX = totalWidth / 2f;

            // Draw fire charge icon centered
            if (renderItemMethod != null && fireChargeStack != null) {
                try {
                    Object ri = null;
                    try {
                        ri = mc.getClass().getMethod("getRenderItem").invoke(mc);
                    } catch (Exception e) {
                        for (Field f : mc.getClass().getDeclaredFields()) {
                            String typeName = f.getType().getName();
                            if (typeName.contains("biq") || typeName.contains("RenderItem")) {
                                f.setAccessible(true);
                                ri = f.get(mc);
                                break;
                            }
                        }
                    }
                    if (ri != null) {
                        int iconX = (int)(innerCenterX - 8);
                        int iconY = 0;
                        renderItemMethod.invoke(ri, fireChargeStack, iconX, iconY);

                        // Aggressively reset GL state after item render
                        int lightingId = GL_LIGHTING.getInt(null);
                        glDisable.invoke(null, lightingId);
                        int depthId = GL_DEPTH_TEST.getInt(null);
                        glDisable.invoke(null, depthId);
                        int texId = GL_TEXTURE_2D.getInt(null);
                        glEnable.invoke(null, texId);
                        int blendId = GL_BLEND.getInt(null);
                        glEnable.invoke(null, blendId);
                        glColor4f.invoke(null, 1.0f, 1.0f, 1.0f, 1.0f);
                    }
                } catch (Exception e) {}
            }

            // Draw subtitle text centered below icon
            float textX = innerCenterX - subtitleWidth / 2f;
            float textY = 20; // below 16px icon + 4px gap
            drawStringWithShadowMethod.invoke(fontRenderer, subtitle, textX, textY, 0xFFFFFF);

            glPopMatrix.invoke(null);

            // Draw edit mode border (orange)
            if (isEditMode) {
                drawEditBorder(hudX, hudY, lastHudWidth, lastHudHeight,
                        MCCTimerState.fireballDragging ? 0x00FF00 : 0xFF6600);
            }

        } catch (Exception e) {
            // Silently fail
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
            // Reset color to white after drawing border
            glColor4f.invoke(null, 1.0f, 1.0f, 1.0f, 1.0f);
        } catch (Exception e) {}
    }
}
