package com.mcctimer.agent;

import java.lang.reflect.Method;

public class ChatInterceptor {

    private static Method getFormattedTextMethod;
    private static boolean initialized = false;

    private static void init(Object chatComponent) {
        if (initialized)
            return;
        try {
            try {
                getFormattedTextMethod = chatComponent.getClass().getMethod("getFormattedText");
                AgentLogger.log("ChatInterceptor loaded MCP mappings.");
            } catch (Exception e) {
                try {
                    getFormattedTextMethod = chatComponent.getClass().getMethod("d");
                    AgentLogger.log("ChatInterceptor loaded Notch mappings.");
                } catch (Exception e2) {
                    for (Method m : chatComponent.getClass().getMethods()) {
                        if (m.getParameterCount() == 0 && m.getReturnType() == String.class
                                && !m.getName().equals("toString")) {
                            getFormattedTextMethod = m;
                            AgentLogger.log("ChatInterceptor found text method by probing: " + m.getName());
                            break;
                        }
                    }
                }
            }
            initialized = true;
        } catch (Exception e) {
            AgentLogger.log("Failed to initialize ChatInterceptor!");
            AgentLogger.log(e);
            initialized = true;
        }
    }

    public static void onChatMessage(Object chatComponent) {
        if (chatComponent == null)
            return;
        init(chatComponent);
        if (getFormattedTextMethod == null)
            return;

        try {
            String formatted = (String) getFormattedTextMethod.invoke(chatComponent);
            if (formatted == null)
                return;

            // Anti-spoofing: Ignore any message that is a player talking (DMs, Party, Public Chat)
            // "(From ", "(To ", and ": " are the standard MCC chat separators
            String strippedForFilter = formatted.replaceAll("(?i)\\u00A7[0-9A-FK-OR]", "");
            if (strippedForFilter.startsWith("(From ") || 
                strippedForFilter.startsWith("(To ") || 
                strippedForFilter.contains(": ")) {
                return; // It's a player message, ignore it completely
            }

            // Start timer: Match started!
            if (formatted.contains("\u00A7aMatch started!")) {
                MCCTimerState.running = true;
                MCCTimerState.visible = true;
                MCCTimerState.startTime = System.currentTimeMillis();
                return;
            }

            // Stop timer: Match Results (Click to view)
            // Server sends: \u00A7r\u00A7e\u00A7lMatch Results\u00A7r\u00A77 (Click to view)\u00A7r
            if (formatted.contains("\u00A7r\u00A7e\u00A7lMatch Results\u00A7r\u00A77 (Click to view)")) {
                MCCTimerState.running = false;
                MCCTimerState.visible = false;
                MCCTimerState.startTime = 0;
                return;
            }

            // Stop timer: forfeited (Server sends: \u00A7r\u00A7e forfeited.\u00A7r)
            if (formatted.contains("\u00A7e forfeited.")) {
                AgentLogger.log("Forfeit detected: " + formatted);
                MCCTimerState.running = false;
                MCCTimerState.visible = false;
                MCCTimerState.startTime = 0;
                return;
            }

            // Stop timer: opponent disconnected (Server sends similar \u00A7e formatting)
            if (formatted.contains("\u00A7e disconnected.")) {
                AgentLogger.log("Disconnect detected: " + formatted);
                MCCTimerState.running = false;
                MCCTimerState.visible = false;
                MCCTimerState.startTime = 0;
                return;
            }

            // Stop timer: FINAL KILL
            if (formatted.contains("\u00A7b\u00A7lFINAL KILL")) {
                MCCTimerState.running = false;
                MCCTimerState.visible = false;
                MCCTimerState.startTime = 0;
                return;
            }
        } catch (Exception e) {
        }
    }

    public static boolean onPacketSend(Object packet) {
        if (packet == null) return false;
        
        String className = packet.getClass().getSimpleName();
        // C14PacketTabComplete in Notch is "iq"
        if (className.equals("C14PacketTabComplete") || className.equals("iq")) {
            String message = getPacketMessage(packet);
            if (message != null) {
                java.util.List<String> completions = CommandHandler.getTabCompletions(message);
                if (completions != null && !completions.isEmpty()) {
                    handleLocalTabComplete(completions);
                    return true; // Cancel sending
                }
            }
        }
        return false;
    }

    private static String getPacketMessage(Object packet) {
        try {
            java.lang.reflect.Field f = packet.getClass().getDeclaredField("message");
            f.setAccessible(true);
            return (String) f.get(packet);
        } catch (Exception e) {}
        try {
            java.lang.reflect.Field f = packet.getClass().getDeclaredField("a");
            f.setAccessible(true);
            return (String) f.get(packet);
        } catch (Exception e) {}
        return null;
    }

    private static void handleLocalTabComplete(java.util.List<String> matches) {
        new Thread(() -> {
            try {
                
                String[] completions = matches.toArray(new String[0]);
                
                // Push completions to GuiChat
                ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
                if (gameLoader == null) return;
                
                Object mc = CommandHandler.getMinecraftInstance(gameLoader);
                if (mc == null) return;
                
                Object currentScreen = null;
                try {
                    java.lang.reflect.Field f = mc.getClass().getDeclaredField("currentScreen");
                    f.setAccessible(true);
                    currentScreen = f.get(mc);
                } catch (Exception e) {
                    try {
                        java.lang.reflect.Field f = mc.getClass().getDeclaredField("m");
                        f.setAccessible(true);
                        currentScreen = f.get(mc);
                    } catch (Exception e2) {}
                }
                
                if (currentScreen == null) return;

                try {
                    java.lang.reflect.Method m = currentScreen.getClass().getMethod("onAutocompleteResponse", String[].class);
                    m.invoke(currentScreen, new Object[]{ completions });
                } catch (Exception e) {
                    try {
                        java.lang.reflect.Method m = currentScreen.getClass().getMethod("a", String[].class);
                        m.invoke(currentScreen, new Object[]{ completions });
                    } catch (Exception e2) {}
                }
            } catch (Exception e) {
                AgentLogger.log(e);
            }
        }).start();
    }
}
