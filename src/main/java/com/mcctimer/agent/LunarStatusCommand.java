package com.mcctimer.agent;

import java.lang.reflect.Method;
import java.util.Collection;

public class LunarStatusCommand implements Command {
    @Override
    public boolean execute(String label, String[] args, String rawMessage) {
        if (args.length < 1) {
            CommandHandler.sendClientMessage("\u00A7b[LC] \u00A7cUsage: /" + label + " <player>");
            return true;
        }

        String targetName = args[0];

        new Thread(() -> {
            try {
                Collection<?> friends = LunarMsgCommand.getFriendCollection();
                if (friends == null) {
                    CommandHandler.sendClientMessage("\u00A7c[LC] Failed to access Lunar Client friend manager.");
                    return;
                }

                Object foundFriend = null;
                for (Object currentProfile : friends) {
                    try {
                        Method getNameMethod = currentProfile.getClass().getMethod("getName");
                        String name = (String) getNameMethod.invoke(currentProfile);
                        if (name != null && name.equalsIgnoreCase(targetName)) {
                            foundFriend = currentProfile;
                            break;
                        }
                    } catch (Throwable t) {
                    }
                }

                if (foundFriend == null) {
                    CommandHandler.sendClientMessage("\u00A7c[LC] You are not friends with \u00A7e" + targetName + "\u00A7c, or they were not found online/offline.");
                    return;
                }

                String statusStr = "Unknown Status";
                String offlineText = null;
                Object locationProtoWrapper = null;
                String modpackStr = "None";

                try {
                    for (Method m : foundFriend.getClass().getMethods()) {
                        if (m.getParameterCount() != 0) continue;

                        Class<?> returnType = m.getReturnType();

                        try {
                            if (returnType.getName().endsWith("com.lunarclient.common.v1.Location")) {
                                locationProtoWrapper = m.invoke(foundFriend);
                            }
                        } catch (Throwable t) {}

                        try {
                            if (returnType.isEnum() || Enum.class.isAssignableFrom(returnType)) {
                                Object enumVal = m.invoke(foundFriend);
                                if (enumVal != null) {
                                    Method nameMethod = enumVal.getClass().getMethod("name");
                                    String enumName = (String) nameMethod.invoke(enumVal);
                                    if (enumName.equalsIgnoreCase("OFFLINE") || enumName.equalsIgnoreCase("ONLINE") ||
                                        enumName.equalsIgnoreCase("AWAY") || enumName.equalsIgnoreCase("BUSY") ||
                                        enumName.equalsIgnoreCase("INVISIBLE")) {
                                        statusStr = enumName;
                                    }
                                }
                            }
                        } catch (Throwable t) {}

                        try {
                            if (returnType == String.class) {
                                String strVal = (String) m.invoke(foundFriend);
                                if (strVal != null && strVal.startsWith("Offline for ")) {
                                    offlineText = strVal;
                                }
                            }
                        } catch (Throwable t) {}
                    }
                } catch (Throwable t) {
                    CommandHandler.sendClientMessage("\u00A7c[LC] Error pulling status context.");
                }

                if (!statusStr.equalsIgnoreCase("OFFLINE") && !statusStr.equalsIgnoreCase("Unknown Status")) {
                    String locationStr = "Unknown Location";
                    try {
                        if (locationProtoWrapper != null) {
                            Method caseEnumM = locationProtoWrapper.getClass().getMethod("getLocationCase");
                            Object caseEnum = caseEnumM.invoke(locationProtoWrapper);
                            String caseName = caseEnum == null ? "" : (String) caseEnum.getClass().getMethod("name").invoke(caseEnum);

                            if ("SERVER".equalsIgnoreCase(caseName)) {
                                Object serverProto = locationProtoWrapper.getClass().getMethod("getServer").invoke(locationProtoWrapper);
                                if (serverProto != null) {
                                    String ip = (String) serverProto.getClass().getMethod("getServerIp").invoke(serverProto);
                                    locationStr = "Playing on: \u00A7e" + ip;

                                    try {
                                        Object richStatus = serverProto.getClass().getMethod("getRichStatus").invoke(serverProto);
                                        if (richStatus != null) {
                                            String gameName = (String) richStatus.getClass().getMethod("getGameName").invoke(richStatus);
                                            String gameState = (String) richStatus.getClass().getMethod("getGameState").invoke(richStatus);
                                            locationStr += " \u00A7f| \u00A7b" + gameName + " \u00A7f- " + gameState;
                                        }
                                    } catch (Throwable ignores) {}
                                }
                            } else if ("SINGLE_PLAYER".equalsIgnoreCase(caseName) || "SINGLEPLAYER".equalsIgnoreCase(caseName)) {
                                locationStr = "In Singleplayer";
                            } else if ("IN_MENUS".equalsIgnoreCase(caseName) || "INMENUS".equalsIgnoreCase(caseName)) {
                                locationStr = "In Menus";
                            } else if ("IN_GAME".equalsIgnoreCase(caseName) || "INGAME".equalsIgnoreCase(caseName)) {
                                locationStr = "In Game";
                            } else if ("HOSTED_WORLD".equalsIgnoreCase(caseName) || "HOSTEDWORLD".equalsIgnoreCase(caseName)) {
                                locationStr = "Hosted World";
                            } else {
                                locationStr = caseName;
                            }
                        }
                    } catch (Throwable t) {}

                    CommandHandler.sendClientMessage("\u00A7a[LC] \u00A7b" + targetName + " \u00A7f- \u00A7aOnline \u00A7f(" + statusStr + ")");
                    CommandHandler.sendClientMessage("\u00A7a[LC] \u00A7fLocation: \u00A7e" + locationStr);

                } else if (statusStr.equalsIgnoreCase("OFFLINE")) {
                    CommandHandler.sendClientMessage("\u00A7c[LC] \u00A7b" + targetName + " \u00A7f- \u00A7cOffline");
                    if (offlineText != null) {
                        CommandHandler.sendClientMessage("\u00A7c[LC] \u00A7fLast Seen: \u00A77" + offlineText);
                    }
                } else {
                    CommandHandler.sendClientMessage("\u00A7e[LC] \u00A7b" + targetName + " \u00A7f- \u00A7eStatus Unknown\u00A7f (Could not parse state)");
                }

            } catch (Throwable ex) {
                AgentLogger.log("Error in /lunarstatus: " + ex.getMessage());
                for (StackTraceElement element : ex.getStackTrace()) {
                    AgentLogger.log(element.toString());
                }
            }
        }).start();

        return true;
    }
}
