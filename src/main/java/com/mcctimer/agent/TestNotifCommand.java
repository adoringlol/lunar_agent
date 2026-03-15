package com.mcctimer.agent;

public class TestNotifCommand implements Command {
    @Override
    public boolean execute(String label, String[] args, String rawMessage) {
        MCCTimerState.notificationTitle = "\u00A7b\u00A7lFriend Request";
        MCCTimerState.notificationMessage = "\u00A7fAccept friend request from \u00A7eNotch\u00A7f?";
        MCCTimerState.notificationPlayerName = "";
        MCCTimerState.notificationStartTime = System.currentTimeMillis();
        return true;
    }
}
