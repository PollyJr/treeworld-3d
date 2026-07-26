package com.pollyjr.treegame;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Procedural audio engine — no assets needed.
 * Synthesizes SFX (jump/coin/crash/powerup) and ambient loops
 * (wind, day birds, night crickets) in real time via AudioTrack.
 */
public class SoundSynth {
    private static final int SR = 22050;
    private AudioTrack track;
    private Thread thread;
    private volatile boolean running = false;
    public volatile boolean enabled = true;
    /** 0=day 1=sunset 2=night 3=dawn — drives ambient sounds. */
    public volatile int phase = 0;

    private final ConcurrentLinkedQueue<float[]> queue = new ConcurrentLinkedQueue<>();
    private float[] active;
    private int activePos = 0;
    private final Random rng = new Random();
    private double nextChirp = 0;

    public void start() {
        if (running) return;
        running = true;
        int bufSize = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(bufSize, SR))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        thread = new Thread(this::mixLoop, "synth");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (track != null) { try { track.stop(); track.release(); } catch (Exception ignored) {} }
    }

    private void mixLoop() {
        short[] buf = new short[1024];
        double windLp = 0;
        double t = 0;
        while (running) {
            for (int i = 0; i < buf.length; i++) {
                t += 1.0 / SR;
                double sample = 0;
                if (enabled) {
                    // ambient wind: low-passed noise with slow swell
                    double noise = rng.nextDouble() * 2 - 1;
                    windLp += 0.03 * (noise - windLp);
                    double swell = 0.5 + 0.5 * Math.sin(t * 0.4);
                    sample += windLp * 0.5 * (0.15 + 0.1 * swell);

                    // chirps by day, crickets by night
                    if (t > nextChirp) {
                        if (phase == 2) queue.offer(cricket());
                        else if (phase == 0) queue.offer(chirp());
                        nextChirp = t + 2.5 + rng.nextDouble() * 6;
                    }

                    // active sfx + queued
                    if (active != null) {
                        sample += active[activePos++];
                        if (activePos >= active.length) { active = null; activePos = 0; }
                    }
                    if (active == null) {
                        float[] next = queue.poll();
                        if (next != null && next.length > 0) { active = next; activePos = 1; sample += next[0]; }
                    }
                }
                sample = Math.max(-1, Math.min(1, sample));
                buf[i] = (short)(sample * 32767);
            }
            track.write(buf, 0, buf.length);
        }
    }

    // ------------------------------------------------------------ SFX library
    public void jump()   { queue.offer(sweep(300, 700, 0.18, 0.35)); }
    public void slide()  { queue.offer(noiseBurst(0.2, 0.25)); }
    public void coin()   { queue.offer(arp(new double[]{1320, 1760}, 0.07, 0.4)); }
    public void orb()    { queue.offer(arp(new double[]{880, 1175, 1568}, 0.08, 0.45)); }
    public void power()  { queue.offer(arp(new double[]{523, 659, 784, 1047}, 0.09, 0.5)); }
    public void crash()  { queue.offer(crashSound()); }
    public void shieldHit() { queue.offer(arp(new double[]{600, 350}, 0.12, 0.5)); }
    public void milestone() { queue.offer(arp(new double[]{784, 988, 1175, 1568}, 0.1, 0.4)); }

    private static float[] sweep(double f0, double f1, double dur, double vol) {
        int n = (int)(SR * dur);
        float[] out = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double f = f0 + (f1 - f0) * i / n;
            phase += 2 * Math.PI * f / SR;
            double env = Math.sin(Math.PI * i / n);
            out[i] = (float)(Math.sin(phase) * env * vol);
        }
        return out;
    }

    private static float[] arp(double[] freqs, double noteDur, double vol) {
        int nd = (int)(SR * noteDur);
        float[] out = new float[nd * freqs.length];
        for (int k = 0; k < freqs.length; k++) {
            for (int i = 0; i < nd; i++) {
                double env = Math.exp(-4.0 * i / nd);
                out[k * nd + i] = (float)(Math.sin(2 * Math.PI * freqs[k] * i / (double)SR) * env * vol);
            }
        }
        return out;
    }

    private float[] noiseBurst(double dur, double vol) {
        int n = (int)(SR * dur);
        float[] out = new float[n];
        double lp = 0;
        for (int i = 0; i < n; i++) {
            lp += 0.2 * (rng.nextDouble() * 2 - 1 - lp);
            out[i] = (float)(lp * vol * (1 - (double)i / n));
        }
        return out;
    }

    private float[] crashSound() {
        int n = (int)(SR * 0.5);
        float[] out = new float[n];
        double lp = 0;
        for (int i = 0; i < n; i++) {
            lp += 0.08 * (rng.nextDouble() * 2 - 1 - lp);
            double thud = Math.sin(2 * Math.PI * 70 * i / (double)SR) * Math.exp(-6.0 * i / n);
            out[i] = (float)((lp * 0.7 + thud * 0.5) * Math.exp(-2.5 * i / n));
        }
        return out;
    }

    private float[] chirp() {
        int n = (int)(SR * 0.35);
        float[] out = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double tt = (double)i / SR;
            double f = 2800 + 900 * Math.sin(tt * 40) - 600 * tt;
            phase += 2 * Math.PI * f / SR;
            double env = Math.sin(Math.PI * i / n);
            out[i] = (float)(Math.sin(phase) * env * env * 0.12);
        }
        return out;
    }

    private float[] cricket() {
        int n = (int)(SR * 0.5);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double tt = (double)i / SR;
            double am = Math.max(0, Math.sin(2 * Math.PI * 22 * tt));
            out[i] = (float)(Math.sin(2 * Math.PI * 4200 * tt) * am * 0.06);
        }
        return out;
    }
}
