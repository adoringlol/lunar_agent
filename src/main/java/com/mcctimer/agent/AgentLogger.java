package com.mcctimer.agent;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Date;

public class AgentLogger {
    public static void log(String msg) {
        try (PrintWriter out = new PrintWriter(
                new FileWriter(System.getProperty("user.home") + "/mcctimer-debug.log", true))) {
            out.println("[" + new Date().toString() + "] " + msg);
        } catch (Exception e) {
        }
    }

    public static void log(Throwable ex) {
        try (PrintWriter out = new PrintWriter(
                new FileWriter(System.getProperty("user.home") + "/mcctimer-debug.log", true))) {
            out.println("[" + new Date().toString() + "] ERROR:");
            ex.printStackTrace(out);
        } catch (Exception e) {
        }
    }
}
