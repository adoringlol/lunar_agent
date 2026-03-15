package com.mcctimer.agent;

import java.lang.reflect.Method;

public class ChatCopyHooks {
    public static String originalOwner = null;
    public static String originalName = null;
    public static String originalDesc = null;
    
    // Cache the resolved method so we don't look it up every copy
    private static Method originalMethod = null;

    /**
     * Called by injected bytecode from Lunar Client.
     * Takes the net.kyori.adventure.text.Component object on the stack and returns a String.
     */
    public static String getTextOrLegacy(Object componentObj) {
        try {
            ClassLoader scl = componentObj != null ? componentObj.getClass().getClassLoader() : ClassLoader.getSystemClassLoader();
            
            // Check if Left Control is held (dynamically to avoid NoClassDefFoundError on Agent load)
            Class<?> keyboardClass = Class.forName("org.lwjgl.input.Keyboard", true, scl);
            Method isKeyDownMethod = keyboardClass.getDeclaredMethod("isKeyDown", int.class);
            boolean isLControlDown = (Boolean) isKeyDownMethod.invoke(null, 29); // KEY_LCONTROL = 29
            
            if (isLControlDown) {
                // Return formatted legacy string with '&'
                Class<?> apolloClass = Class.forName("com.lunarclient.apollo.common.ApolloComponent", true, scl);
                Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component", true, scl);
                Method toLegacy = apolloClass.getDeclaredMethod("toLegacy", componentClass);
                
                String legacy = (String) toLegacy.invoke(null, componentObj);
                return legacy.replace('\u00A7', '&');
                
            } else {
                // Call original getTextContent (returns stripped text)
                if (originalMethod == null) {
                    if (originalOwner != null && originalName != null) {
                        String javaName = originalOwner.replace('/', '.');
                        Class<?> utilClass = Class.forName(javaName, true, scl);
                        Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component", true, scl);
                        originalMethod = utilClass.getDeclaredMethod(originalName, componentClass);
                        originalMethod.setAccessible(true);
                    } else {
                        return "Error: Original method metadata not set by agent!";
                    }
                }
                
                return (String) originalMethod.invoke(null, componentObj);
            }
        } catch (Exception e) {
            AgentLogger.log("Error in ChatCopyHooks: " + e.getMessage());
            e.printStackTrace();
            return "Error copying chat";
        }
    }
}
