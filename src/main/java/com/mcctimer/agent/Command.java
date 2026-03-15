package com.mcctimer.agent;

public interface Command {
    /**
     * Executes the given command.
     *
     * @param label The command alias that was used.
     * @param args The arguments passed to the command (excluding the label).
     * @param rawMessage The raw, full chat message string.
     * @return true if the command was successfully handled.
     */
    boolean execute(String label, String[] args, String rawMessage);

    /**
     * Handles tab completion for the given command.
     *
     * @param label The command alias that was used.
     * @param args The arguments passed to the command so far.
     * @param rawMessage The raw, full chat message string so far.
     * @return A list of possible completions, or an empty list if none.
     */
    default java.util.List<String> onTabComplete(String label, String[] args, String rawMessage) {
        return java.util.Collections.emptyList();
    }
}
