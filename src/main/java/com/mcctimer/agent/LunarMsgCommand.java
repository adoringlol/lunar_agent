package com.mcctimer.agent;

public class LunarMsgCommand implements Command {
    @Override
    public boolean execute(String label, String[] args, String rawMessage) {
        if (args.length < 2) {
            CommandHandler.sendClientMessage("\u00A7b[LC] \u00A7fUsage: /lunarmsg <friend_name> <message>");
            return true;
        }
        String friendName = args[0];
        String messageContent = rawMessage.substring(label.length() + friendName.length() + 2).trim();
        new Thread(() -> sendTestMessage(friendName, messageContent)).start();
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(String label, String[] args, String rawMessage) {
        if (args.length == 1) {
            String partialName = args[0].toLowerCase();
            java.util.List<String> matches = new java.util.ArrayList<>();
            for (String friend : getFriendNames()) {
                if (friend.toLowerCase().startsWith(partialName)) {
                    matches.add(friend);
                }
            }
            return matches;
        }
        return new java.util.ArrayList<>();
    }

    public static java.util.Collection<?> getFriendCollection() {
        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null) return null;
            
            Class<?>[] allClasses = MCCTimerAgent.instrumentation.getAllLoadedClasses();
            Object friendManager = null;
            
            for (Class<?> c : allClasses) {
                if (c.getName() == null || !c.getName().startsWith("com.moonsworth.lunar.")) continue;
                
                Object singletonInstance = null;
                try {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == c) {
                            f.setAccessible(true);
                            singletonInstance = f.get(null);
                            break;
                        }
                    }
                } catch (Throwable t) {}
                
                if (singletonInstance == null) continue;
                
                try {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        Class<?> fieldType = f.getType();
                        if (fieldType.isPrimitive() || fieldType == String.class || !fieldType.getName().startsWith("com.moonsworth.lunar.")) continue;
                        f.setAccessible(true);
                        Object potentialFriendManager = f.get(singletonInstance);
                        if (potentialFriendManager == null) continue;
                        
                        Class<?> pfmClass = potentialFriendManager.getClass();
                        while (pfmClass != null && pfmClass != Object.class) {
                            for (java.lang.reflect.Method collM : pfmClass.getDeclaredMethods()) {
                                if (collM.getParameterCount() == 0 && java.lang.reflect.Modifier.isPublic(collM.getModifiers()) && java.util.Collection.class.isAssignableFrom(collM.getReturnType())) {
                                    try {
                                        collM.setAccessible(true);
                                        Object objReturn = collM.invoke(potentialFriendManager);
                                        if (objReturn instanceof java.util.Collection<?>) {
                                            java.util.Collection<?> col = (java.util.Collection<?>) objReturn;
                                            if (!col.isEmpty()) {
                                                Object firstElement = col.iterator().next();
                                                if (firstElement != null) {
                                                    boolean hasName = false, hasUuid = false;
                                                    for (java.lang.reflect.Method profileM : firstElement.getClass().getMethods()) {
                                                        if (profileM.getName().equals("getName") && profileM.getReturnType() == String.class) hasName = true;
                                                        if (profileM.getName().equals("getUuid") && profileM.getReturnType() == java.util.UUID.class) hasUuid = true;
                                                    }
                                                    if (hasName && hasUuid) {
                                                        friendManager = potentialFriendManager;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable t) {}
                                }
                            }
                            if (friendManager != null) break;
                            pfmClass = pfmClass.getSuperclass();
                        }
                        if (friendManager != null) break;
                    }
                } catch (Throwable t) {}
                if (friendManager != null) break;
            }
            
            if (friendManager == null) return null;
            
            java.util.Collection<?> friendCollection = null;
            Class<?> currClass = friendManager.getClass();
            int largestSize = -1;
            while (currClass != null && currClass != Object.class) {
                for (java.lang.reflect.Method m : currClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && java.lang.reflect.Modifier.isPublic(m.getModifiers()) && java.util.Collection.class.isAssignableFrom(m.getReturnType())) {
                        try {
                            m.setAccessible(true);
                            Object colObj = m.invoke(friendManager);
                            if (colObj instanceof java.util.Collection) {
                                java.util.Collection<?> col = (java.util.Collection<?>) colObj;
                                if (!col.isEmpty()) {
                                    Object firstElement = col.iterator().next();
                                    if (firstElement != null) {
                                        boolean hasName = false, hasUuid = false;
                                        for (java.lang.reflect.Method profileM : firstElement.getClass().getMethods()) {
                                            if (profileM.getName().equals("getName") && profileM.getReturnType() == String.class) hasName = true;
                                            if (profileM.getName().equals("getUuid") && profileM.getReturnType() == java.util.UUID.class) hasUuid = true;
                                        }
                                        if (hasName && hasUuid && col.size() > largestSize) {
                                            largestSize = col.size();
                                            friendCollection = col;
                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) {}
                    }
                }
                currClass = currClass.getSuperclass();
            }
            return friendCollection;
        } catch (Exception e) {}
        return null;
    }

    public static java.util.List<String> getFriendNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            java.util.Collection<?> friendCollection = getFriendCollection();
            if (friendCollection != null) {
                for (Object profile : friendCollection) {
                    java.lang.reflect.Method getNameMethod = profile.getClass().getMethod("getName");
                    String name = (String) getNameMethod.invoke(profile);
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception e) {}
        return names;
    }

    private void sendTestMessage(String targetName, String messageContent) {
        try {
            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            if (gameLoader == null) return;

            AgentLogger.log("sendTestMessage: Looking for ConversationService...");

            // 1. Get the interface class
            Class<?> conversationServiceIfClass = Class.forName("com.lunarclient.websocket.conversation.v1.ConversationService$Interface", true, gameLoader);

            // 2. Discover the websocket client instance that provides ConversationService.Interface
            Object wsClient = null;
            java.lang.reflect.Method getConversationServiceMethod = null;

            Class<?>[] allClasses = MCCTimerAgent.instrumentation.getAllLoadedClasses();

            // First pass: find the websocket class that has a method returning ConversationService$Interface
            for (Class<?> c : allClasses) {
                if (c.getName() == null || !c.getName().startsWith("com.moonsworth.lunar.")) continue;
                java.lang.reflect.Method[] methods = null;
                try {
                    methods = c.getDeclaredMethods();
                } catch (Throwable t) { continue; }

                for (java.lang.reflect.Method m : methods) {
                    if (m.getReturnType() == conversationServiceIfClass && m.getParameterCount() == 0) {
                        getConversationServiceMethod = m;
                        break;
                    }
                }
                if (getConversationServiceMethod != null) break;
            }

            if (getConversationServiceMethod == null) {
                AgentLogger.log("sendTestMessage: Could not find WS client method returning ConversationService.Interface");
                return;
            }
            Class<?> wsClientClass = getConversationServiceMethod.getDeclaringClass();
            
            // Second pass: find a singleton that provides this websocket client
            Object mainSettingsManager = null;
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
                                mainSettingsManager = m.invoke(null);
                                if (mainSettingsManager != null) {
                                    subM.setAccessible(true);
                                    wsClient = subM.invoke(mainSettingsManager);
                                }
                            } catch (Exception e) {}
                            if (wsClient != null) break;
                        }
                    }
                    if (wsClient != null) break;
                }
                if (wsClient != null) break;
            }

            if (wsClient == null) {
                AgentLogger.log("sendTestMessage: Could not find WS client instance");
                return;
            }

            getConversationServiceMethod.setAccessible(true);
            Object conversationServiceIf = getConversationServiceMethod.invoke(wsClient);
            if (conversationServiceIf == null) return;

            // Lookup UUID from PlayerDB API (more reliable than Mojang API)
            java.util.UUID friendId = null;
            try {
                java.net.URL url = new java.net.URL("https://playerdb.co/api/player/minecraft/" + targetName);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "MCCTimer-Agent");
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                    String response = in.readLine();
                    in.close();
                    if (response != null && response.contains("\"id\":\"")) {
                        // Response looks like: ..."id":"0dcbc410-091a-4c2f-b4de-12eeb8ffc327"...
                        int idIndex = response.indexOf("\"id\":\"") + 6;
                        String uuidStr = response.substring(idIndex, idIndex + 36);
                        friendId = java.util.UUID.fromString(uuidStr);
                    }
                }
            } catch (Exception e) {
                AgentLogger.log("sendTestMessage: Failed to lookup UUID for " + targetName + " from PlayerDB API");
                AgentLogger.log(e);
            }
            
            if (friendId == null) {
                AgentLogger.log("sendTestMessage: Could not resolve UUID for '" + targetName + "'.");
                CommandHandler.sendClientMessage("\u00A7c[MMCTimer] \u00A7fCould not resolve UUID for \u00A7e" + targetName + "\u00A7f via PlayerDB API.");
                return;
            }
            
            AgentLogger.log("sendTestMessage: Found target UUID " + friendId.toString() + " for " + targetName);

            // Send executing message to client
            CommandHandler.sendClientMessage("\u00A7b[LC] \u00A7e(To \u00A7f" + targetName + "\u00A7e) " + messageContent);

            // 3. Construct Protobuf message via reflection

            Class<?> uuidClass = Class.forName("com.lunarclient.common.v1.Uuid", true, gameLoader);
            Object uuidBuilder = uuidClass.getMethod("newBuilder").invoke(null);
            uuidBuilder.getClass().getMethod("setHigh64", long.class).invoke(uuidBuilder, friendId.getMostSignificantBits());
            uuidBuilder.getClass().getMethod("setLow64", long.class).invoke(uuidBuilder, friendId.getLeastSignificantBits());
            Object uuidProto = uuidBuilder.getClass().getMethod("build").invoke(uuidBuilder);

            Class<?> crClass = Class.forName("com.lunarclient.websocket.conversation.v1.ConversationReference", true, gameLoader);
            Object crBuilder = crClass.getMethod("newBuilder").invoke(null);
            crBuilder.getClass().getMethod("setFriendUuid", uuidClass).invoke(crBuilder, uuidProto);
            Object crProto = crBuilder.getClass().getMethod("build").invoke(crBuilder);

            Class<?> cmcClass = Class.forName("com.lunarclient.websocket.conversation.v1.ConversationMessageContents", true, gameLoader);
            Object cmcBuilder = cmcClass.getMethod("newBuilder").invoke(null);
            cmcBuilder.getClass().getMethod("setPlainText", String.class).invoke(cmcBuilder, messageContent);
            Object cmcProto = cmcBuilder.getClass().getMethod("build").invoke(cmcBuilder);

            Class<?> scmrClass = Class.forName("com.lunarclient.websocket.conversation.v1.SendConversationMessageRequest", true, gameLoader);
            Object scmrBuilder = scmrClass.getMethod("newBuilder").invoke(null);
            scmrBuilder.getClass().getMethod("setConversationReference", crClass).invoke(scmrBuilder, crProto);
            scmrBuilder.getClass().getMethod("setMessageContents", cmcClass).invoke(scmrBuilder, cmcProto);
            Object scmrProto = scmrBuilder.getClass().getMethod("build").invoke(scmrBuilder);

            // 4. Send message
            Class<?> rpcControllerClass = Class.forName("com.google.protobuf.RpcController", true, gameLoader);
            Class<?> rpcCallbackClass = Class.forName("com.google.protobuf.RpcCallback", true, gameLoader);

            java.lang.reflect.Method sendMethod = conversationServiceIfClass.getMethod("sendConversationMessage", rpcControllerClass, scmrClass, rpcCallbackClass);

            AgentLogger.log("sendTestMessage: Invoking send method...");
            // Because RpcCallback is required for signature, we can pass null. RpcController can also be null.
            sendMethod.invoke(conversationServiceIf, null, scmrProto, null);
            AgentLogger.log("sendTestMessage: Message sent successfully!");

        } catch (Exception e) {
            AgentLogger.log("sendTestMessage: Error sending message.");
            AgentLogger.log(e);
        }
    }

}
