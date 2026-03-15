package com.mcctimer.agent;

import java.lang.reflect.Method;

public class CommandHandler {

    private static Method addChatMethod;
    private static boolean chatMethodResolved = false;

    private static final java.util.Map<String, Command> commands = new java.util.HashMap<>();

    static {
        commands.put("friendnotif", new FriendNotifCommand());
        commands.put("testnotif", new TestNotifCommand());
        
        LunarMsgCommand lunarMsg = new LunarMsgCommand();
        commands.put("lunarmsg", lunarMsg);
        commands.put("lmsg", lunarMsg);
        commands.put("tell", lunarMsg);
        
        LunarStatusCommand lunarStatus = new LunarStatusCommand();
        commands.put("lunarstatus", lunarStatus);
        commands.put("lcstatus", lunarStatus);
        commands.put("lstatus", lunarStatus);
        
        commands.put("gui", new TimerCommand());

        PlayEmoteCommand playEmote = new PlayEmoteCommand();
        commands.put("playemote", playEmote);
        commands.put("emote", playEmote);

        GiveAllCosmeticsCommand giveAllCosmeticsCommand = new GiveAllCosmeticsCommand();
        commands.put("giveallcosmetic", giveAllCosmeticsCommand);
        commands.put("gac", giveAllCosmeticsCommand);

        commands.put("blockhit", new BlockHitCommand());
    }

    /**
     * Handles /gui and other commands. Returns true if handled (don't send to server).
     */
    public static boolean handleCommand(Object message) {
        if (message == null)
            return false;
        String msg = message.toString().trim();

        if (msg.isEmpty() || !msg.startsWith("/"))
            return false;

        String[] parts = msg.split("\\s+");
        String label = parts[0].substring(1).toLowerCase();

        Command cmd = commands.get(label);
        if (cmd != null) {
            String[] args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
            return cmd.execute(parts[0], args, msg);
        }

        return false;
    }

    /**
     * Handles tab completion for custom commands.
     * @param message The raw chat message typed so far.
     * @return A list of completions, or an empty list if not handled.
     */
    public static java.util.List<String> getTabCompletions(String message) {
        if (message == null)
            return java.util.Collections.emptyList();

        if (message.isEmpty() || !message.startsWith("/"))
            return java.util.Collections.emptyList();

        String[] parts = message.split(" ", -1);
        String label = parts[0].substring(1).toLowerCase();

        Command cmd = commands.get(label);
        if (cmd != null) {
            String[] args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
            return cmd.onTabComplete(label, args, message);
        }

        return java.util.Collections.emptyList();
    }

    /**
     * Display a blank GuiScreen to block all game input during edit mode.
     * Minecraft natively ungrabs mouse, shows cursor, and blocks
     * movement/interaction.
     */
    private static void displayBlankScreen() {
        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null)
                return;

            // Get Minecraft instance
            Object mc = getMinecraftInstance(gameLoader);
            if (mc == null)
                return;

            // Create a blank GuiScreen instance
            Class<?> guiScreenClass = Class.forName("net.minecraft.client.gui.GuiScreen", true, gameLoader);
            Object screen = guiScreenClass.getDeclaredConstructor().newInstance();

            // Minecraft.displayGuiScreen(screen)
            Method displayGuiScreen = null;
            try {
                displayGuiScreen = mc.getClass().getMethod("displayGuiScreen", guiScreenClass);
            } catch (Exception e) {
                // Try Notch name
                displayGuiScreen = mc.getClass().getMethod("a", guiScreenClass);
            }
            displayGuiScreen.invoke(mc, screen);
            AgentLogger.log("Displayed blank GuiScreen for edit mode.");
        } catch (Exception e) {
            AgentLogger.log("Failed to display blank screen for edit mode!");
            AgentLogger.log(e);
        }
    }

    /**
     * Closes the GuiScreen and exits edit mode, saving config.
     */
    public static void exitEditMode() {
        MCCTimerState.editMode = false;
        MCCTimerState.dragging = false;
        MCCTimerState.saveConfig();
        closeScreen();
    }

    private static void closeScreen() {
        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null)
                return;

            Object mc = getMinecraftInstance(gameLoader);
            if (mc == null)
                return;

            Class<?> guiScreenClass = Class.forName("net.minecraft.client.gui.GuiScreen", true, gameLoader);
            Method displayGuiScreen = null;
            try {
                displayGuiScreen = mc.getClass().getMethod("displayGuiScreen", guiScreenClass);
            } catch (Exception e) {
                displayGuiScreen = mc.getClass().getMethod("a", guiScreenClass);
            }
            displayGuiScreen.invoke(mc, (Object) null);
            AgentLogger.log("Closed GuiScreen, exiting edit mode.");
        } catch (Exception e) {
            AgentLogger.log("Failed to close screen!");
            AgentLogger.log(e);
        }
    }

    public static Object getMinecraftInstance(ClassLoader gameLoader) throws Exception {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, gameLoader);
            return mcClass.getMethod("getMinecraft").invoke(null);
        } catch (Exception e) {
            Class<?> mcClass = Class.forName("ave", true, gameLoader);
            return mcClass.getMethod("A").invoke(null);
        }
    }

    public static void sendClientMessage(String message) {
        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null)
                return;

            if (!chatMethodResolved) {
                resolveChatMethod(gameLoader);
                chatMethodResolved = true;
            }

            if (addChatMethod == null)
                return;

            Object mc = getMinecraftInstance(gameLoader);
            if (mc == null)
                return;

            Object player = null;
            try {
                player = mc.getClass().getField("thePlayer").get(mc);
            } catch (Exception e) {
                player = mc.getClass().getField("h").get(mc);
            }
            if (player == null)
                return;

            Object chatComponent = null;
            try {
                Class<?> chatClass = Class.forName("net.minecraft.util.ChatComponentText", true, gameLoader);
                chatComponent = chatClass.getConstructor(String.class).newInstance(message);
            } catch (Exception e) {
                Class<?> chatClass = Class.forName("eu", true, gameLoader);
                chatComponent = chatClass.getConstructor(String.class).newInstance(message);
            }

            addChatMethod.invoke(player, chatComponent);
        } catch (Exception e) {
            AgentLogger.log("Failed to send client chat message!");
            AgentLogger.log(e);
        }
    }

    private static void resolveChatMethod(ClassLoader gameLoader) {
        try {
            Class<?> chatCompInterface = null;
            try {
                chatCompInterface = Class.forName("net.minecraft.util.IChatComponent", true, gameLoader);
            } catch (Exception e) {
                chatCompInterface = Class.forName("eu", true, gameLoader);
            }

            Class<?> playerClass = null;
            try {
                playerClass = Class.forName("net.minecraft.client.entity.EntityPlayerSP", true, gameLoader);
            } catch (Exception e) {
                playerClass = Class.forName("bew", true, gameLoader);
            }

            try {
                addChatMethod = playerClass.getMethod("addChatMessage", chatCompInterface);
            } catch (Exception e) {
                addChatMethod = playerClass.getMethod("a", chatCompInterface);
            }
        } catch (Exception e) {
            AgentLogger.log("CommandHandler: Failed to resolve addChatMessage!");
            AgentLogger.log(e);
        }
    }


}
