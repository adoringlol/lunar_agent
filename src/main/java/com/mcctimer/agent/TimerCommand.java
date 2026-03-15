package com.mcctimer.agent;

public class TimerCommand implements Command {
    @Override
    public boolean execute(String label, String[] args, String rawMessage) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        switch (sub) {
            case "edit":
                MCCTimerState.editMode = true;
                MCCTimerState.dragging = false;
                MCCTimerState.pendingEditScreen = true;
                CommandHandler.sendClientMessage(
                        "\u00A7a[MMCTimer] \u00A7fEdit mode \u00A7aON\u00A7f. Drag the timer. Press \u00A7eESC\u00A7f to save.");
                return true;

            case "reset":
                MCCTimerState.timerX = 10;
                MCCTimerState.timerY = 10;
                MCCTimerState.timerScale = 2.0f;
                MCCTimerState.saveConfig();
                CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fPosition reset to default.");
                return true;

            case "scale":
                if (args.length < 2) {
                    CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fUsage: /gui scale <value>");
                    return true;
                }
                try {
                    float scale = Float.parseFloat(args[1]);
                    scale = Math.max(0.5f, Math.min(5.0f, scale));
                    MCCTimerState.timerScale = scale;
                    MCCTimerState.saveConfig();
                    CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fTimer scale set to \u00A7e" + scale);
                } catch (NumberFormatException e) {
                    CommandHandler.sendClientMessage("\u00A7c[MMCTimer] \u00A7fInvalid number.");
                }
                return true;

            case "fireball":
                if (args.length < 2) {
                    CommandHandler.sendClientMessage("\u00A7a\u00A7l[MMCTimer] \u00A7fFireball Settings:");
                    CommandHandler.sendClientMessage("\u00A7e/gui fireball distance <value> \u00A77- Detection range (\u00A7e" + MCCTimerState.fireballDetectDistance + "\u00A77)");
                    
                    String radiusStr = MCCTimerState.fireballBlastRadius > 0 ? String.valueOf(MCCTimerState.fireballBlastRadius) : "Auto (\u00A7e" + MCCTimerState.getEffectiveBlastRadius() + "\u00A77)";
                    CommandHandler.sendClientMessage("\u00A7e/gui fireball radius <value> \u00A77- Blast radius (\u00A7e" + radiusStr + "\u00A77)");
                    CommandHandler.sendClientMessage("\u00A7e/gui fireball throughwalls <true|false> \u00A77- Visible through walls (\u00A7e" + MCCTimerState.fireballThroughWalls + "\u00A77)");
                    CommandHandler.sendClientMessage("\u00A7e/gui fireball toggleraycast <on|off> \u00A77- Trajectory ray + explosion preview (\u00A7e" + (MCCTimerState.fireballRaycastEnabled ? "on" : "off") + "\u00A77)");
                    
                    if (!MCCTimerState.currentServerIP.isEmpty()) {
                        CommandHandler.sendClientMessage("\u00A77Detected Server: \u00A7e" + MCCTimerState.currentServerIP);
                    }
                    return true;
                }
                String fireballSub = args[1].toLowerCase();
                if (fireballSub.equals("distance")) {
                    if (args.length < 3) {
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fUsage: /gui fireball distance <value>");
                        CommandHandler.sendClientMessage("\u00A77Current: \u00A7e" + MCCTimerState.fireballDetectDistance + " blocks");
                        return true;
                    }
                    try {
                        float dist = Float.parseFloat(args[2]);
                        dist = Math.max(1.0f, Math.min(50.0f, dist));
                        MCCTimerState.fireballDetectDistance = dist;
                        MCCTimerState.saveConfig();
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fDetection distance set to \u00A7e" + dist + " blocks");
                    } catch (NumberFormatException e) {
                        CommandHandler.sendClientMessage("\u00A7c[MMCTimer] \u00A7fInvalid number.");
                    }
                } else if (fireballSub.equals("radius")) {
                    if (args.length < 3) {
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fUsage: /gui fireball radius <value>");
                        String radiusStr = MCCTimerState.fireballBlastRadius > 0 ? String.valueOf(MCCTimerState.fireballBlastRadius) : "Auto (\u00A7e" + MCCTimerState.getEffectiveBlastRadius() + "\u00A77)";
                        CommandHandler.sendClientMessage("\u00A77Current: \u00A7e" + radiusStr + " blocks");
                        if (!MCCTimerState.currentServerIP.isEmpty()) {
                            CommandHandler.sendClientMessage("\u00A77Server: \u00A7e" + MCCTimerState.currentServerIP);
                        }
                        CommandHandler.sendClientMessage("\u00A77Tip: Set to \u00A7eauto \u00A77to auto-detect from explosions");
                        return true;
                    }
                    if (args[2].equalsIgnoreCase("auto")) {
                        MCCTimerState.fireballBlastRadius = -1;
                        MCCTimerState.saveConfig();
                        TrajectoryRenderer.dynamicBlastRadius = 1.2;
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fBlast radius set to \u00A7eauto-detect\u00A7f (will calibrate on next explosion)");
                        return true;
                    }
                    try {
                        float radius = Float.parseFloat(args[2]);
                        radius = Math.max(0.5f, Math.min(10.0f, radius));
                        MCCTimerState.fireballBlastRadius = radius;
                        TrajectoryRenderer.dynamicBlastRadius = radius;
                        MCCTimerState.saveConfig();
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fBlast radius set to \u00A7e" + radius + " blocks");
                    } catch (NumberFormatException e) {
                        CommandHandler.sendClientMessage("\u00A7c[MMCTimer] \u00A7fInvalid number. Use a number or 'auto'.");
                    }
                } else if (fireballSub.equals("throughwalls")) {
                    if (args.length < 3) {
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fUsage: /gui fireball throughwalls <true|false>");
                        CommandHandler.sendClientMessage("\u00A77Current: \u00A7e" + MCCTimerState.fireballThroughWalls);
                        return true;
                    }
                    boolean val = Boolean.parseBoolean(args[2]);
                    MCCTimerState.fireballThroughWalls = val;
                    MCCTimerState.saveConfig();
                    CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fFireball ESP visible through walls set to \u00A7e" + val);
                } else if (fireballSub.equals("toggleraycast")) {
                    if (args.length < 3) {
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fUsage: /gui fireball toggleraycast <on|off>");
                        CommandHandler.sendClientMessage("\u00A77Current: \u00A7e" + (MCCTimerState.fireballRaycastEnabled ? "on" : "off"));
                        return true;
                    }
                    String val = args[2].toLowerCase();
                    boolean enable;
                    if (val.equals("on") || val.equals("true")) {
                        enable = true;
                    } else if (val.equals("off") || val.equals("false")) {
                        enable = false;
                    } else {
                        CommandHandler.sendClientMessage("\u00A7c[MMCTimer] \u00A7fInvalid value. Use on/off.");
                        return true;
                    }
                    MCCTimerState.fireballRaycastEnabled = enable;
                    MCCTimerState.saveConfig();
                    CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fFireball trajectory ray/explosion preview set to \u00A7e" + (enable ? "on" : "off"));
                } else {
                    CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fUsage: /gui fireball <distance|radius|throughwalls|toggleraycast> <value>");
                }
                return true;

            case "tnt":
                if (args.length < 2) {
                    CommandHandler.sendClientMessage("\u00A7a\u00A7l[MMCTimer] \u00A7fTNT Countdown HUD:");
                    CommandHandler.sendClientMessage("\u00A7e/gui tnt distance <value> \u00A77- Detection range (\u00A7e" + MCCTimerState.tntDetectDistance + "\u00A77)");
                    CommandHandler.sendClientMessage("\u00A7e/gui tnt scale <value> \u00A77- HUD scale (\u00A7e" + MCCTimerState.tntHudScale + "\u00A77)");
                    CommandHandler.sendClientMessage("\u00A7e/gui tnt toggle <on|off> \u00A77- Show/Hide TNT HUD (\u00A7e" + (MCCTimerState.tntHudEnabled ? "on" : "off") + "\u00A77)");
                    return true;
                }
                String tntSub = args[1].toLowerCase();
                if (tntSub.equals("distance")) {
                    if (args.length < 3) {
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fUsage: /gui tnt distance <value>");
                        CommandHandler.sendClientMessage("\u00A77Current: \u00A7e" + MCCTimerState.tntDetectDistance + " blocks");
                        return true;
                    }
                    try {
                        float dist = Float.parseFloat(args[2]);
                        dist = Math.max(2.0f, Math.min(120.0f, dist));
                        MCCTimerState.tntDetectDistance = dist;
                        MCCTimerState.saveConfig();
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fTNT detection range set to \u00A7e" + dist + " blocks");
                    } catch (NumberFormatException e) {
                        CommandHandler.sendClientMessage("\u00A7c[MMCTimer] \u00A7fInvalid number.");
                    }
                } else if (tntSub.equals("scale")) {
                    if (args.length < 3) {
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fUsage: /gui tnt scale <value>");
                        CommandHandler.sendClientMessage("\u00A77Current: \u00A7e" + MCCTimerState.tntHudScale);
                        return true;
                    }
                    try {
                        float scale = Float.parseFloat(args[2]);
                        scale = Math.max(0.5f, Math.min(5.0f, scale));
                        MCCTimerState.tntHudScale = scale;
                        MCCTimerState.saveConfig();
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fTNT HUD scale set to \u00A7e" + scale);
                    } catch (NumberFormatException e) {
                        CommandHandler.sendClientMessage("\u00A7c[MMCTimer] \u00A7fInvalid number.");
                    }
                } else if (tntSub.equals("toggle")) {
                    if (args.length < 3) {
                        CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fUsage: /gui tnt toggle <on|off>");
                        CommandHandler.sendClientMessage("\u00A77Current: \u00A7e" + (MCCTimerState.tntHudEnabled ? "on" : "off"));
                        return true;
                    }
                    String val = args[2].toLowerCase();
                    Boolean enable = null;
                    if (val.equals("on") || val.equals("true")) enable = true;
                    else if (val.equals("off") || val.equals("false")) enable = false;

                    if (enable == null) {
                        CommandHandler.sendClientMessage("\u00A7c[MMCTimer] \u00A7fInvalid value. Use on/off.");
                        return true;
                    }
                    MCCTimerState.tntHudEnabled = enable;
                    MCCTimerState.saveConfig();
                    CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fTNT HUD is now \u00A7e" + (enable ? "on" : "off"));
                } else {
                    CommandHandler.sendClientMessage("\u00A7a[MMCTimer] \u00A7fUsage: /gui tnt <distance|scale|toggle> <value>");
                }
                return true;

            default:
                CommandHandler.sendClientMessage("\u00A7a\u00A7l[MMCTimer] \u00A7fCommands:");
                CommandHandler.sendClientMessage("\u00A7e/gui edit \u00A77- Drag/resize HUD elements (ESC to save)");
                CommandHandler.sendClientMessage("\u00A7e/gui reset \u00A77- Reset timer position to default");
                CommandHandler.sendClientMessage("\u00A7e/gui scale <value> \u00A77- Set timer scale (0.5-5.0)");
                CommandHandler.sendClientMessage("\u00A7e/gui fireball \u00A77- Fireball settings (distance, radius)");
                CommandHandler.sendClientMessage("\u00A7e/gui tnt \u00A77- TNT countdown HUD settings");
                return true;
        }
    }
}

