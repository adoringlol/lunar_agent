package com.mcctimer.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.List;

public class TrajectoryRenderer {
    
    // Cache methods and fields for performance
    private static Class<?> entityClass;
    private static Method getMinecraftMethod;
    private static Field theWorldField;
    private static Field loadedEntityListField;
    private static Class<?> vec3Class;
    private static Class<?> worldClass;
    private static Method rayTraceBlocksMethod;
    private static Class<?> mopClass;
    private static Field hitVecField;
    private static Class<?> renderManagerClass;
    private static Field renderPosXField;
    private static Field renderPosYField;
    private static Field renderPosZField;

    // Reflection GL11
    private static Method glPushMatrix, glPopMatrix;
    private static Method glEnable, glDisable;
    private static Method glBlendFunc, glLineWidth, glColor4f;
    private static Method glBegin, glEnd, glVertex3d;

    private static Class<?> blockPosClass;
    private static Method getBlockPosMethod;
    private static Method getXMethod;
    private static Method getYMethod;
    private static Method getZMethod;

    // Block checking
    private static Method getBlockStateMethod;   // World.getBlockState(BlockPos)
    private static Method getBlockMethod;         // IBlockState.getBlock()
    private static Object airBlock;               // Blocks.air / id 0
    private static Object endStoneBlock;          // Blocks.end_stone / id 121
    private static java.lang.reflect.Constructor<?> blockPosConstructor; // BlockPos(int,int,int)

    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_DEPTH_TEST = 2929;
    private static final int GL_BLEND = 3042;
    private static final int GL_SRC_ALPHA = 770;
    private static final int GL_ONE_MINUS_SRC_ALPHA = 771;
    private static final int GL_LINES = 1;
    private static final int GL_QUADS = 7;

    public static double dynamicBlastRadius = 1.2;
    private static Field explosionSizeField = null;

    private static boolean initialized = false;
    private static boolean loggedError = false;
    private static boolean firstRun = false;

    private static void init(ClassLoader gameLoader) {
        if (initialized) return;
        try {
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11", true, gameLoader);
            glPushMatrix = gl11.getMethod("glPushMatrix");
            glPopMatrix = gl11.getMethod("glPopMatrix");
            glEnable = gl11.getMethod("glEnable", int.class);
            glDisable = gl11.getMethod("glDisable", int.class);
            glBlendFunc = gl11.getMethod("glBlendFunc", int.class, int.class);
            glLineWidth = gl11.getMethod("glLineWidth", float.class);
            glColor4f = gl11.getMethod("glColor4f", float.class, float.class, float.class, float.class);
            glBegin = gl11.getMethod("glBegin", int.class);
            glEnd = gl11.getMethod("glEnd");
            glVertex3d = gl11.getMethod("glVertex3d", double.class, double.class, double.class);
        } catch (Throwable e) {
            AgentLogger.log("Failed to initialize GL11: " + e.getMessage());
        }

        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, gameLoader);
            getMinecraftMethod = mcClass.getMethod("getMinecraft");
            theWorldField = mcClass.getDeclaredField("theWorld");
            
            worldClass = Class.forName("net.minecraft.world.World", true, gameLoader);
            loadedEntityListField = worldClass.getDeclaredField("loadedEntityList");
            
            entityClass = Class.forName("net.minecraft.entity.Entity", true, gameLoader);
            vec3Class = Class.forName("net.minecraft.util.Vec3", true, gameLoader);
            mopClass = Class.forName("net.minecraft.util.MovingObjectPosition", true, gameLoader);
            hitVecField = mopClass.getDeclaredField("hitVec");
            
            for (Method m : worldClass.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 5 && p[0] == vec3Class && p[1] == vec3Class && p[2] == boolean.class && p[3] == boolean.class && p[4] == boolean.class) {
                    rayTraceBlocksMethod = m;
                    break;
                }
            }
            
            renderManagerClass = Class.forName("net.minecraft.client.renderer.entity.RenderManager", true, gameLoader);
            renderPosXField = renderManagerClass.getDeclaredField("renderPosX");
            renderPosYField = renderManagerClass.getDeclaredField("renderPosY");
            renderPosZField = renderManagerClass.getDeclaredField("renderPosZ");

            blockPosClass = Class.forName("net.minecraft.util.BlockPos", true, gameLoader);
            getBlockPosMethod = mopClass.getMethod("getBlockPos");
            getXMethod = blockPosClass.getMethod("getX");
            getYMethod = blockPosClass.getMethod("getY");
            getZMethod = blockPosClass.getMethod("getZ");
            blockPosConstructor = blockPosClass.getConstructor(int.class, int.class, int.class);

            // Block checking - MCP
            getBlockStateMethod = worldClass.getMethod("getBlockState", blockPosClass);
            Class<?> iBlockState = Class.forName("net.minecraft.block.state.IBlockState", true, gameLoader);
            getBlockMethod = iBlockState.getMethod("getBlock");
            Class<?> blocksClass = Class.forName("net.minecraft.init.Blocks", true, gameLoader);
            airBlock = blocksClass.getField("air").get(null);
            endStoneBlock = blocksClass.getField("end_stone").get(null);

        } catch (Exception e) {
            try {
                // Notch mappings
                Class<?> mcClass = Class.forName("ave", true, gameLoader);
                getMinecraftMethod = mcClass.getMethod("A");
                theWorldField = mcClass.getDeclaredField("f");
                
                worldClass = Class.forName("adm", true, gameLoader);
                loadedEntityListField = worldClass.getDeclaredField("f");
                
                entityClass = Class.forName("pk", true, gameLoader);
                vec3Class = Class.forName("aui", true, gameLoader);
                mopClass = Class.forName("auh", true, gameLoader);
                hitVecField = mopClass.getDeclaredField("c");
                
                for (Method m : worldClass.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 5 && p[0] == vec3Class && p[1] == vec3Class && p[2] == boolean.class && p[3] == boolean.class && p[4] == boolean.class) {
                        rayTraceBlocksMethod = m;
                        break;
                    }
                }
                
                renderManagerClass = Class.forName("biu", true, gameLoader);
                renderPosXField = renderManagerClass.getDeclaredField("o");
                renderPosYField = renderManagerClass.getDeclaredField("p");
                renderPosZField = renderManagerClass.getDeclaredField("q");

                blockPosClass = Class.forName("cj", true, gameLoader);
                getBlockPosMethod = mopClass.getMethod("a");
                getXMethod = blockPosClass.getMethod("n");
                getYMethod = blockPosClass.getMethod("o");
                getZMethod = blockPosClass.getMethod("p");
                blockPosConstructor = blockPosClass.getConstructor(int.class, int.class, int.class);

                // Block checking - Notch
                // Find World.getBlockState(BlockPos) -> IBlockState
                for (Method m : worldClass.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == blockPosClass && !m.getReturnType().isPrimitive() 
                        && !m.getReturnType().equals(Object.class) && !m.getReturnType().equals(String.class)) {
                        getBlockStateMethod = m;
                        break;
                    }
                }
                if (getBlockStateMethod != null) {
                    Class<?> iBlockState = getBlockStateMethod.getReturnType();
                    for (Method m : iBlockState.getDeclaredMethods()) {
                        if (m.getParameterTypes().length == 0 && !m.getReturnType().equals(Object.class) 
                            && !m.getReturnType().isPrimitive() && !m.getReturnType().equals(String.class)) {
                            getBlockMethod = m;
                            break;
                        }
                    }
                }
                
                // Get air and end_stone by block ID
                Class<?> blockClass = Class.forName("aig", true, gameLoader);
                Method getBlockById = null;
                for (Method m : blockClass.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == int.class 
                        && m.getReturnType() == blockClass) {
                        getBlockById = m;
                        break;
                    }
                }
                if (getBlockById != null) {
                    airBlock = getBlockById.invoke(null, 0);       // air = 0
                    endStoneBlock = getBlockById.invoke(null, 121); // end_stone = 121
                }

            } catch (Throwable e2) {
                AgentLogger.log("Failed to initialize mappings for TrajectoryRenderer: " + e2.getMessage());
            }
        }
        initialized = true;

        // Detect server and load saved blast radius
        MCCTimerState.currentServerIP = MCCTimerState.detectServerIP();
        dynamicBlastRadius = MCCTimerState.getEffectiveBlastRadius();
        if (!MCCTimerState.currentServerIP.isEmpty()) {
            AgentLogger.log("Server: " + MCCTimerState.currentServerIP + " | Blast radius: " + dynamicBlastRadius);
        }
    }

    public static void renderTrajectories(float partialTicks) {
        try {
            if (!MCCTimerState.fireballRaycastEnabled) return;

            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            init(gameLoader);

            if (MCCTimerState.currentServerIP.isEmpty() && getMinecraftMethod != null) {
                MCCTimerState.currentServerIP = MCCTimerState.detectServerIP();
                dynamicBlastRadius = MCCTimerState.getEffectiveBlastRadius();
                if (!MCCTimerState.currentServerIP.isEmpty()) {
                    AgentLogger.log("Joined Server: " + MCCTimerState.currentServerIP + " | Blast radius: " + dynamicBlastRadius);
                }
            }

            if (getMinecraftMethod == null) return;

            Object mc = getMinecraftMethod.invoke(null);
            if (mc == null) return;

            Object theWorld = theWorldField.get(mc);
            if (theWorld == null) return;

            List<?> loadedEntities = (List<?>) loadedEntityListField.get(theWorld);
            if (loadedEntities == null) return;

            // Prepare RenderManager offsets
            Object renderManager = mc.getClass().getMethod(renderManagerClass.getSimpleName().equals("RenderManager") ? "getRenderManager" : "af").invoke(mc);
            double rX = renderPosXField.getDouble(renderManager);
            double rY = renderPosYField.getDouble(renderManager);
            double rZ = renderPosZField.getDouble(renderManager);

            if (glPushMatrix != null) glPushMatrix.invoke(null);
            if (glDisable != null) {
                glDisable.invoke(null, 2896); // GL_LIGHTING
                glDisable.invoke(null, GL_TEXTURE_2D);
                if (MCCTimerState.fireballThroughWalls) {
                    glDisable.invoke(null, GL_DEPTH_TEST);
                }
            }
            if (glEnable != null) glEnable.invoke(null, GL_BLEND);
            if (glBlendFunc != null) glBlendFunc.invoke(null, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            if (glLineWidth != null) glLineWidth.invoke(null, 2.5f);
            
            try {
                for (Object entity : loadedEntities) {
                    if (entity == null) continue;
                    String cName = entity.getClass().getSimpleName();
                    
                    if (cName.equals("EntityLargeFireball") || cName.equals("we") || 
                        cName.equals("EntitySmallFireball") || cName.equals("wd") || 
                        cName.equals("EntityFireball") || cName.equals("wq")) {
                        if (!firstRun) {
                            AgentLogger.log("Found fireball entity: " + cName);
                            firstRun = true;
                        }
                        drawFireballRayDir(entity, theWorld, rX, rY, rZ, gameLoader);
                    }
                }
            } finally {
                if (glEnable != null) {
                    if (MCCTimerState.fireballThroughWalls) {
                        glEnable.invoke(null, GL_DEPTH_TEST);
                    }
                    glEnable.invoke(null, GL_TEXTURE_2D);
                    glEnable.invoke(null, 2896); // GL_LIGHTING
                }
                if (glDisable != null) glDisable.invoke(null, GL_BLEND);
                if (glColor4f != null) glColor4f.invoke(null, 1.0f, 1.0f, 1.0f, 1.0f);
                if (glPopMatrix != null) glPopMatrix.invoke(null);
            }

        } catch (Throwable t) {
            if (!loggedError) {
                AgentLogger.log("TrajectoryRenderer Error (Logged once):");
                AgentLogger.log(t);
                loggedError = true;
            }
        }
    }

    public static void recordExplosion(Object explosion) {
        try {
            if (!MCCTimerState.fireballRaycastEnabled) return;

            // Don't auto-calibrate if user has manually set a blast radius
            if (MCCTimerState.fireballBlastRadius > 0) return;

            if (explosionSizeField == null) {
                boolean isNotch = explosion.getClass().getSimpleName().equals("aqo");
                explosionSizeField = explosion.getClass().getDeclaredField(isNotch ? "c" : "explosionSize");
                explosionSizeField.setAccessible(true);
            }
            float size = (float) explosionSizeField.get(explosion);
            if (size > 0.0f && size < 10.0f) {
                float radius = size * 1.2f;
                
                // Only save and notify if we don't already have it, or it changed
                String ip = MCCTimerState.currentServerIP;
                if (!MCCTimerState.serverBlastRadii.containsKey(ip) || Math.abs(dynamicBlastRadius - radius) > 0.05f) {
                    dynamicBlastRadius = radius;
                    MCCTimerState.recordServerBlastRadius(radius);
                    
                    String displayIp = ip.isEmpty() ? "this server" : ip;
                    CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fAuto-calibrated blast radius to \u00A7e" + String.format("%.1f", radius) + "\u00A7f blocks for \u00A7e" + displayIp);
                }
            }
        } catch (Throwable t) {
            // Ignored, fallback will be used
        }
    }

    private static void drawFireballRayDir(Object entity, Object world, double rX, double rY, double rZ, ClassLoader loader) throws Exception {
        boolean isNotch = entityClass.getSimpleName().equals("pk");
        Field posXF = entityClass.getDeclaredField(isNotch ? "s" : "posX");
        Field posYF = entityClass.getDeclaredField(isNotch ? "t" : "posY");
        Field posZF = entityClass.getDeclaredField(isNotch ? "u" : "posZ");
        
        Field motXF = entityClass.getDeclaredField(isNotch ? "v" : "motionX");
        Field motYF = entityClass.getDeclaredField(isNotch ? "w" : "motionY");
        Field motZF = entityClass.getDeclaredField(isNotch ? "x" : "motionZ");

        double posX = posXF.getDouble(entity);
        double posY = posYF.getDouble(entity);
        double posZ = posZF.getDouble(entity);

        double motX = motXF.getDouble(entity);
        double motY = motYF.getDouble(entity);
        double motZ = motZF.getDouble(entity);

        double maxM = 300.0;
        double speed = Math.sqrt(motX*motX + motY*motY + motZ*motZ);
        if (speed < 0.001) return;
        
        double dirX = motX / speed * maxM;
        double dirY = motY / speed * maxM;
        double dirZ = motZ / speed * maxM;

        double endX = posX + dirX;
        double endY = posY + dirY;
        double endZ = posZ + dirZ;

        Object startVec = vec3Class.getConstructor(double.class, double.class, double.class).newInstance(posX, posY, posZ);
        Object targetVec = vec3Class.getConstructor(double.class, double.class, double.class).newInstance(endX, endY, endZ);

        Object mop = rayTraceBlocksMethod.invoke(world, startVec, targetVec, false, true, false);
        
        boolean hitBlock = false;
        double lineEndX = endX;
        double lineEndY = endY;
        double lineEndZ = endZ;

        if (mop != null) {
            Object hitVec = hitVecField.get(mop);
            if (hitVec != null) {
                Field cX = vec3Class.getDeclaredField(isNotch ? "a" : "xCoord");
                Field cY = vec3Class.getDeclaredField(isNotch ? "b" : "yCoord");
                Field cZ = vec3Class.getDeclaredField(isNotch ? "c" : "zCoord");
                
                lineEndX = cX.getDouble(hitVec);
                lineEndY = cY.getDouble(hitVec);
                lineEndZ = cZ.getDouble(hitVec);
                
                double dx = lineEndX - posX;
                double dy = lineEndY - posY;
                double dz = lineEndZ - posZ;
                double hitDist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                
                if (hitDist < maxM - 0.1) {
                    hitBlock = true;
                }
            }
        }

        // Render the trajectory line
        if (glColor4f != null) glColor4f.invoke(null, 1.0f, 0.4f, 0.0f, 1.0f);
        if (glBegin != null) {
            glBegin.invoke(null, GL_LINES);
            glVertex3d.invoke(null, posX - rX, posY - rY, posZ - rZ);
            glVertex3d.invoke(null, lineEndX - rX, lineEndY - rY, lineEndZ - rZ);
            glEnd.invoke(null);
        }

        // Render individual destructible blocks in explosion radius
        if (hitBlock && getBlockPosMethod != null) {
            Object blockPosObj = getBlockPosMethod.invoke(mop);
            if (blockPosObj != null) {
                int bx = (int) getXMethod.invoke(blockPosObj);
                int by = (int) getYMethod.invoke(blockPosObj);
                int bz = (int) getZMethod.invoke(blockPosObj);

                // Remove the forced glDisable(GL_DEPTH_TEST)

                // 1. Draw the impact block (solid red)
                double expand = 0.003;
                double minX = bx - rX - expand;
                double minY = by - rY - expand;
                double minZ = bz - rZ - expand;
                double maxX = bx - rX + 1.0 + expand;
                double maxY = by - rY + 1.0 + expand;
                double maxZ = bz - rZ + 1.0 + expand;

                if (glColor4f != null) glColor4f.invoke(null, 1.0f, 0.1f, 0.3f, 0.5f);
                drawSolidBoxFaces(minX, minY, minZ, maxX, maxY, maxZ);
                if (glColor4f != null) glColor4f.invoke(null, 1.0f, 0.2f, 0.4f, 1.0f);
                if (glLineWidth != null) glLineWidth.invoke(null, 3.0f);
                drawOutlinedBoxEdges(minX, minY, minZ, maxX, maxY, maxZ);

                // 2. Check each block in blast radius individually
                int radius = (int) Math.ceil(dynamicBlastRadius);
                if (getBlockStateMethod != null && getBlockMethod != null && blockPosConstructor != null) {
                    if (glLineWidth != null) glLineWidth.invoke(null, 1.5f);
                    
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dy = -radius; dy <= radius; dy++) {
                            for (int dz = -radius; dz <= radius; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) continue;
                                
                                // Spherical radius check
                                double distSq = dx*dx + dy*dy + dz*dz;
                                if (distSq > dynamicBlastRadius * dynamicBlastRadius) continue;

                                int checkX = bx + dx;
                                int checkY = by + dy;
                                int checkZ = bz + dz;

                                try {
                                    Object checkPos = blockPosConstructor.newInstance(checkX, checkY, checkZ);
                                    Object blockState = getBlockStateMethod.invoke(world, checkPos);
                                    if (blockState == null) continue;
                                    Object block = getBlockMethod.invoke(blockState);
                                    if (block == null) continue;

                                    // Skip air and endstone — they won't be destroyed
                                    if (block == airBlock) continue;
                                    if (block == endStoneBlock) continue;

                                    // This block WILL be destroyed — highlight it
                                    double blkMinX = checkX - rX - expand;
                                    double blkMinY = checkY - rY - expand;
                                    double blkMinZ = checkZ - rZ - expand;
                                    double blkMaxX = checkX - rX + 1.0 + expand;
                                    double blkMaxY = checkY - rY + 1.0 + expand;
                                    double blkMaxZ = checkZ - rZ + 1.0 + expand;

                                    if (glColor4f != null) glColor4f.invoke(null, 1.0f, 0.6f, 0.0f, 0.18f);
                                    drawSolidBoxFaces(blkMinX, blkMinY, blkMinZ, blkMaxX, blkMaxY, blkMaxZ);

                                    if (glColor4f != null) glColor4f.invoke(null, 1.0f, 0.6f, 0.0f, 0.7f);
                                    drawOutlinedBoxEdges(blkMinX, blkMinY, blkMinZ, blkMaxX, blkMaxY, blkMaxZ);
                                } catch (Exception blockEx) {
                                    // Skip block silently
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void drawSolidBoxFaces(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) throws Exception {
        if (glBegin == null) return;
        glBegin.invoke(null, GL_QUADS);
        glVertex3d.invoke(null, minX, minY, minZ); glVertex3d.invoke(null, maxX, minY, minZ);
        glVertex3d.invoke(null, maxX, minY, maxZ); glVertex3d.invoke(null, minX, minY, maxZ);
        glVertex3d.invoke(null, minX, maxY, minZ); glVertex3d.invoke(null, minX, maxY, maxZ);
        glVertex3d.invoke(null, maxX, maxY, maxZ); glVertex3d.invoke(null, maxX, maxY, minZ);
        glVertex3d.invoke(null, minX, minY, minZ); glVertex3d.invoke(null, minX, maxY, minZ);
        glVertex3d.invoke(null, maxX, maxY, minZ); glVertex3d.invoke(null, maxX, minY, minZ);
        glVertex3d.invoke(null, minX, minY, maxZ); glVertex3d.invoke(null, maxX, minY, maxZ);
        glVertex3d.invoke(null, maxX, maxY, maxZ); glVertex3d.invoke(null, minX, maxY, maxZ);
        glVertex3d.invoke(null, minX, minY, minZ); glVertex3d.invoke(null, minX, minY, maxZ);
        glVertex3d.invoke(null, minX, maxY, maxZ); glVertex3d.invoke(null, minX, maxY, minZ);
        glVertex3d.invoke(null, maxX, minY, minZ); glVertex3d.invoke(null, maxX, maxY, minZ);
        glVertex3d.invoke(null, maxX, maxY, maxZ); glVertex3d.invoke(null, maxX, minY, maxZ);
        glEnd.invoke(null);
    }

    private static void drawOutlinedBoxEdges(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) throws Exception {
        if (glBegin == null) return;
        glBegin.invoke(null, GL_LINES);
        glVertex3d.invoke(null, minX, minY, minZ); glVertex3d.invoke(null, maxX, minY, minZ);
        glVertex3d.invoke(null, maxX, minY, minZ); glVertex3d.invoke(null, maxX, minY, maxZ);
        glVertex3d.invoke(null, maxX, minY, maxZ); glVertex3d.invoke(null, minX, minY, maxZ);
        glVertex3d.invoke(null, minX, minY, maxZ); glVertex3d.invoke(null, minX, minY, minZ);
        glVertex3d.invoke(null, minX, maxY, minZ); glVertex3d.invoke(null, maxX, maxY, minZ);
        glVertex3d.invoke(null, maxX, maxY, minZ); glVertex3d.invoke(null, maxX, maxY, maxZ);
        glVertex3d.invoke(null, maxX, maxY, maxZ); glVertex3d.invoke(null, minX, maxY, maxZ);
        glVertex3d.invoke(null, minX, maxY, maxZ); glVertex3d.invoke(null, minX, maxY, minZ);
        glVertex3d.invoke(null, minX, minY, minZ); glVertex3d.invoke(null, minX, maxY, minZ);
        glVertex3d.invoke(null, maxX, minY, minZ); glVertex3d.invoke(null, maxX, maxY, minZ);
        glVertex3d.invoke(null, maxX, minY, maxZ); glVertex3d.invoke(null, maxX, maxY, maxZ);
        glVertex3d.invoke(null, minX, minY, maxZ); glVertex3d.invoke(null, minX, maxY, maxZ);
        glEnd.invoke(null);
    }
}
