package com.mcctimer.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class TimerRenderer {

    // Minecraft reflection handles
    private static Class<?> minecraftClass;
    private static Method getMinecraftMethod;
    private static Field fontRendererField;
    private static Field currentScreenField;
    private static Method drawStringWithShadowMethod;
    private static Method getStringWidthMethod;

    // Cached text dimensions (updated each frame)
    private static float lastTimerTextWidth = 42;
    private static final int FONT_HEIGHT = 9;

    // ScaledResolution reflection
    private static java.lang.reflect.Constructor<?> scaledResCtor;
    private static Method getScaledWidthMethod;
    private static Method getScaledHeightMethod;

    // GL11 reflection handles
    private static Method glPushMatrix, glPopMatrix, glTranslatef, glScalef;
    private static Method glEnable, glDisable, glColor4f, glLineWidth;
    private static Method glBegin, glEnd, glVertex2f;
    private static Field GL_TEXTURE_2D, GL_LINES;

    // LWJGL Mouse
    private static Method mouseGetX, mouseGetY, mouseIsButtonDown, mouseGetDWheel;
    private static Method mouseSetGrabbed;

    // LWJGL Keyboard
    private static Method kbIsKeyDown;
    private static int KEY_ESCAPE = 1;

    // Display
    private static Method displayGetWidth, displayGetHeight;

    private static boolean initialized = false;
    private static boolean configLoaded = false;
    private static boolean lastEscState = false;
    private static boolean wasInEditMode = false;

    private static void init() {
        if (initialized)
            return;
        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null)
                return;

            AgentLogger.log("TimerRenderer init using classloader: " + gameLoader.getClass().getName());

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
                GL_LINES = gl11.getField("GL_LINES");
            } catch (Exception e) {
                AgentLogger.log("TimerRenderer: Failed to resolve GL11!");
                AgentLogger.log(e);
            }

            // Mouse
            try {
                Class<?> mouseClass = Class.forName("org.lwjgl.input.Mouse", true, gameLoader);
                mouseGetX = mouseClass.getMethod("getX");
                mouseGetY = mouseClass.getMethod("getY");
                mouseIsButtonDown = mouseClass.getMethod("isButtonDown", int.class);
                mouseGetDWheel = mouseClass.getMethod("getDWheel");
                mouseSetGrabbed = mouseClass.getMethod("setGrabbed", boolean.class);
            } catch (Exception e) {
                AgentLogger.log("TimerRenderer: Failed to resolve Mouse!");
            }

            // Keyboard
            try {
                Class<?> kbClass = Class.forName("org.lwjgl.input.Keyboard", true, gameLoader);
                kbIsKeyDown = kbClass.getMethod("isKeyDown", int.class);
                KEY_ESCAPE = kbClass.getField("KEY_ESCAPE").getInt(null);
            } catch (Exception e) {
                AgentLogger.log("TimerRenderer: Failed to resolve Keyboard!");
            }

            // Display
            try {
                Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display", true, gameLoader);
                displayGetWidth = displayClass.getMethod("getWidth");
                displayGetHeight = displayClass.getMethod("getHeight");
            } catch (Exception e) {
            }

            // Minecraft
            try {
                minecraftClass = Class.forName("net.minecraft.client.Minecraft", true, gameLoader);
                getMinecraftMethod = minecraftClass.getMethod("getMinecraft");
                fontRendererField = minecraftClass.getField("fontRendererObj");
                try {
                    currentScreenField = minecraftClass.getField("currentScreen");
                } catch (Exception ce) {
                    currentScreenField = minecraftClass.getField("m");
                }

                Class<?> fontRendererClass = Class.forName("net.minecraft.client.gui.FontRenderer", true, gameLoader);
                drawStringWithShadowMethod = fontRendererClass.getMethod("drawStringWithShadow", String.class,
                        float.class, float.class, int.class);
                getStringWidthMethod = fontRendererClass.getMethod("getStringWidth", String.class);
                AgentLogger.log("TimerRenderer loaded MCP mappings.");
            } catch (Exception e) {
                try {
                    minecraftClass = Class.forName("ave", true, gameLoader);
                    getMinecraftMethod = minecraftClass.getMethod("A");
                    fontRendererField = minecraftClass.getField("k");
                    try {
                        currentScreenField = minecraftClass.getField("m");
                    } catch (Exception ce) {
                        currentScreenField = minecraftClass.getField("currentScreen");
                    }

                    Class<?> fontRendererClass = Class.forName("bjn", true, gameLoader);
                    drawStringWithShadowMethod = fontRendererClass.getMethod("a", String.class, float.class,
                            float.class, int.class);
                    try {
                        getStringWidthMethod = fontRendererClass.getMethod("getStringWidth", String.class);
                    } catch (Exception ex) {
                        getStringWidthMethod = fontRendererClass.getMethod("a", String.class);
                    }
                    AgentLogger.log("TimerRenderer loaded Notch mappings.");
                } catch (Exception e2) {
                    AgentLogger.log("TimerRenderer: Failed to resolve Minecraft!");
                    AgentLogger.log(e2);
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
                } catch (Exception e2) {
                }
            }

            initialized = true;
        } catch (Exception e) {
            AgentLogger.log("Failed to initialize TimerRenderer!");
            AgentLogger.log(e);
            initialized = true;
        }
    }

    public static void renderTimerOverlay(float partialTicks) {
        init();
        if (getMinecraftMethod == null || drawStringWithShadowMethod == null || glPushMatrix == null)
            return;

        if (!configLoaded) {
            MCCTimerState.loadConfig();
            configLoaded = true;
        }

        try {
            Object mc = getMinecraftMethod.invoke(null);
            if (mc == null)
                return;

            Object fontRenderer = fontRendererField.get(mc);
            if (fontRenderer == null)
                return;

            // Detect self-disconnect: if timer is running but player is null, reset it
            if (MCCTimerState.running) {
                Object player = null;
                try {
                    try {
                        player = mc.getClass().getField("thePlayer").get(mc);
                    } catch (Exception e) {
                        player = mc.getClass().getField("h").get(mc);
                    }
                } catch (Exception e) {}
                if (player == null) {
                    AgentLogger.log("Player is null while timer running - resetting (disconnect).");
                    MCCTimerState.running = false;
                    MCCTimerState.visible = false;
                    MCCTimerState.startTime = 0;
                    return;
                }
            }

            // Handle edit mode opening (deferred from /timer edit command)
            if (MCCTimerState.pendingEditScreen) {
                MCCTimerState.pendingEditScreen = false;
                try {
                    // Use EditScreenFactory to create and inject a GuiScreen subclass via Unsafe
                    ClassLoader sysCl = ClassLoader.getSystemClassLoader();
                    Class<?> factoryClass = Class.forName("com.mcctimer.agent.EditScreenFactory", true, sysCl);
                    Method createMethod = factoryClass.getMethod("createEditScreen");
                    Object screen = createMethod.invoke(null);
                    if (screen != null) {
                        // displayGuiScreen(screen)
                        ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
                        Class<?> guiScreenClass = null;
                        try {
                            guiScreenClass = Class.forName("net.minecraft.client.gui.GuiScreen", true, gameLoader);
                        } catch (Exception e) {
                            guiScreenClass = Class.forName("avb", true, gameLoader);
                        }
                        Method displayGuiScreen = null;
                        try {
                            displayGuiScreen = mc.getClass().getMethod("displayGuiScreen", guiScreenClass);
                        } catch (Exception e) {
                            displayGuiScreen = mc.getClass().getMethod("a", guiScreenClass);
                        }
                        displayGuiScreen.invoke(mc, screen);
                        wasInEditMode = true;
                        AgentLogger.log("Displayed EditScreen via Unsafe injection.");
                    } else {
                        AgentLogger.log("EditScreen creation returned null!");
                    }
                } catch (Exception e) {
                    AgentLogger.log("Failed to display EditScreen!");
                    AgentLogger.log(e);
                }
            }

            // Detect if the GuiScreen was closed (ESC pressed while in edit mode)
            if (MCCTimerState.editMode && currentScreenField != null) {
                Object currentScreen = currentScreenField.get(mc);
                if (wasInEditMode && currentScreen == null) {
                    // Screen closed by ESC — exit edit mode and save
                    MCCTimerState.editMode = false;
                    MCCTimerState.dragging = false;
                    MCCTimerState.tntDragging = false;
                    MCCTimerState.fireballDragging = false;
                    MCCTimerState.saveConfig();
                    wasInEditMode = false;
                    AgentLogger.log("Edit mode OFF (screen closed). Config saved.");
                } else if (currentScreen != null) {
                    wasInEditMode = true;
                }
            }

            // Get scaled screen dimensions
            int screenW = 0, screenH = 0;
            if (scaledResCtor != null) {
                try {
                    Object sr = scaledResCtor.newInstance(mc);
                    screenW = (int) getScaledWidthMethod.invoke(sr);
                    screenH = (int) getScaledHeightMethod.invoke(sr);
                } catch (Exception e) {
                }
            }

            // Mouse handling in edit mode
            if (MCCTimerState.editMode && screenW > 0 && screenH > 0) {
                handleMouse(screenW, screenH);
            }

            // Render timer
            boolean showTimer = (MCCTimerState.visible && MCCTimerState.running) || MCCTimerState.editMode;
            if (showTimer) {
                String timeStr;
                if (MCCTimerState.running && MCCTimerState.visible) {
                    long elapsed = System.currentTimeMillis() - MCCTimerState.startTime;
                    long totalSeconds = elapsed / 1000;
                    long minutes = totalSeconds / 60;
                    long seconds = totalSeconds % 60;
                    long tenths = (elapsed % 1000) / 100;
                    timeStr = String.format("%02d:%02d.%d", minutes, seconds, tenths);
                } else {
                    timeStr = "00:00.0";
                }

                glPushMatrix.invoke(null);
                glTranslatef.invoke(null, MCCTimerState.timerX, MCCTimerState.timerY, 0f);
                glScalef.invoke(null, MCCTimerState.timerScale, MCCTimerState.timerScale, 1.0f);
                drawStringWithShadowMethod.invoke(fontRenderer, timeStr, 0f, 0f, 0xFFFFFF);
                glPopMatrix.invoke(null);

                // Measure actual text width for accurate hitbox
                if (getStringWidthMethod != null) {
                    try {
                        lastTimerTextWidth = (int) getStringWidthMethod.invoke(fontRenderer, timeStr);
                    } catch (Exception e) {
                    }
                }

                // Edit mode overlay
                if (MCCTimerState.editMode) {
                    float tw = lastTimerTextWidth * MCCTimerState.timerScale;
                    float th = FONT_HEIGHT * MCCTimerState.timerScale;
                    drawEditBorder(MCCTimerState.timerX, MCCTimerState.timerY, tw, th,
                            MCCTimerState.dragging ? 0x00FF00 : 0xFFFF00);

                    drawStringWithShadowMethod.invoke(fontRenderer,
                            "\u00A7b\u00A7lEDIT MODE \u00A77- Press ESC to save",
                            (screenW > 0 ? (screenW / 2f - 60) : 10f), 2f, 0xFFFFFF);
                }
            }
        } catch (Exception e) {
        }
    }

    private static void handleMouse(int screenW, int screenH) {
        if (mouseGetX == null || mouseGetY == null || mouseIsButtonDown == null)
            return;
        try {
            int rawX = (int) mouseGetX.invoke(null);
            int rawY = (int) mouseGetY.invoke(null);

            int displayW = 1, displayH = 1;
            if (displayGetWidth != null) {
                displayW = (int) displayGetWidth.invoke(null);
                displayH = (int) displayGetHeight.invoke(null);
            }

            float mouseX = (float) rawX / displayW * screenW;
            float mouseY = screenH - ((float) rawY / displayH * screenH);

            boolean leftDown = (boolean) mouseIsButtonDown.invoke(null, 0);
            int dWheel = (int) mouseGetDWheel.invoke(null);

            float tw = 60 * MCCTimerState.timerScale;
            float th = 12 * MCCTimerState.timerScale;

            // TNT HUD bounds
            float tntX = TntHUD.getHudX(screenW);
            float tntY = TntHUD.getHudY(screenH);
            float tntW = TntHUD.lastHudWidth;
            float tntH = TntHUD.lastHudHeight;

            // --- TNT HUD drag (check first) ---
            if (leftDown) {
                if (!MCCTimerState.tntDragging && !MCCTimerState.fireballDragging && !MCCTimerState.dragging) {
                    if (mouseX >= tntX && mouseX <= tntX + tntW
                            && mouseY >= tntY && mouseY <= tntY + tntH) {
                        MCCTimerState.tntDragging = true;
                        MCCTimerState.tntDragOffsetX = mouseX - tntX;
                        MCCTimerState.tntDragOffsetY = mouseY - tntY;
                    }
                } else if (MCCTimerState.tntDragging) {
                    MCCTimerState.tntHudX = mouseX - MCCTimerState.tntDragOffsetX;
                    MCCTimerState.tntHudY = mouseY - MCCTimerState.tntDragOffsetY;
                }
            } else {
                MCCTimerState.tntDragging = false;
            }

            // --- Fireball HUD drag ---
            float fbX = FireballHUD.getHudX(screenW);
            float fbY = FireballHUD.getHudY(screenH);
            float fbW = FireballHUD.lastHudWidth;
            float fbH = FireballHUD.lastHudHeight;

            if (leftDown) {
                if (!MCCTimerState.fireballDragging && !MCCTimerState.dragging && !MCCTimerState.tntDragging) {
                    if (mouseX >= fbX && mouseX <= fbX + fbW
                            && mouseY >= fbY && mouseY <= fbY + fbH) {
                        MCCTimerState.fireballDragging = true;
                        MCCTimerState.fireballDragOffsetX = mouseX - fbX;
                        MCCTimerState.fireballDragOffsetY = mouseY - fbY;
                    }
                } else if (MCCTimerState.fireballDragging) {
                    MCCTimerState.fireballHudX = mouseX - MCCTimerState.fireballDragOffsetX;
                    MCCTimerState.fireballHudY = mouseY - MCCTimerState.fireballDragOffsetY;
                }
            } else {
                MCCTimerState.fireballDragging = false;
            }

            // --- Timer drag (only if fireball isn't being dragged) ---
            if (leftDown) {
                if (!MCCTimerState.dragging && !MCCTimerState.fireballDragging && !MCCTimerState.tntDragging) {
                    if (mouseX >= MCCTimerState.timerX && mouseX <= MCCTimerState.timerX + tw
                            && mouseY >= MCCTimerState.timerY && mouseY <= MCCTimerState.timerY + th) {
                        MCCTimerState.dragging = true;
                        MCCTimerState.dragOffsetX = mouseX - MCCTimerState.timerX;
                        MCCTimerState.dragOffsetY = mouseY - MCCTimerState.timerY;
                    }
                } else if (MCCTimerState.dragging) {
                    MCCTimerState.timerX = mouseX - MCCTimerState.dragOffsetX;
                    MCCTimerState.timerY = mouseY - MCCTimerState.dragOffsetY;
                }
            } else {
                MCCTimerState.dragging = false;
            }

            // --- Scroll to resize (fireball first, then timer) ---
            if (dWheel != 0) {
                if (mouseX >= tntX && mouseX <= tntX + tntW
                        && mouseY >= tntY && mouseY <= tntY + tntH) {
                    float scaleDelta = dWheel > 0 ? 0.1f : -0.1f;
                    MCCTimerState.tntHudScale = Math.max(0.5f, Math.min(5.0f, MCCTimerState.tntHudScale + scaleDelta));
                } else if (mouseX >= fbX && mouseX <= fbX + fbW
                        && mouseY >= fbY && mouseY <= fbY + fbH) {
                    float scaleDelta = dWheel > 0 ? 0.1f : -0.1f;
                    MCCTimerState.fireballHudScale = Math.max(0.5f, Math.min(5.0f, MCCTimerState.fireballHudScale + scaleDelta));
                } else if (mouseX >= MCCTimerState.timerX && mouseX <= MCCTimerState.timerX + tw
                        && mouseY >= MCCTimerState.timerY && mouseY <= MCCTimerState.timerY + th) {
                    float scaleDelta = dWheel > 0 ? 0.1f : -0.1f;
                    MCCTimerState.timerScale = Math.max(0.5f, Math.min(5.0f, MCCTimerState.timerScale + scaleDelta));
                }
            }
        } catch (Exception e) {
        }
    }

    private static void drawEditBorder(float x, float y, float w, float h, int color) {
        try {
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;

            int texId = (int) GL_TEXTURE_2D.get(null);
            int linesId = (int) GL_LINES.get(null);

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
            // Reset color to white
            glColor4f.invoke(null, 1.0f, 1.0f, 1.0f, 1.0f);
        } catch (Exception e) {
        }
    }

    /**
     * Called from EditScreen.drawScreen at full framerate.
     * mouseX/mouseY are already pre-scaled by Minecraft's GuiScreen.
     * This eliminates the LWJGL Mouse/Display reflection overhead.
     */
    public static void handleEditInput(int mouseX, int mouseY) {
        if (!MCCTimerState.editMode)
            return;
        try {
            boolean leftDown = false;
            int dWheel = 0;

            if (mouseIsButtonDown != null) {
                leftDown = (boolean) mouseIsButtonDown.invoke(null, 0);
            }
            if (mouseGetDWheel != null) {
                dWheel = (int) mouseGetDWheel.invoke(null);
            }

            float tw = lastTimerTextWidth * MCCTimerState.timerScale;
            float th = FONT_HEIGHT * MCCTimerState.timerScale;

            // Get screen dimensions for fireball HUD position
            int screenWLocal = 0, screenHLocal = 0;
            try {
                Object mcLocal = getMinecraftMethod.invoke(null);
                if (mcLocal != null && scaledResCtor != null) {
                    Object sr = scaledResCtor.newInstance(mcLocal);
                    screenWLocal = (int) getScaledWidthMethod.invoke(sr);
                    screenHLocal = (int) getScaledHeightMethod.invoke(sr);
                }
            } catch (Exception e) {}

            float tntX = TntHUD.getHudX(screenWLocal);
            float tntY = TntHUD.getHudY(screenHLocal);
            float tntW = TntHUD.lastHudWidth;
            float tntH = TntHUD.lastHudHeight;

            float fbX = FireballHUD.getHudX(screenWLocal);
            float fbY = FireballHUD.getHudY(screenHLocal);
            float fbW = FireballHUD.lastHudWidth;
            float fbH = FireballHUD.lastHudHeight;

            // --- TNT HUD drag (check first) ---
            if (leftDown) {
                if (!MCCTimerState.tntDragging && !MCCTimerState.fireballDragging && !MCCTimerState.dragging) {
                    if (mouseX >= tntX && mouseX <= tntX + tntW
                            && mouseY >= tntY && mouseY <= tntY + tntH) {
                        MCCTimerState.tntDragging = true;
                        MCCTimerState.tntDragOffsetX = mouseX - tntX;
                        MCCTimerState.tntDragOffsetY = mouseY - tntY;
                    }
                } else if (MCCTimerState.tntDragging) {
                    MCCTimerState.tntHudX = mouseX - MCCTimerState.tntDragOffsetX;
                    MCCTimerState.tntHudY = mouseY - MCCTimerState.tntDragOffsetY;
                }
            } else {
                MCCTimerState.tntDragging = false;
            }

            // --- Fireball HUD drag ---
            if (leftDown) {
                if (!MCCTimerState.fireballDragging && !MCCTimerState.dragging && !MCCTimerState.tntDragging) {
                    if (mouseX >= fbX && mouseX <= fbX + fbW
                            && mouseY >= fbY && mouseY <= fbY + fbH) {
                        MCCTimerState.fireballDragging = true;
                        MCCTimerState.fireballDragOffsetX = mouseX - fbX;
                        MCCTimerState.fireballDragOffsetY = mouseY - fbY;
                    }
                } else if (MCCTimerState.fireballDragging) {
                    MCCTimerState.fireballHudX = mouseX - MCCTimerState.fireballDragOffsetX;
                    MCCTimerState.fireballHudY = mouseY - MCCTimerState.fireballDragOffsetY;
                }
            } else {
                MCCTimerState.fireballDragging = false;
            }

            // --- Timer drag ---
            if (leftDown) {
                if (!MCCTimerState.dragging && !MCCTimerState.fireballDragging && !MCCTimerState.tntDragging) {
                    if (mouseX >= MCCTimerState.timerX && mouseX <= MCCTimerState.timerX + tw
                            && mouseY >= MCCTimerState.timerY && mouseY <= MCCTimerState.timerY + th) {
                        MCCTimerState.dragging = true;
                        MCCTimerState.dragOffsetX = mouseX - MCCTimerState.timerX;
                        MCCTimerState.dragOffsetY = mouseY - MCCTimerState.timerY;
                    }
                } else if (MCCTimerState.dragging) {
                    MCCTimerState.timerX = mouseX - MCCTimerState.dragOffsetX;
                    MCCTimerState.timerY = mouseY - MCCTimerState.dragOffsetY;
                }
            } else {
                MCCTimerState.dragging = false;
            }

            // --- Scroll ---
            if (dWheel != 0) {
                if (mouseX >= tntX && mouseX <= tntX + tntW
                        && mouseY >= tntY && mouseY <= tntY + tntH) {
                    float scaleDelta = dWheel > 0 ? 0.1f : -0.1f;
                    MCCTimerState.tntHudScale = Math.max(0.5f, Math.min(5.0f, MCCTimerState.tntHudScale + scaleDelta));
                } else if (mouseX >= fbX && mouseX <= fbX + fbW
                        && mouseY >= fbY && mouseY <= fbY + fbH) {
                    float scaleDelta = dWheel > 0 ? 0.1f : -0.1f;
                    MCCTimerState.fireballHudScale = Math.max(0.5f, Math.min(5.0f, MCCTimerState.fireballHudScale + scaleDelta));
                } else if (mouseX >= MCCTimerState.timerX && mouseX <= MCCTimerState.timerX + tw
                        && mouseY >= MCCTimerState.timerY && mouseY <= MCCTimerState.timerY + th) {
                    float scaleDelta = dWheel > 0 ? 0.1f : -0.1f;
                    MCCTimerState.timerScale = Math.max(0.5f, Math.min(5.0f, MCCTimerState.timerScale + scaleDelta));
                }
            }
        } catch (Exception e) {
        }
    }
}
