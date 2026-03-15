package com.mcctimer.agent;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PlayEmoteCommand implements Command {

    private static final Map<String, Integer> EMOTE_MAP = new HashMap<>();
    private static boolean loaded = false;

    public PlayEmoteCommand() {
        super();
    }

    @Override
    public boolean execute(String label, String[] args, String rawMessage) {
        loadEmotes();

        if (args.length < 1) {
            CommandHandler.sendClientMessage("\u00A7c[MMCTimer] \u00A7fUsage: /playemote <emote>");
            return true;
        }

        String emoteName = args[0].toLowerCase();
        if (!EMOTE_MAP.containsKey(emoteName)) {
            CommandHandler.sendClientMessage("\u00A7c[MMCTimer] \u00A7fUnknown emote: \u00A7e" + emoteName);
            return true;
        }

        int emoteId = EMOTE_MAP.get(emoteName);
        new Thread(() -> playEmote(emoteId, emoteName)).start();

        CommandHandler.sendClientMessage("\u00A7b[LC] \u00A7fPlaying emote: \u00A7e" + emoteName + "\u00A7f (ID: " + emoteId + ")");
        return true;
    }

    @Override
    public List<String> onTabComplete(String label, String[] args, String rawMessage) {
        loadEmotes();
        
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return EMOTE_MAP.keySet().stream()
                    .filter(name -> name.startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    private static void loadEmotes() {
        if (loaded) return;
        loaded = true;

        try {
            File emotesFile = new File(System.getProperty("user.home"), ".lunarclient/textures/assets/lunar/emotes/emotes.json");
            if (!emotesFile.exists()) {
                AgentLogger.log("PlayEmoteCommand: emotes.json not found at " + emotesFile.getAbsolutePath());
                return;
            }

            String content = new String(Files.readAllBytes(emotesFile.toPath()));
            Pattern pattern = Pattern.compile("\"id\"\\s*:\\s*(\\d+)[^}]+\"name\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(content);
            
            while (matcher.find()) {
                int id = Integer.parseInt(matcher.group(1));
                String name = matcher.group(2).toLowerCase();
                EMOTE_MAP.put(name, id);
            }
            
            AgentLogger.log("PlayEmoteCommand: Loaded " + EMOTE_MAP.size() + " emotes from JSON via regex.");
        } catch (Exception e) {
            AgentLogger.log("PlayEmoteCommand: Error loading emotes: " + e.getMessage());
            AgentLogger.log(e);
        }
    }

    private void playEmote(int emoteId, String emoteName) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            
            Class<?> useEmoteReqClass = loader.loadClass("com.lunarclient.websocket.emote.v1.UseEmoteRequest");
            Method newBuilderReq = useEmoteReqClass.getMethod("newBuilder");
            Object reqBuilder = newBuilderReq.invoke(null);
            Method setEmoteIdReq = reqBuilder.getClass().getMethod("setEmoteId", int.class);
            setEmoteIdReq.invoke(reqBuilder, emoteId);
            Method buildReq = reqBuilder.getClass().getMethod("build");
            Object req = buildReq.invoke(reqBuilder);

            Object wsClient = null;

            Class<?> emoteServiceIfClass = loader.loadClass("com.lunarclient.websocket.emote.v1.EmoteService$Interface");
            java.lang.reflect.Method getEmoteServiceMethod = null;

            Class<?>[] allClasses = MCCTimerAgent.instrumentation.getAllLoadedClasses();

            for (Class<?> c : allClasses) {
                if (c.getName() == null || !c.getName().startsWith("com.moonsworth.lunar.")) continue;
                java.lang.reflect.Method[] methods = null;
                try {
                    methods = c.getDeclaredMethods();
                } catch (Throwable t) { continue; }
                
                for (java.lang.reflect.Method m : methods) {
                    if (m.getReturnType() == emoteServiceIfClass && m.getParameterCount() == 0) {
                        getEmoteServiceMethod = m;
                        break;
                    }
                }
                if (getEmoteServiceMethod != null) break;
            }

            if (getEmoteServiceMethod == null) {
                AgentLogger.log("PlayEmoteCommand: Could not find WS client method returning EmoteService");
                return;
            }
            Class<?> wsClientClass = getEmoteServiceMethod.getDeclaringClass();
            
            for (Class<?> c : allClasses) {
                if (c.getName() == null || !c.getName().startsWith("com.moonsworth.lunar.")) continue;
                java.lang.reflect.Method[] methods = null;
                try {
                    methods = c.getDeclaredMethods();
                } catch (Throwable t) { continue; }

                for (java.lang.reflect.Method m : methods) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
                    Class<?> ret = m.getReturnType();
                    if (ret == null || ret.isPrimitive()) continue;

                    java.lang.reflect.Method[] subMethods = null;
                    try {
                        subMethods = ret.getDeclaredMethods();
                    } catch (Throwable t) { continue; }

                    for (java.lang.reflect.Method subM : subMethods) {
                        if (subM.getParameterCount() != 0) continue;
                        if (subM.getReturnType() == wsClientClass || wsClientClass.isAssignableFrom(subM.getReturnType())) {
                            try {
                                m.setAccessible(true);
                                Object mainSettingsManager = m.invoke(null);
                                if (mainSettingsManager != null) {
                                    subM.setAccessible(true);
                                    wsClient = subM.invoke(mainSettingsManager);
                                }
                            } catch (Throwable e) {}
                            if (wsClient != null) break;
                        }
                    }
                    if (wsClient != null) break;
                }
                if (wsClient != null) break;
            }

            if (wsClient == null) {
                AgentLogger.log("PlayEmoteCommand: Could not dynamically find LunarClient singleton with WebSocketClient field.");
                return;
            }
            
            getEmoteServiceMethod.setAccessible(true);
            
            
            Object emoteServiceIf = getEmoteServiceMethod.invoke(wsClient);
            if (emoteServiceIf == null) {
                AgentLogger.log("PlayEmoteCommand: emoteServiceIf is null");
                return;
            }

            Method writeEmoteMethod = null;
            for (Method m : emoteServiceIf.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == useEmoteReqClass) {
                    writeEmoteMethod = m;
                    break;
                }
            }

            if (writeEmoteMethod != null) {
                writeEmoteMethod.invoke(emoteServiceIf, req);
                AgentLogger.log("PlayEmoteCommand: Sent UseEmoteRequest to server: " + emoteName);
            }

            Class<?> mcClass = loader.loadClass("net.minecraft.client.Minecraft");
            Method getMcMethod = mcClass.getDeclaredMethod("getMinecraft");
            Object mc = getMcMethod.invoke(null);
            
            Field playerField = null;
            for (Field f : mcClass.getDeclaredFields()) {
                if (f.getType().getName().equals("net.minecraft.client.entity.EntityPlayerSP") ||
                    f.getType().getName().equals("bew")) { // 1.8.9 EntityPlayerSP obfuscated name
                    playerField = f;
                    break;
                }
            }
            if (playerField == null) {
                AgentLogger.log("PlayEmoteCommand: Could not find EntityPlayerSP field in Minecraft class.");
                return;
            }
            playerField.setAccessible(true);
            Object thePlayer = playerField.get(mc);
            if (thePlayer == null) {
                AgentLogger.log("PlayEmoteCommand: thePlayer is null.");
            }
            if (thePlayer != null) {
                Method getUuidMethod = thePlayer.getClass().getMethod("getUniqueID");
                UUID localUuid = (UUID) getUuidMethod.invoke(thePlayer);
                
                Class<?> uuidClass = loader.loadClass("com.lunarclient.common.v1.Uuid");
                Method uuidBuilderMeth = uuidClass.getMethod("newBuilder");
                Object uuidBuilder = uuidBuilderMeth.invoke(null);
                
                Method setHigh = uuidBuilder.getClass().getMethod("setHigh64", long.class);
                setHigh.invoke(uuidBuilder, localUuid.getMostSignificantBits());
                Method setLow = uuidBuilder.getClass().getMethod("setLow64", long.class);
                setLow.invoke(uuidBuilder, localUuid.getLeastSignificantBits());
                Object protoUuid = uuidBuilder.getClass().getMethod("build").invoke(uuidBuilder);
                    
                Class<?> useEmotePushClass = loader.loadClass("com.lunarclient.websocket.emote.v1.UseEmotePush");
                Method pushBuilderMeth = useEmotePushClass.getMethod("newBuilder");
                Object pushBuilder = pushBuilderMeth.invoke(null);
                
                Method setEmoteIdPush = pushBuilder.getClass().getMethod("setEmoteId", int.class);
                setEmoteIdPush.invoke(pushBuilder, emoteId);
                
                Method setPlayerUuidPush = pushBuilder.getClass().getMethod("setPlayerUuid", uuidClass);
                setPlayerUuidPush.invoke(pushBuilder, protoUuid);
                
                Object push = pushBuilder.getClass().getMethod("build").invoke(pushBuilder);
                    
                Method pushMethod = null;
                for (Method m : wsClient.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == useEmotePushClass) {
                        pushMethod = m;
                        break;
                    }
                }
                
                if (pushMethod == null) {
                    AgentLogger.log("PlayEmoteCommand: pushMethod is null! Could not find it on wsClient.");
                }
                
                if (pushMethod != null) {
                    pushMethod.setAccessible(true);
                    pushMethod.invoke(wsClient, push);
                    AgentLogger.log("PlayEmoteCommand: Forced local playback of UseEmotePush.");
                }
            }

        } catch (Throwable e) {
            AgentLogger.log("PlayEmoteCommand error: " + e.getMessage());
            AgentLogger.log(e);
        }
    }
}
