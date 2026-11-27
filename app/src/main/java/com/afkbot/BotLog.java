package com.afkbot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Shared log + running flag (service and activity live in the same process). */
public final class BotLog {
    private static final ArrayList<String> lines = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);
    public static volatile boolean running = false;
    public static volatile int version = 0;

    private BotLog() {}

    public static synchronized void add(String msg) {
        lines.add(FMT.format(new Date()) + "  " + msg);
        if (lines.size() > 300) lines.remove(0);
        version++;
    }

    public static synchronized void clear() {
        lines.clear();
        version++;
    }

    public static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }
}
