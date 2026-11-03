package com.ludox.game.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.util.Log;

import com.ludox.game.LudoApp;
import com.ludox.game.net.Api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live voice with friends in the same room. The microphone is off until the player turns
 * it on; speech is compressed to AAC (16 kHz mono, ~16 kbps) and sent to the server in
 * packets of about a tenth of a second, which forwards them to the player's friends in the
 * room only. Each friend's voice plays through its own decoder; any friend can be muted.
 *
 * No echo: while the mic is on the phone is in call mode and friends play on the call
 * stream, so the phone's echo canceller can take their voices back out of the mic. On top
 * of that, a gate sends only speech (silence costs nothing on a weak network) and needs a
 * louder voice while a friend is talking, so what leaks from the speaker is never sent
 * back. Playback keeps a small jitter buffer so a bumpy network doesn't chop words.
 *
 * Packet: [frame count u8] then per frame [length u16, big-endian][AAC frame].
 */
public final class VoiceChat {
    public interface Listener {
        /** Mic, mutes, friends or who is talking changed (called on the main thread). */
        void onVoiceChanged();
    }

    private static final String TAG = "Voice";
    private static final int RATE = 16_000;
    private static final String AAC = MediaFormat.MIMETYPE_AUDIO_AAC;
    /** AudioSpecificConfig for AAC-LC, 16 kHz, mono (what the encoder produces). */
    private static final byte[] CSD = {0x14, 0x08};
    private static final long PACKET_MS = 100;
    private static final int BIT_RATE = 16_000;
    /** Mic loudness that counts as speech, normally and while a friend's voice is playing. */
    private static final int GATE = 700, GATE_ECHO = 2200;
    /** Keep sending this long after the last word, so endings aren't clipped. */
    private static final long GATE_HOLD_MS = 450;
    /** A friend's voice counts as "playing" this long after a loud bit came out. */
    private static final long ECHO_TAIL_MS = 300;
    /** After a pause, wait up to this long for a second packet before playing (jitter). */
    private static final long PREBUFFER_MS = 120;
    private static final String PREF_MUTED = "voice_muted";

    private static VoiceChat instance;

    public static synchronized VoiceChat get() {
        if (instance == null) instance = new VoiceChat(LudoApp.get());
        return instance;
    }

    private final LudoApp app;
    private final Set<String> friends = ConcurrentHashMap.newKeySet();
    /** Public ids of friends this player muted (kept across matches). */
    private final Set<String> muted = ConcurrentHashMap.newKeySet();
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHeard = new ConcurrentHashMap<>();
    private final Set<Listener> listeners = ConcurrentHashMap.newKeySet();
    private final AtomicInteger friendsAsk = new AtomicInteger();
    private volatile Capture capture;
    private volatile long lastSpoke;
    /** Last time a friend's voice came out of the speaker loudly (for the echo gate). */
    private volatile long lastPlayedLoud;
    /** Audio mode to put back when the mic goes off (-1: not in call mode). */
    private int prevMode = -1;

    private VoiceChat(LudoApp app) {
        this.app = app;
        muted.addAll(app.prefs.getStringSet(PREF_MUTED, new HashSet<>()));
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        LudoApp.MAIN.post(() -> {
            for (Listener l : listeners) l.onVoiceChanged();
        });
    }

    // ------------------------------------------------------------------ who

    /** Read my friend list (voice only reaches friends). */
    public void refreshFriends() {
        refreshFriends(0);
    }

    private void refreshFriends(int attempt) {
        // Only the newest read counts: on a slow network an older answer can land last.
        int ask = friendsAsk.incrementAndGet();
        app.api.get("/friends", new Api.Cb(null) {
            @Override
            public void ok(JSONObject r) {
                if (ask != friendsAsk.get()) return;
                JSONArray arr = r.optJSONArray("friends");
                Set<String> next = new HashSet<>();
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject f = arr.optJSONObject(i);
                    if (f != null) next.add(f.optString("publicId"));
                }
                friends.clear();
                friends.addAll(next);
                changed();
            }

            @Override
            public void err(Api.Error e) {
                // Keep the last list, and try again a little later while in a room.
                if (ask == friendsAsk.get() && attempt < 3 && app.socket.room != null) {
                    LudoApp.MAIN.postDelayed(() -> refreshFriends(attempt + 1), 1500L * (attempt + 1));
                }
            }
        });
    }

    /** A seat whose player is my friend (so we can talk). */
    public boolean isFriend(JSONObject seat) {
        if (seat == null || "ai".equals(seat.optString("kind")) || seat.isNull("publicId")) return false;
        return friends.contains(seat.optString("publicId"));
    }

    /** At least one friend sits in my room: voice is available. */
    public boolean available() {
        JSONObject room = app.socket.room;
        JSONArray seats = room != null ? room.optJSONArray("seats") : null;
        String mine = app.socket.mySeatId();
        for (int i = 0; seats != null && i < seats.length(); i++) {
            JSONObject s = seats.optJSONObject(i);
            if (s != null && !s.optString("id").equals(mine) && isFriend(s)) return true;
        }
        return false;
    }

    public boolean isMuted(String publicId) {
        return publicId != null && muted.contains(publicId);
    }

    public void toggleMute(String publicId) {
        if (publicId == null) return;
        if (!muted.remove(publicId)) muted.add(publicId);
        app.prefs.edit().putStringSet(PREF_MUTED, new HashSet<>(muted)).apply();
        changed();
    }

    /** This seat was heard in the last moment (for the talking glow). */
    public boolean speaking(String seatId) {
        Long t = lastHeard.get(seatId);
        return t != null && System.currentTimeMillis() - t < 450;
    }

    public boolean meSpeaking() {
        return micOn() && System.currentTimeMillis() - lastSpoke < 350;
    }

    // ------------------------------------------------------------------ microphone

    public boolean micOn() {
        return capture != null;
    }

    /** Turn my microphone on or off (the caller checked the record permission). */
    public synchronized void setMic(boolean on) {
        if (on == micOn()) return;
        if (on) {
            callMode(true);
            Capture c = new Capture();
            capture = c;
            c.start();
        } else {
            Capture c = capture;
            capture = null;
            if (c != null) c.running = false;
            callMode(false);
        }
        changed();
    }

    /**
     * Call mode while the mic is on: the echo canceller only knows about sound on the call
     * stream. Sound stays on the loudspeaker unless a headset is plugged in.
     */
    private synchronized void callMode(boolean on) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (on && prevMode < 0) {
                prevMode = am.getMode();
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                boolean headset = false;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                            || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) headset = true;
                }
                if (!headset) {
                    if (Build.VERSION.SDK_INT >= 31) {
                        for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                am.setCommunicationDevice(d);
                                break;
                            }
                        }
                    } else {
                        am.setSpeakerphoneOn(true);
                    }
                }
            } else if (!on && prevMode >= 0) {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                else am.setSpeakerphoneOn(false);
                am.setMode(prevMode);
                prevMode = -1;
            }
        } catch (Exception e) {
            Log.w(TAG, "audio mode", e);
        }
    }

    /** Leaving the room: mic off, stop every voice. */
    public void stopAll() {
        setMic(false);
        for (Player p : players.values()) p.running = false;
        players.clear();
        lastHeard.clear();
    }

    /** The room changed: drop voices of players who left; stop everything without a room. */
    public void syncRoom() {
        JSONObject room = app.socket.room;
        if (room == null) {
            stopAll();
            return;
        }
        Set<String> seats = new HashSet<>();
        JSONArray arr = room.optJSONArray("seats");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s != null) seats.add(s.optString("id"));
        }
        for (Map.Entry<String, Player> e : players.entrySet()) {
            if (!seats.contains(e.getKey())) {
                e.getValue().running = false;
                players.remove(e.getKey());
            }
        }
        if (!available() && micOn()) setMic(false);
    }

    // ------------------------------------------------------------------ incoming

    /** A friend's packet from the server (socket thread). */
    public void onPacket(String seatId, byte[] data) {
        if (seatId == null || data == null || data.length < 4) return;
        JSONObject seat = seat(seatId);
        if (seat == null || isMuted(seat.optString("publicId"))) return;
        Player p = players.get(seatId);
        if (p == null) {
            p = new Player();
            players.put(seatId, p);
            p.start();
        }
        p.offer(data);
        boolean was = speaking(seatId);
        lastHeard.put(seatId, System.currentTimeMillis());
        if (!was) changed();
    }

    private JSONObject seat(String seatId) {
        JSONObject room = app.socket.room;
        JSONArray arr = room != null ? room.optJSONArray("seats") : null;
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s != null && seatId.equals(s.optString("id"))) return s;
        }
        return null;
    }

    private static List<byte[]> unpack(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        int n = data[0] & 0xff, at = 1;
        for (int i = 0; i < n && at + 2 <= data.length; i++) {
            int len = ((data[at] & 0xff) << 8) | (data[at + 1] & 0xff);
            at += 2;
            if (len <= 0 || at + len > data.length) break;
            byte[] f = new byte[len];
            System.arraycopy(data, at, f, 0, len);
            out.add(f);
            at += len;
        }
        return out;
    }

    // ------------------------------------------------------------------ threads

    /** Records, encodes and sends my voice while the mic is on. */
    private final class Capture extends Thread {
        volatile boolean running = true;

        Capture() {
            super("voice-capture");
        }

        @Override
        public void run() {
            AudioRecord rec = null;
            MediaCodec enc = null;
            AcousticEchoCanceler aec = null;
            NoiseSuppressor ns = null;
            AutomaticGainControl agc = null;
            try {
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 6400));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("mic unavailable");
                // Keep the friends' voices (from the speaker) out of the mic where the phone can.
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                    if (aec != null) aec.setEnabled(true);
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(rec.getAudioSessionId());
                    if (ns != null) ns.setEnabled(true);
                }
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(rec.getAudioSessionId());
                    if (agc != null) agc.setEnabled(true);
                }
                MediaFormat f = MediaFormat.createAudioFormat(AAC, RATE, 1);
                f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                f.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
                enc = MediaCodec.createEncoderByType(AAC);
                enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                enc.start();
                rec.startRecording();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                List<byte[]> frames = new ArrayList<>();
                long pts = 0, lastSend = System.currentTimeMillis();
                while (running) {
                    int in = enc.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buf = enc.getInputBuffer(in);
                        int n = 0;
                        if (buf != null) {
                            buf.clear();
                            n = rec.read(buf, Math.min(buf.remaining(), 1280));
                        }
                        if (n > 0) {
                            // While a friend's voice is coming out of the speaker, only a
                            // clearly louder (nearer) voice opens the gate: never their echo.
                            long now = System.currentTimeMillis();
                            int gate = now - lastPlayedLoud < ECHO_TAIL_MS ? GATE_ECHO : GATE;
                            if (level(buf, n) > gate) lastSpoke = now;
                            enc.queueInputBuffer(in, 0, n, pts, 0);
                            pts += n / 2 * 1_000_000L / RATE;
                        } else {
                            enc.queueInputBuffer(in, 0, 0, pts, 0);
                        }
                    }
                    // Silence (and echo) is still encoded, to keep the encoder warm, but not sent.
                    boolean open = System.currentTimeMillis() - lastSpoke < GATE_HOLD_MS;
                    int out;
                    while ((out = enc.dequeueOutputBuffer(info, 0)) >= 0) {
                        if (open && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            ByteBuffer ob = enc.getOutputBuffer(out);
                            if (ob != null) {
                                byte[] fr = new byte[info.size];
                                ob.position(info.offset);
                                ob.get(fr);
                                frames.add(fr);
                            }
                        }
                        enc.releaseOutputBuffer(out, false);
                    }
                    long now = System.currentTimeMillis();
                    if (!frames.isEmpty() && (now - lastSend >= PACKET_MS || frames.size() >= 12)) {
                        send(frames);
                        frames.clear();
                        lastSend = now;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "microphone stopped", e);
                if (capture == this) {
                    capture = null;
                    callMode(false);
                    changed();
                }
            } finally {
                try {
                    if (rec != null) {
                        rec.stop();
                        rec.release();
                    }
                } catch (Exception ignored) {
                }
                try {
                    if (enc != null) {
                        enc.stop();
                        enc.release();
                    }
                } catch (Exception ignored) {
                }
                if (aec != null) aec.release();
                if (ns != null) ns.release();
                if (agc != null) agc.release();
            }
        }

        private void send(List<byte[]> frames) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int n = Math.min(frames.size(), 255);
            bo.write(n);
            for (int i = 0; i < n; i++) {
                byte[] f = frames.get(i);
                bo.write((f.length >> 8) & 0xff);
                bo.write(f.length & 0xff);
                bo.write(f, 0, f.length);
            }
            app.socket.emitRaw("voice:frame", (Object) bo.toByteArray());
        }

        /** Rough loudness of the samples just read (to light up "talking"). */
        private int level(ByteBuffer buf, int n) {
            // AudioRecord wrote the samples from position 0 (and left the position alone).
            ByteBuffer d = buf.duplicate();
            d.position(0);
            ShortBuffer sb = d.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            long sum = 0;
            int count = 0;
            for (int i = 0; i < n / 2; i += 8) {
                int v = sb.get(i);
                sum += (long) v * v;
                count++;
            }
            return count == 0 ? 0 : (int) Math.sqrt(sum / (double) count);
        }
    }

    /** Decodes and plays one friend's voice. */
    private final class Player extends Thread {
        volatile boolean running = true;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

        Player() {
            super("voice-play");
        }

        void offer(byte[] packet) {
            // Fall behind by more than ~0.6 s: skip ahead so the voice stays live.
            while (queue.size() > 6) queue.poll();
            queue.offer(packet);
        }

        @Override
        public void run() {
            MediaCodec dec = null;
            AudioTrack track = null;
            try {
                MediaFormat f = MediaFormat.createAudioFormat(AAC, RATE, 1);
                f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
                f.setByteBuffer("csd-0", ByteBuffer.wrap(CSD));
                dec = MediaCodec.createDecoderByType(AAC);
                dec.configure(f, null, null, 0);
                dec.start();
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                // The call stream: the echo canceller removes it from the mic.
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(Math.max(min * 2, 9600))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                track.play();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long pts = 0;
                byte[] pcm = new byte[8192];
                boolean idle = true;
                List<byte[]> batch = new ArrayList<>();
                while (running) {
                    byte[] packet = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (packet == null) {
                        idle = true;
                        continue;
                    }
                    batch.clear();
                    batch.add(packet);
                    if (idle) {
                        // First words after a pause: hold back a moment so a late packet
                        // doesn't leave a hole in the middle of them.
                        byte[] next = queue.poll(PREBUFFER_MS, TimeUnit.MILLISECONDS);
                        if (next != null) batch.add(next);
                        idle = false;
                    }
                    List<byte[]> frames = new ArrayList<>();
                    for (byte[] b : batch) frames.addAll(unpack(b));
                    for (byte[] frame : frames) {
                        int in = dec.dequeueInputBuffer(20_000);
                        if (in < 0) continue;
                        ByteBuffer ib = dec.getInputBuffer(in);
                        if (ib == null) continue;
                        ib.clear();
                        ib.put(frame);
                        dec.queueInputBuffer(in, 0, frame.length, pts, 0);
                        pts += 1024 * 1_000_000L / RATE;
                        int out;
                        while ((out = dec.dequeueOutputBuffer(info, 5_000)) >= 0) {
                            ByteBuffer ob = dec.getOutputBuffer(out);
                            if (ob != null && info.size > 0) {
                                if (pcm.length < info.size) pcm = new byte[info.size];
                                ob.position(info.offset);
                                ob.get(pcm, 0, info.size);
                                if (loud(pcm, info.size)) lastPlayedLoud = System.currentTimeMillis();
                                track.write(pcm, 0, info.size);
                            }
                            dec.releaseOutputBuffer(out, false);
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                // Stopping.
            } catch (Exception e) {
                Log.w(TAG, "voice playback stopped", e);
            } finally {
                try {
                    if (track != null) {
                        track.stop();
                        track.release();
                    }
                } catch (Exception ignored) {
                }
                try {
                    if (dec != null) {
                        dec.stop();
                        dec.release();
                    }
                } catch (Exception ignored) {
                }
            }
        }

        /** Decoded speech (16-bit little-endian) loud enough to echo into a mic. */
        private boolean loud(byte[] pcm, int size) {
            long sum = 0;
            int count = 0;
            for (int i = 0; i + 1 < size; i += 16) {
                int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                sum += (long) v * v;
                count++;
            }
            return count > 0 && Math.sqrt(sum / (double) count) > 400;
        }
    }
}
