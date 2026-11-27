package com.afkbot;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Tiny standalone Minecraft Java client (protocol 775 = 26.1.x) for AFK use.
 * No game client needed. Stands completely still, only answers keep-alives.
 */
public class BotClient implements Runnable {

    public interface Listener { void log(String msg); }

    static final int PROTOCOL = 775;
    static final int ST_LOGIN = 0, ST_CONFIG = 1, ST_PLAY = 2;

    final String host; final int port; final String user; final String pass; final Listener out;
    public long retryMs = 10000, loginDelayMs = 5000, survivalDelayMs = 3000;
    volatile boolean stopped;
    volatile Socket sock;
    Thread thread;

    public BotClient(String host, int port, String user, String pass, Listener out) {
        this.host = host; this.port = port; this.user = user; this.pass = pass; this.out = out;
    }

    public void start() { thread = new Thread(this, "bot-client"); thread.setDaemon(true); thread.start(); }

    public void stop() {
        stopped = true;
        closeQuiet();
        if (thread != null) thread.interrupt();
    }

    void closeQuiet() { try { Socket s = sock; if (s != null) s.close(); } catch (Exception ignored) {} }

    @Override public void run() {
        while (!stopped) {
            try { session(); }
            catch (Exception e) { if (!stopped) { out.log("Connection lost: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); if (System.getProperty("afk.debug") != null) e.printStackTrace(); } }
            closeQuiet();
            if (stopped) break;
            out.log("Reconnecting in " + (retryMs / 1000) + " seconds...");
            try { Thread.sleep(retryMs); } catch (InterruptedException ie) { break; }
        }
        out.log("Bot stopped.");
    }

    // ---------- one connection ----------
    int state, compression;
    InputStream in; OutputStream os;
    ScheduledExecutorService sched;
    boolean spawned, loadedSent, registerPrompt, loginPrompt, authDone, registerSent, loginSent, surviveSent, surviveAnnounced;

    void session() throws Exception {
        state = ST_LOGIN; compression = -1;
        spawned = loadedSent = registerPrompt = loginPrompt = authDone = registerSent = loginSent = surviveSent = surviveAnnounced = false;
        out.log("Connecting to " + host + ":" + port + " as " + user + "...");
        Socket s = new Socket();
        sock = s;
        s.connect(new InetSocketAddress(host, port), 15000);
        s.setSoTimeout(60000);
        s.setTcpNoDelay(true);
        in = new BufferedInputStream(s.getInputStream());
        os = new BufferedOutputStream(s.getOutputStream());
        sched = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "bot-timer"); t.setDaemon(true); return t; });
        try {
            W h = new W(); h.varInt(PROTOCOL); h.string(host); h.u16(port); h.varInt(2);
            send(0x00, h);
            W l = new W(); l.string(user); l.uuid(offlineUuid(user));
            send(0x00, l);
            while (!stopped) {
                Buf p = readPacket();
                int id = p.varInt();
                if (System.getProperty("afk.debug") != null) System.out.println("[dbg] state=" + state + " id=0x" + Integer.toHexString(id) + " len=" + p.a.length);
                boolean keep;
                try { keep = handle(id, p); }
                catch (RuntimeException re) { if (System.getProperty("afk.debug") != null) re.printStackTrace(); keep = true; /* skip a packet we can't parse */ }
                if (!keep) return;
            }
        } finally {
            sched.shutdownNow();
        }
    }

    /** @return false when the session should end (kicked). */
    boolean handle(int id, Buf p) throws IOException {
        switch (state) {
            case ST_LOGIN:
                switch (id) {
                    case 0x00: out.log("Login refused: " + clean(jsonText(p.string()))); return false;
                    case 0x01: out.log("This server needs a real Microsoft account (online mode). Bot can't join like this."); return false;
                    case 0x02: send(0x03, new W()); state = ST_CONFIG; out.log("Logged in, loading server..."); break;
                    case 0x03: compression = p.varInt(); break;
                    case 0x04: { int mid = p.varInt(); W w = new W(); w.varInt(mid); w.bool(false); send(0x02, w); break; }
                    case 0x05: { String idf = p.string(); W w = new W(); w.string(idf); w.bool(false); send(0x04, w); break; }
                }
                break;
            case ST_CONFIG:
                switch (id) {
                    case 0x00: { String idf = p.string(); W w = new W(); w.string(idf); w.bool(false); send(0x01, w); break; }
                    case 0x02: out.log("Disconnected: " + clean(nbtText(p))); return false;
                    case 0x03: send(0x03, new W()); state = ST_PLAY; break;
                    case 0x04: { W w = new W(); w.i64(p.i64()); send(0x04, w); break; }
                    case 0x05: { W w = new W(); w.i32(p.i32()); send(0x05, w); break; }
                    case 0x0e: { W w = new W(); w.varInt(0); send(0x07, w); break; }
                    case 0x13: send(0x09, new W()); break;
                }
                break;
            case ST_PLAY:
                switch (id) {
                    case 0x20: out.log("Kicked: " + clean(nbtText(p))); return false;
                    case 0x2c: { W w = new W(); w.i64(p.i64()); send(0x1c, w); break; }
                    case 0x3d: { W w = new W(); w.i32(p.i32()); send(0x2d, w); break; }
                    case 0x0b: { p.varInt(); W w = new W(); w.f32(9.0f); send(0x0b, w); break; }
                    case 0x31: loadedSent = false; out.log("Joined a server world."); onWorldChange(); break;
                    case 0x52: onWorldChange(); break;
                    case 0x76: send(0x10, new W()); state = ST_CONFIG; onWorldChange(); break;
                    case 0x48: {
                        int tid = p.varInt();
                        W w = new W(); w.varInt(tid); send(0x00, w);
                        if (!loadedSent) { loadedSent = true; send(0x2c, new W()); }
                        if (!spawned) { spawned = true; onSpawn(); }
                        break;
                    }
                    case 0x79: {
                        String t = clean(nbtText(p));
                        boolean actionBar = p.remaining() > 0 && p.bool();
                        if (!actionBar && !t.isEmpty()) onChat(t);
                        break;
                    }
                }
                break;
        }
        return true;
    }

    void onWorldChange() {
        if (surviveSent && !surviveAnnounced) { surviveAnnounced = true; out.log(user + " has been sent to survival."); }
    }

    void onSpawn() {
        out.log(user + " has spawned into the lobby.");
        sched.schedule(this::doAuth, loginDelayMs, TimeUnit.MILLISECONDS);
    }

    void doAuth() {
        try {
            if (registerPrompt && !loginPrompt) sendRegister(); else sendLogin();
            authDone = true;
            sched.schedule(() -> {
                try {
                    out.log("Sending /server survival");
                    command("server survival");
                    surviveSent = true;
                } catch (IOException e) { out.log("Could not send command: " + e.getMessage()); }
            }, survivalDelayMs, TimeUnit.MILLISECONDS);
        } catch (IOException e) { out.log("Could not send command: " + e.getMessage()); }
    }

    synchronized void sendRegister() throws IOException { registerSent = true; out.log("Sending /register"); command("register " + pass + " " + pass); }
    synchronized void sendLogin() throws IOException { loginSent = true; out.log("Sending /login"); command("login " + pass); }

    void onChat(String text) {
        out.log("Server: " + text);
        String l = text.toLowerCase();
        if (l.contains("/register")) registerPrompt = true;
        if (l.contains("/login")) loginPrompt = true;
        if (!authDone) return;
        try {
            if (!registerSent && l.contains("/register") && !l.contains("/login")) sendRegister();
            else if (registerSent && !loginSent && l.contains("/login") && l.contains("already")) sendLogin();
            if (surviveSent && !surviveAnnounced && l.contains("survival") && (l.contains("connect") || l.contains("send") || l.contains("transfer"))) {
                surviveAnnounced = true; out.log(user + " has been sent to survival.");
            }
        } catch (IOException e) { out.log("Could not send command: " + e.getMessage()); }
    }

    void command(String c) throws IOException { W w = new W(); w.string(c); send(0x07, w); }

    // ---------- packet io ----------
    synchronized void send(int id, W body) throws IOException {
        W payload = new W(); payload.varInt(id); payload.write(body.toByteArray());
        byte[] data = payload.toByteArray();
        W frame = new W();
        if (compression >= 0) {
            if (data.length >= compression) {
                Deflater d = new Deflater(); d.setInput(data); d.finish();
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[4096];
                while (!d.finished()) { int n = d.deflate(buf); bo.write(buf, 0, n); }
                d.end();
                W inner = new W(); inner.varInt(data.length); inner.write(bo.toByteArray());
                frame.varInt(inner.size()); frame.write(inner.toByteArray());
            } else {
                frame.varInt(data.length + 1); frame.varInt(0); frame.write(data);
            }
        } else { frame.varInt(data.length); frame.write(data); }
        os.write(frame.toByteArray()); os.flush();
    }

    Buf readPacket() throws IOException {
        int len = readVarInt(in);
        if (len <= 0 || len > 16 * 1024 * 1024) throw new IOException("bad packet length " + len);
        byte[] raw = new byte[len];
        int off = 0;
        while (off < len) { int n = in.read(raw, off, len - off); if (n < 0) throw new EOFException("Server closed the connection"); off += n; }
        if (compression < 0) return new Buf(raw);
        Buf b = new Buf(raw);
        int dl = b.varInt();
        if (dl == 0) return new Buf(java.util.Arrays.copyOfRange(raw, b.p, raw.length));
        Inflater inf = new Inflater();
        inf.setInput(raw, b.p, raw.length - b.p);
        byte[] res = new byte[dl];
        try {
            int o = 0;
            while (o < dl) { int n = inf.inflate(res, o, dl - o); if (n == 0 && (inf.finished() || inf.needsInput())) break; o += n; }
        } catch (java.util.zip.DataFormatException e) { throw new IOException("bad compressed packet"); }
        finally { inf.end(); }
        return new Buf(res);
    }

    static int readVarInt(InputStream in) throws IOException {
        int v = 0, sh = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("Server closed the connection");
            v |= (b & 0x7f) << sh;
            if ((b & 0x80) == 0) return v;
            sh += 7; if (sh > 35) throw new IOException("varint too big");
        }
    }

    static byte[] offlineUuid(String name) {
        UUID u = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        ByteBuffer bb = ByteBuffer.allocate(16); bb.putLong(u.getMostSignificantBits()); bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    static String clean(String s) { return s == null ? "" : s.replaceAll("\u00a7.", "").trim(); }

    static String jsonText(String json) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(?:text|translate)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        while (m.find()) sb.append(m.group(1));
        return sb.length() > 0 ? sb.toString() : json;
    }

    /** Reads an anonymous NBT text component and returns its visible text. */
    static String nbtText(Buf p) {
        StringBuilder sb = new StringBuilder();
        try { int t = p.u8(); nbtValue(p, t, null, sb); } catch (RuntimeException e) { /* ignore */ }
        return sb.toString();
    }

    static void nbtValue(Buf p, int type, String key, StringBuilder sb) {
        switch (type) {
            case 0: return;
            case 1: p.p += 1; return;
            case 2: p.p += 2; return;
            case 3: p.p += 4; return;
            case 4: p.p += 8; return;
            case 5: p.p += 4; return;
            case 6: p.p += 8; return;
            case 7: { int n = p.i32(); p.p += n; return; }
            case 8: {
                int n = p.u16(); String s = new String(p.a, p.p, n, StandardCharsets.UTF_8); p.p += n;
                if (key == null || key.equals("text") || key.equals("translate") || key.equals("extra") || key.equals("with")) sb.append(s);
                return;
            }
            case 9: { int et = p.u8(); int n = p.i32(); for (int i = 0; i < n; i++) nbtValue(p, et, key, sb); return; }
            case 10: {
                while (true) {
                    int t = p.u8(); if (t == 0) return;
                    int kl = p.u16(); String k = new String(p.a, p.p, kl, StandardCharsets.UTF_8); p.p += kl;
                    nbtValue(p, t, k, sb);
                }
            }
            case 11: { int n = p.i32(); p.p += 4 * n; return; }
            case 12: { int n = p.i32(); p.p += 8 * n; return; }
            default: throw new RuntimeException("bad nbt");
        }
    }

    // ---------- helpers ----------
    static class Buf {
        final byte[] a; int p;
        Buf(byte[] a) { this.a = a; }
        int remaining() { return a.length - p; }
        int u8() { return a[p++] & 0xff; }
        boolean bool() { return a[p++] != 0; }
        int u16() { int v = ((a[p] & 0xff) << 8) | (a[p + 1] & 0xff); p += 2; return v; }
        int i32() { int v = ByteBuffer.wrap(a, p, 4).getInt(); p += 4; return v; }
        long i64() { long v = ByteBuffer.wrap(a, p, 8).getLong(); p += 8; return v; }
        int varInt() { int v = 0, sh = 0; while (true) { int b = u8(); v |= (b & 0x7f) << sh; if ((b & 0x80) == 0) return v; sh += 7; if (sh > 35) throw new RuntimeException("varint"); } }
        String string() { int n = varInt(); String s = new String(a, p, n, StandardCharsets.UTF_8); p += n; return s; }
    }

    static class W extends ByteArrayOutputStream {
        void varInt(int v) { while ((v & ~0x7f) != 0) { write((v & 0x7f) | 0x80); v >>>= 7; } write(v); }
        void string(String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); varInt(b.length); write(b, 0, b.length); }
        void u16(int v) { write((v >> 8) & 0xff); write(v & 0xff); }
        void i32(int v) { write(ByteBuffer.allocate(4).putInt(v).array(), 0, 4); }
        void i64(long v) { write(ByteBuffer.allocate(8).putLong(v).array(), 0, 8); }
        void f32(float v) { write(ByteBuffer.allocate(4).putFloat(v).array(), 0, 4); }
        void bool(boolean b) { write(b ? 1 : 0); }
        void uuid(byte[] b) { write(b, 0, b.length); }
    }
}
