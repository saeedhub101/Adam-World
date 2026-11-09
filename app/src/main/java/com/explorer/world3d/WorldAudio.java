package com.explorer.world3d;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;

/**
 * Lightweight procedural world audio. No large audio assets are bundled:
 * engine, rotor, wind and traffic beds are synthesized on a dedicated audio thread.
 */
final class WorldAudio {
    private static final int RATE = 22050;
    private static final int FRAMES = 512;
    private volatile boolean running = true;
    private volatile boolean enabled = true;
    private volatile float master = 0.82f, engineVolume = 0.9f, ambienceVolume = 0.55f, effectsVolume = 0.85f;
    private volatile boolean driving, helicopter, airplane, moving;
    private volatile float speed, throttle, wind, traffic, trafficPan, playerPan;
    private volatile boolean horn;
    private volatile int transientEffect;
    private AudioTrack track;
    private final Thread thread;
    private double phase, rotorPhase, trafficPhase, windPhase, stepPhase;
    private long noiseState = 0x1234abcdL;

    WorldAudio() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int buffer = Math.max(FRAMES * 4 * 4, min * 2);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build();
        track.play();
        thread = new Thread(this::render, "AdamWorld-Audio");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    void update(Game g) {
        if (g == null) return;
        driving = g.driving;
        helicopter = g.driving && g.vehicleType == ProceduralVehicleModels.HELICOPTER;
        airplane = g.driving && g.vehicleType == ProceduralVehicleModels.AIRPLANE;
        speed = Math.min(1f, Math.abs(g.cv) / (helicopter ? 48f : airplane ? 92f : 32f));
        throttle = Math.max(Math.abs(g.sy), g.engineThrottle);
        moving = g.driving ? speed > 0.025f : Math.abs(g.sx) + Math.abs(g.sy) > 0.15f;
        wind = Math.min(1f, speed * (airplane ? 1.0f : 0.65f) + (g.altitude > 1f ? 0.25f : 0f));
        float nearest = 0f, pan = 0f;
        float px = g.focusX(), pz = g.focusZ();
        for (Game.Agent a : g.agents) {
            if (!a.car) continue;
            float dx = a.x - px, dz = a.z - pz;
            float dist = (float)Math.sqrt(dx * dx + dz * dz);
            float amount = 1f / (1f + dist * 0.20f);
            if (amount > nearest) {
                nearest = amount;
                float angle = (float)Math.atan2(dx, dz) - (g.driving ? g.cyaw : g.pyaw);
                pan = (float)Math.sin(angle);
            }
        }
        traffic = nearest;
        trafficPan = pan;
        playerPan = 0f;
    }

    void playHorn() { horn = true; }
    void playEffect(int effect) { transientEffect = effect; }
    void setEnabled(boolean value) { enabled = value; }
    void setVolumes(float masterValue, float engine, float ambience, float effects) {
        master = clamp(masterValue); engineVolume = clamp(engine);
        ambienceVolume = clamp(ambience); effectsVolume = clamp(effects);
    }
    void pause() { if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause(); }
    void resume() { if (track != null && running && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) { track.flush(); track.play(); } }

    private void render() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] out = new short[FRAMES * 2];
        while (running) {
            boolean audible = enabled;
            for (int i = 0; i < FRAMES; i++) {
                double engine = 0, rotor = 0, ambience = 0, fx = 0;
                float localSpeed = speed;
                if (audible && driving) {
                    double rpm = 48.0 + localSpeed * 150.0 + throttle * 42.0;
                    phase += (2.0 * Math.PI * rpm) / RATE;
                    engine = Math.sin(phase) * 0.30 + Math.sin(phase * 2.01) * 0.13
                            + Math.sin(phase * 3.97) * 0.055;
                    if (helicopter) {
                        rotorPhase += 2.0 * Math.PI * (8.0 + localSpeed * 5.0) / RATE;
                        engine = Math.sin(phase) * 0.10;
                        rotor = Math.sin(rotorPhase) * 0.30 + Math.sin(rotorPhase * 2.0) * 0.10;
                    } else if (airplane) {
                        engine = Math.sin(phase) * 0.16 + Math.sin(phase * 1.51) * 0.12;
                    }
                }
                if (audible) {
                    windPhase += 2.0 * Math.PI * 73.0 / RATE;
                    double n = noise();
                    ambience = n * (0.025 + wind * 0.075) * ambienceVolume;
                    ambience += Math.sin(windPhase) * (0.006 + wind * 0.018);
                    if (traffic > 0.01f) {
                        trafficPhase += 2.0 * Math.PI * (62.0 + traffic * 95.0) / RATE;
                        ambience += (Math.sin(trafficPhase) * 0.10 + Math.sin(trafficPhase * 2.2) * 0.035)
                                * traffic * ambienceVolume;
                    }
                    if (moving && !driving) {
                        stepPhase += 2.0 * Math.PI * 2.1 / RATE;
                        double step = Math.pow(Math.max(0, Math.sin(stepPhase)), 18);
                        ambience += step * 0.10 * effectsVolume;
                    }
                    if (horn) {
                        fx = (Math.sin(2.0 * Math.PI * 440.0 * i / RATE)
                                + Math.sin(2.0 * Math.PI * 554.0 * i / RATE)) * 0.18;
                        if (i == FRAMES - 1) horn = false;
                    }
                }
                double base = audible ? (engine * engineVolume + rotor * engineVolume + ambience + fx) * master : 0;
                double left = base, right = base;
                if (traffic > 0.01f) {
                    left += Math.max(0, -trafficPan) * 0.035 * traffic * master;
                    right += Math.max(0, trafficPan) * 0.035 * traffic * master;
                }
                out[i * 2] = sample(left);
                out[i * 2 + 1] = sample(right);
            }
            if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                int written = track.write(out, 0, out.length);
                if (written < 0) { try { Thread.sleep(20); } catch (InterruptedException ignored) { } }
            } else {
                try { Thread.sleep(30); } catch (InterruptedException ignored) { }
            }
        }
    }

    private double noise() {
        noiseState ^= noiseState << 13; noiseState ^= noiseState >>> 7; noiseState ^= noiseState << 17;
        return ((noiseState & 0xffff) / 32767.5) - 1.0;
    }
    private static short sample(double v) { return (short)(Math.max(-1.0, Math.min(1.0, v)) * 32767); }
    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    void release() {
        running = false;
        thread.interrupt();
        try { thread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        if (track != null) { try { track.pause(); track.flush(); track.release(); } catch (Exception ignored) { } track = null; }
    }
}
