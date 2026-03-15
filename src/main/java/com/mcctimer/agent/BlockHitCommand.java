package com.mcctimer.agent;

public class BlockHitCommand implements Command {

    @Override
    public boolean execute(String label, String[] args, String rawMessage) {
        if (args.length < 1) {
            CommandHandler.sendClientMessage("\u00A7b[LC] \u00A7fUsage:");
            CommandHandler.sendClientMessage("\u00A7e/blockhit <sound> \u00A77- Set the block hit sound");
            CommandHandler.sendClientMessage("\u00A7e/blockhit toggle \u00A77- Toggle on/off");
            String current = MCCTimerState.blockHitSound.isEmpty() ? "none" : MCCTimerState.blockHitSound;
            String status = MCCTimerState.blockHitEnabled ? "\u00A7aON" : "\u00A7cOFF";
            CommandHandler.sendClientMessage("\u00A77Current sound: \u00A7e" + current + " \u00A77| Status: " + status);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("toggle")) {
            MCCTimerState.blockHitEnabled = !MCCTimerState.blockHitEnabled;
            MCCTimerState.saveConfig();
            String status = MCCTimerState.blockHitEnabled ? "\u00A7aON" : "\u00A7cOFF";
            CommandHandler.sendClientMessage("\u00A7b[LC] \u00A7fBlock hit sound: " + status);
            return true;
        }

        // Set the sound name
        MCCTimerState.blockHitSound = args[0];
        MCCTimerState.blockHitEnabled = true;
        MCCTimerState.saveConfig();
        CommandHandler.sendClientMessage("\u00A7b[LC] \u00A7fBlock hit sound set to: \u00A7e" + args[0]);

        // Play a preview
        new Thread(() -> BlockHitDetector.playPreviewSound()).start();

        return true;
    }
}
