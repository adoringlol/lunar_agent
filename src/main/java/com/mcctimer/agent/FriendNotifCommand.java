package com.mcctimer.agent;

public class FriendNotifCommand implements Command {
    @Override
    public boolean execute(String label, String[] args, String rawMessage) {
        String playerName = args.length > 0 ? args[0] : "Notch";
        
        // Pre-resolve UUID and trigger discovery on background thread (avoids render lag)
        new Thread(() -> {
            try {
                NotificationRenderer.prefetchPlayerHead(playerName);
            } catch (Exception e) {
                AgentLogger.log("friendnotif: prefetch error: " + e.getMessage());
            }
            // Set notification state AFTER prefetch completes
            MCCTimerState.notificationTitle = "";
            MCCTimerState.notificationMessage = "\u00A7a\u00A7l" + playerName + "\u00A7r is now online";
            MCCTimerState.notificationPlayerName = playerName;
            MCCTimerState.notificationStartTime = System.currentTimeMillis();
        }, "FriendNotifPrefetch").start();
        return true;
    }
}
