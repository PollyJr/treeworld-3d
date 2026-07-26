package com.pollyjr.treegame;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * TreeWorld 3D v2 — mini 3D engine + polished endless runner.
 * Engine: gradient sky dome w/ sun & stars, day-night cycle, hemisphere
 * lighting + specular, distance fog, alpha blending, particle system,
 * blob shadows. Game: jump/slide obstacles, coins + combo multiplier,
 * shield/magnet/x2 power-ups, birds, leaves, fireflies, camera shake.
 */
public class GameRenderer implements GLSurfaceView.Renderer {

    public interface Events { void crash(); void milestone(); void shieldBlock(); }
    public Events events;
    public SoundSynth sound;

    public enum State { READY, PLAYING, GAME_OVER }
    public volatile State state = State.READY;
    public volatile boolean paused = false;
    public volatile int inputLane = 0;
    public volatile boolean inputJump = false;
    public volatile boolean inputSlide = false;

    // HUD-visible state
    public int score = 0, best = 0, coinsCollected = 0;
    public float distance = 0, speed = 8f;
    public int fps = 0, multiplier = 1;
    public boolean shield = false;
    public float magnetT = 0, x2T = 0;
    public int phase = 0; // 0 day 1 sunset 2 night 3 dawn

    // ------------------------------------------------------------- shaders
    private static final String LIT_VERT =
        "uniform mat4 uMVP;\nuniform mat4 uModel;\n" +
        "attribute vec4 aPos;\nattribute vec3 aNormal;\nattribute vec3 aColor;\n" +
        "varying vec3 vColor;\nvarying vec3 vNormal;\nvarying float vDist;\nvarying vec3 vWorld;\n" +
        "void main(){\n" +
        "  vec4 world = uModel * aPos;\n" +
        "  vWorld = world.xyz;\n" +
        "  gl_Position = uMVP * aPos;\n" +
        "  vColor = aColor;\n" +
        "  vNormal = mat3(uModel) * aNormal;\n" +
        "  vDist = gl_Position.w;\n" +
        "}\n";
    private static final String LIT_FRAG =
        "precision mediump float;\n" +
        "varying vec3 vColor;\nvarying vec3 vNormal;\nvarying float vDist;\nvarying vec3 vWorld;\n" +
        "uniform vec3 uFogColor;\nuniform vec3 uLightDir;\nuniform vec3 uLightCol;\n" +
        "uniform float uAmbient;\nuniform float uGlow;\nuniform float uAlpha;\nuniform float uFogFar;\n" +
        "uniform vec3 uCamPos;\n" +
        "void main(){\n" +
        "  vec3 n = normalize(vNormal);\n" +
        "  vec3 l = normalize(uLightDir);\n" +
        "  float diff = max(dot(n, l), 0.0);\n" +
        "  float hemi = 0.5 + 0.5 * n.y;\n" +
        "  vec3 viewDir = normalize(uCamPos - vWorld);\n" +
        "  vec3 hv = normalize(l + viewDir);\n" +
        "  float spec = pow(max(dot(n, hv), 0.0), 24.0) * 0.25;\n" +
        "  vec3 col = vColor * (uAmbient * hemi + diff * uLightCol) + spec + vColor * uGlow;\n" +
        "  float fog = clamp((vDist - 18.0) / uFogFar, 0.0, 1.0);\n" +
        "  gl_FragColor = vec4(mix(col, uFogColor, fog), uAlpha);\n" +
        "}\n";
    private static final String SKY_VERT =
        "uniform mat4 uVP;\nattribute vec3 aPos;\nvarying vec3 vDir;\n" +
        "void main(){ vDir = aPos; vec4 p = uVP * vec4(aPos, 1.0); gl_Position = p.xyww; }\n";
    private static final String SKY_FRAG =
        "precision mediump float;\nvarying vec3 vDir;\n" +
        "uniform vec3 uZenith;\nuniform vec3 uHorizon;\nuniform vec3 uSunDir;\nuniform vec3 uSunCol;\n" +
        "uniform float uNight;\nuniform float uTime;\n" +
        "float hash(vec3 p){ return fract(sin(dot(p, vec3(12.9898,78.233,45.164))) * 43758.5453); }\n" +
        "void main(){\n" +
        "  vec3 d = normalize(vDir);\n" +
        "  float h = clamp(d.y, 0.0, 1.0);\n" +
        "  vec3 col = mix(uHorizon, uZenith, pow(h, 0.6));\n" +
        "  float sun = max(dot(d, normalize(uSunDir)), 0.0);\n" +
        "  col += uSunCol * (pow(sun, 350.0) * 1.2 + pow(sun, 8.0) * 0.25);\n" +
        "  vec3 cell = floor(d * 160.0);\n" +
        "  float star = step(0.9985, hash(cell));\n" +
        "  float tw = 0.5 + 0.5 * sin(uTime * 3.0 + hash(cell + 1.0) * 40.0);\n" +
        "  col += vec3(star * tw * uNight * smoothstep(0.05, 0.3, d.y));\n" +
        "  gl_FragColor = vec4(col, 1.0);\n" +
        "}\n";

    private int litProg, skyProg;
    private int hMVP, hModel, hFog, hLight, hLightCol, hAmbient, hGlow, hAlpha, hFogFar, hCamPos;
    private int hPos, hNormal, hColor;
    private int sVP, sPos, sZenith, sHorizon, sSunDir, sSunCol, sNight, sTime;

    private Mesh cube, foliage, trunk, rock, orb, ground, circle, log, coinM, birdM, shieldM, skyM;
    private final Particles particles = new Particles();

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] vp = new float[16];
    private final float[] model = new float[16];
    private final float[] mvp = new float[16];
    private final float[] tmp = new float[16];

    // ------------------------------------------------------------ day cycle
    // keyframes: day, sunset, night, dawn
    private static final float[][] ZENITH = {
        {0.30f, 0.60f, 0.95f}, {0.32f, 0.25f, 0.50f}, {0.02f, 0.04f, 0.11f}, {0.38f, 0.42f, 0.66f}};
    private static final float[][] HORIZON = {
        {0.72f, 0.86f, 0.97f}, {0.98f, 0.58f, 0.32f}, {0.09f, 0.12f, 0.24f}, {0.95f, 0.72f, 0.55f}};
    private static final float[][] SUNCOL = {
        {1.0f, 0.95f, 0.8f}, {1.0f, 0.55f, 0.25f}, {0.6f, 0.7f, 0.9f}, {1.0f, 0.8f, 0.55f}};
    private static final float[] AMBIENT = {0.55f, 0.42f, 0.22f, 0.4f};
    private static final float[] FOGFAR = {55f, 48f, 38f, 45f};
    private static final float CYCLE = 600f; // metres per full day

    private final float[] zenith = new float[3];
    private final float[] horizon = new float[3];
    private final float[] sunCol = new float[3];
    private final float[] lightDir = new float[3];
    private float ambient = 0.55f, fogFar = 55f, night = 0f;
    private float time = 0;

    // --------------------------------------------------------------- world
    private static class Tree { float x, z, s; }
    private static class Ent { int lane; float z; boolean active = true; int kind; }
    // kinds: 0 rock, 1 log, 2 branch, 3 orb, 4 shield, 5 magnet, 6 x2
    private final List<Tree> trees = new ArrayList<>();
    private final List<Ent> obstacles = new ArrayList<>();
    private final List<Ent> pickups = new ArrayList<>();
    private final List<float[]> coins = new ArrayList<>(); // x,y,z,active
    private final List<float[]> birds = new ArrayList<>(); // x,y,z,speed,flapPhase
    private final Random rng = new Random();

    private static final float[] LANES = {-2.2f, 0f, 2.2f};
    private int lane = 1;
    private float playerX = 0, playerY = 0.7f, velY = 0;
    private boolean jumping = false, sliding = false;
    private float slideT = 0;
    private float playerZ = 0;
    private float spin = 0, runTime = 0, shake = 0;
    private int combo = 0, pickupScore = 0;
    private int lastMilestone = 0;
    private float camX = 0;

    private long lastTime = 0, fpsTimer = 0;
    private int frameCount = 0;

    // ------------------------------------------------------------ GL setup
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_CULL_FACE);

        litProg = program(LIT_VERT, LIT_FRAG);
        hMVP = u(litProg, "uMVP"); hModel = u(litProg, "uModel"); hFog = u(litProg, "uFogColor");
        hLight = u(litProg, "uLightDir"); hLightCol = u(litProg, "uLightCol");
        hAmbient = u(litProg, "uAmbient"); hGlow = u(litProg, "uGlow");
        hAlpha = u(litProg, "uAlpha"); hFogFar = u(litProg, "uFogFar"); hCamPos = u(litProg, "uCamPos");
        hPos = a(litProg, "aPos"); hNormal = a(litProg, "aNormal"); hColor = a(litProg, "aColor");

        skyProg = program(SKY_VERT, SKY_FRAG);
        sVP = u(skyProg, "uVP"); sZenith = u(skyProg, "uZenith"); sHorizon = u(skyProg, "uHorizon");
        sSunDir = u(skyProg, "uSunDir"); sSunCol = u(skyProg, "uSunCol");
        sNight = u(skyProg, "uNight"); sTime = u(skyProg, "uTime");
        sPos = a(skyProg, "aPos");

        cube = Mesh.cube(0.98f, 0.80f, 0.25f);
        foliage = Mesh.cone(7, 1.5f, 2.8f, 0.15f, 0.52f, 0.22f);
        trunk = Mesh.cylinder(6, 0.24f, 1.3f, 0.40f, 0.26f, 0.15f);
        rock = Mesh.sphere(6, 8, 0.44f, 0.42f, 0.46f);
        orb = Mesh.sphere(8, 10, 1.0f, 0.55f, 0.22f);
        ground = Mesh.ground(60f, 0.28f, 0.58f, 0.30f);
        circle = Mesh.circle(14, 0f, 0f, 0f);
        log = Mesh.log(8, 0.55f, 2.0f, 0.45f, 0.30f, 0.16f);
        coinM = Mesh.cylinder(10, 0.38f, 0.1f, 1.0f, 0.82f, 0.2f);
        birdM = Mesh.bird(0.15f, 0.15f, 0.2f);
        shieldM = Mesh.sphere(10, 12, 0.35f, 0.65f, 1.0f);
        skyM = Mesh.sphere(10, 14, 1f, 1f, 1f);
        particles.initGL();

        resetWorld();
    }

    private void resetWorld() {
        trees.clear(); obstacles.clear(); pickups.clear(); coins.clear(); birds.clear();
        particles.clear();
        rng.setSeed(System.nanoTime());
        for (float z = 20; z > -150; z -= 4) spawnTreeRow(z);
        for (float z = -35; z > -150; z -= 8) spawnGate(z);
        playerZ = 0; playerX = 0; playerY = 0.7f; velY = 0;
        jumping = false; sliding = false; slideT = 0; lane = 1;
        speed = 8f; distance = 0; score = 0; runTime = 0; shake = 0;
        combo = 0; multiplier = 1; pickupScore = 0; coinsCollected = 0;
        shield = false; magnetT = 0; x2T = 0; lastMilestone = 0;
        time = 0;
    }

    private void spawnTreeRow(float z) {
        for (int side = -1; side <= 1; side += 2) {
            int n = 1 + rng.nextInt(2);
            for (int i = 0; i < n; i++) {
                Tree t = new Tree();
                t.x = side * (4.5f + rng.nextFloat() * 15f);
                t.z = z + rng.nextFloat() * 4f;
                t.s = 0.8f + rng.nextFloat() * 1.5f;
                trees.add(t);
            }
        }
    }

    /** Spawn one gameplay gate: obstacles + rewards, always passable. */
    private void spawnGate(float z) {
        int roll = rng.nextInt(100);
        if (roll < 12) {
            // power-up gate
            Ent p = new Ent();
            p.kind = 4 + rng.nextInt(3); // shield / magnet / x2
            p.lane = rng.nextInt(3); p.z = z;
            pickups.add(p);
            coinRow((p.lane + 1) % 3, z - 6);
            return;
        }
        int freeLane = rng.nextInt(3);
        for (int l = 0; l < 3; l++) {
            if (l == freeLane) continue;
            if (rng.nextInt(100) < 65) {
                Ent o = new Ent();
                o.lane = l; o.z = z - rng.nextFloat() * 2;
                o.kind = rng.nextInt(100) < 40 ? 1 : (rng.nextInt(100) < 45 ? 2 : 0);
                obstacles.add(o);
            }
        }
        // rewards in free lane
        if (rng.nextInt(100) < 30) {
            Ent o = new Ent(); o.kind = 3; o.lane = freeLane; o.z = z - 1;
            pickups.add(o);
        } else {
            coinRow(freeLane, z - 5);
        }
    }

    private void coinRow(int laneIdx, float zStart) {
        for (int i = 0; i < 5; i++) {
            coins.add(new float[]{LANES[laneIdx], 0.9f, zStart - i * 1.3f, 1});
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        Matrix.perspectiveM(proj, 0, 60f, (float) w / h, 0.1f, 220f);
    }

    // -------------------------------------------------------------- update
    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = lastTime == 0 ? 0.016f : Math.min((now - lastTime) / 1e9f, 0.05f);
        lastTime = now;
        frameCount++;
        if (now - fpsTimer > 500_000_000L) {
            fps = (int)(frameCount * 1e9 / (now - fpsTimer));
            frameCount = 0; fpsTimer = now;
        }
        if (state == State.PLAYING && !paused) update(dt);
        render();
    }

    private void update(float dt) {
        runTime += dt; time += dt;
        speed = Math.min(9f + runTime * 0.22f, 28f);
        playerZ -= speed * dt;
        distance = -playerZ;
        spin += dt * 4f;
        score = (int)(distance * 2) + pickupScore;

        int mile = (int)(distance / 250);
        if (mile > lastMilestone) {
            lastMilestone = mile;
            if (sound != null) sound.milestone();
            if (events != null) events.milestone();
        }

        // day-night phase from distance
        updateDayCycle();

        // input
        if (inputLane != 0) {
            lane = Math.max(0, Math.min(2, lane + inputLane));
            inputLane = 0;
        }
        playerX += (LANES[lane] - playerX) * Math.min(1f, dt * 13f);
        if (inputJump) {
            if (!jumping && !sliding) { velY = 10f; jumping = true; if (sound != null) sound.jump(); }
            inputJump = false;
        }
        if (inputSlide) {
            if (!jumping) { sliding = true; slideT = 0.55f; if (sound != null) sound.slide(); }
            inputSlide = false;
        }
        if (sliding) { slideT -= dt; if (slideT <= 0) sliding = false; }
        if (jumping) {
            velY -= 27f * dt;
            playerY += velY * dt;
            if (playerY <= 0.7f) {
                playerY = 0.7f; jumping = false; velY = 0;
                particles.burst(playerX, 0.15f, playerZ, 8, 2.5f, 0.5f, 2.5f, 0.75f, 0.68f, 0.5f);
            }
        }

        // power-up timers
        if (magnetT > 0) magnetT -= dt;
        if (x2T > 0) x2T -= dt;
        shake = Math.max(0, shake - dt * 1.8f);

        // recycle world
        float behind = playerZ + 14;
        for (Tree t : trees) if (t.z > behind) { t.z -= 190; t.x = Math.signum(t.x) * (4.5f + rng.nextFloat() * 15f); }
        for (Ent o : obstacles) if (o.z > behind) { o.z -= 152; o.active = true; o.lane = rng.nextInt(3); }
        for (Ent p : pickups) if (p.z > behind) { p.z -= 152; p.active = true; p.lane = rng.nextInt(3); }
        for (float[] c : coins) if (c[2] > behind) { c[2] -= 152; c[3] = 1; }

        // ambient particles
        ambientParticles(dt);

        // magnet pull
        if (magnetT > 0) {
            for (float[] c : coins) {
                if (c[3] == 1 && c[2] < playerZ && c[2] > playerZ - 9) {
                    c[0] += (playerX - c[0]) * Math.min(1f, dt * 10f);
                    c[2] += (playerZ - c[2]) * Math.min(1f, dt * 4f);
                }
            }
        }

        // coin collisions
        for (float[] c : coins) {
            if (c[3] == 1 && Math.abs(c[2] - playerZ) < 1.0f && Math.abs(c[0] - playerX) < 1.0f && playerY < 2.6f) {
                c[3] = 0;
                coinsCollected++;
                combo++;
                multiplier = Math.min(5, 1 + combo / 10);
                int gain = 5 * multiplier * (x2T > 0 ? 2 : 1);
                pickupScore += gain;
                particles.burst(c[0], c[1], c[2], 10, 3f, 0.6f, 2.2f, 1f, 0.85f, 0.3f);
                if (sound != null) sound.coin();
            }
            // missed coin breaks combo
            if (c[3] == 1 && c[2] > playerZ + 2) { combo = 0; multiplier = 1; }
        }

        // pickup collisions
        for (Ent p : pickups) {
            if (!p.active || Math.abs(p.z - playerZ) > 1.1f || Math.abs(LANES[p.lane] - playerX) > 1.2f || playerY > 2.6f) continue;
            p.active = false;
            combo++;
            multiplier = Math.min(5, 1 + combo / 10);
            switch (p.kind) {
                case 3:
                    pickupScore += 25 * multiplier * (x2T > 0 ? 2 : 1);
                    particles.burst(LANES[p.lane], 1.2f, p.z, 16, 4f, 0.8f, 3f, 1f, 0.6f, 0.25f);
                    if (sound != null) sound.orb();
                    break;
                case 4: shield = true; if (sound != null) sound.power(); break;
                case 5: magnetT = 8f; if (sound != null) sound.power(); break;
                case 6: x2T = 10f; if (sound != null) sound.power(); break;
            }
        }

        // obstacle collisions
        for (Ent o : obstacles) {
            if (!o.active || Math.abs(o.z - playerZ) > 1.0f || Math.abs(LANES[o.lane] - playerX) > 1.1f) continue;
            boolean hit;
            switch (o.kind) {
                case 1: hit = playerY < 1.7f; break;              // log: jump over
                case 2: hit = !sliding && playerY < 2.4f; break;  // branch: slide under
                default: hit = playerY < 1.5f; break;             // rock: jump over
            }
            if (hit) {
                if (shield) {
                    shield = false;
                    o.active = false;
                    shake = 0.5f;
                    particles.burst(LANES[o.lane], 1f, o.z, 22, 5f, 0.9f, 3.5f, 0.4f, 0.7f, 1f);
                    if (sound != null) sound.shieldHit();
                    if (events != null) events.shieldBlock();
                } else {
                    crash();
                    return;
                }
            }
        }
    }

    private void crash() {
        state = State.GAME_OVER;
        shake = 1f;
        particles.burst(playerX, 1f, playerZ, 30, 6f, 1.2f, 4f, 0.9f, 0.4f, 0.2f);
        if (score > best) best = score;
        if (sound != null) sound.crash();
        if (events != null) events.crash();
    }

    public void restart() {
        resetWorld();
        state = State.PLAYING;
        paused = false;
    }

    private void updateDayCycle() {
        float t = (distance % CYCLE) / CYCLE; // 0..1
        int seg; float f;
        if (t < 0.35f) { seg = 0; f = 0; }
        else if (t < 0.5f) { seg = 0; f = (t - 0.35f) / 0.15f; }
        else if (t < 0.6f) { seg = 1; f = (t - 0.5f) / 0.1f; }
        else if (t < 0.85f) { seg = 2; f = 0; }
        else { seg = 2; f = (t - 0.85f) / 0.15f; }
        int next = Math.min(seg + 1, 3);
        if (t >= 0.85f) { next = 3; }
        if (t >= 0.97f) { seg = 3; next = 0; f = (t - 0.97f) / 0.03f; }
        for (int i = 0; i < 3; i++) {
            zenith[i] = lerp(ZENITH[seg][i], ZENITH[next][i], f);
            horizon[i] = lerp(HORIZON[seg][i], HORIZON[next][i], f);
            sunCol[i] = lerp(SUNCOL[seg][i], SUNCOL[next][i], f);
        }
        ambient = lerp(AMBIENT[seg], AMBIENT[next], f);
        fogFar = lerp(FOGFAR[seg], FOGFAR[next], f);
        float sunAngle = (float)(Math.PI * (0.15 + 0.7 * t));
        lightDir[0] = (float)Math.cos(sunAngle) * 0.6f;
        lightDir[1] = Math.max(0.15f, (float)Math.sin(sunAngle));
        lightDir[2] = 0.45f;
        night = seg == 2 && f < 0.5f ? 1f : (seg == 1 ? f : 1f - f);
        phase = seg;
        if (sound != null) sound.phase = phase;
    }

    private static float lerp(float a, float b, float f) { return a + (b - a) * f; }

    private void ambientParticles(float dt) {
        // falling leaves
        if (rng.nextFloat() < 8 * dt) {
            particles.spawn(camX + (rng.nextFloat() - 0.5f) * 24, 4 + rng.nextFloat() * 5,
                playerZ - 5 - rng.nextFloat() * 35,
                0, -0.7f - rng.nextFloat() * 0.5f, speed * 0.1f,
                5f, 2f, 0.35f + rng.nextFloat() * 0.3f, 0.6f, 0.25f, 0.6f);
        }
        // fireflies at night
        if (night > 0.6f && rng.nextFloat() < 10 * dt) {
            particles.spawn(camX + (rng.nextFloat() - 0.5f) * 20, 0.8f + rng.nextFloat() * 3,
                playerZ - 3 - rng.nextFloat() * 30,
                0, 0, 0, 7f, 1.8f, 0.75f, 1f, 0.4f, 0.2f);
        }
        // running dust
        if (!jumping && rng.nextFloat() < 14 * dt) {
            particles.spawn(playerX + (rng.nextFloat() - 0.5f) * 0.5f, 0.1f, playerZ + 0.6f,
                (rng.nextFloat() - 0.5f) * 1f, 1.2f, 1.5f, 0.5f, 1.6f, 0.7f, 0.65f, 0.5f, 0);
        }
        // birds by day
        if (phase == 0 && birds.size() < 2 && rng.nextFloat() < 0.15 * dt) {
            birds.add(new float[]{camX + (rng.nextBoolean() ? -30 : 30),
                7 + rng.nextFloat() * 5, playerZ - 40 - rng.nextFloat() * 20,
                rng.nextBoolean() ? 6f : -6f, rng.nextFloat() * 6});
        }
        for (int i = birds.size() - 1; i >= 0; i--) {
            float[] b = birds.get(i);
            b[0] += b[3] * dt;
            b[4] += dt * 10;
            if (Math.abs(b[0] - camX) > 60 || b[2] > playerZ + 20) birds.remove(i);
        }
    }

    // -------------------------------------------------------------- render
    private void render() {
        GLES20.glClearColor(horizon[0], horizon[1], horizon[2], 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // camera with shake
        float sx = shake > 0 ? (rng.nextFloat() - 0.5f) * shake * 0.7f : 0;
        float sy = shake > 0 ? (rng.nextFloat() - 0.5f) * shake * 0.5f : 0;
        camX += (playerX * 0.55f - camX) * 0.12f;
        float eyeY = 3.4f + sy, eyeZ = playerZ + 7f;
        Matrix.setLookAtM(view, 0,
            camX + sx, eyeY, eyeZ,
            playerX * 0.8f, 1.1f, playerZ - 8f,
            0, 1, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        renderSky();

        GLES20.glUseProgram(litProg);
        GLES20.glUniform3f(hFog, horizon[0], horizon[1], horizon[2]);
        GLES20.glUniform3f(hLight, lightDir[0], lightDir[1], lightDir[2]);
        GLES20.glUniform3f(hLightCol, sunCol[0], sunCol[1], sunCol[2]);
        GLES20.glUniform1f(hAmbient, ambient);
        GLES20.glUniform1f(hFogFar, fogFar);
        GLES20.glUniform3f(hCamPos, camX, eyeY, eyeZ);

        // ground tiles
        for (int i = -1; i < 5; i++) {
            float gz = (float)(Math.floor((playerZ - 10) / 60) * 60) + i * 60f;
            draw(ground, 0, 0, gz, 1, 1, 1, 0, 0, 1f);
        }

        // trees
        for (Tree t : trees) {
            draw(trunk, t.x, 0, t.z, t.s, t.s, t.s, 0, 0, 1f);
            draw(foliage, t.x, 1.2f * t.s, t.z, t.s, t.s, t.s, (float)Math.sin(t.x * 7 + t.z) * 0.4f, 0, 1f);
        }

        // obstacles
        for (Ent o : obstacles) {
            if (!o.active) continue;
            float lx = LANES[o.lane];
            switch (o.kind) {
                case 0:
                    draw(rock, lx, 0.55f, o.z, 1.15f, 0.95f, 1.15f, o.z * 0.5f, 0, 1f);
                    break;
                case 1:
                    draw(log, lx, 0.55f, o.z, 1f, 1f, 1f, 0, 0, 1f);
                    break;
                case 2:
                    // hanging branch: two posts + bar
                    draw(trunk, lx - 1.3f, 0, o.z, 0.7f, 2.3f, 0.7f, 0, 0, 1f);
                    draw(trunk, lx + 1.3f, 0, o.z, 0.7f, 2.3f, 0.7f, 0, 0, 1f);
                    draw(log, lx, 2.1f, o.z, 0.35f, 0.35f, 1.6f, 0, 0, 1f);
                    draw(foliage, lx, 2.2f, o.z, 0.8f, 0.5f, 0.8f, 0, 0, 1f);
                    break;
            }
        }

        // pickups
        for (Ent p : pickups) {
            if (!p.active) continue;
            float lx = LANES[p.lane];
            float bob = 1.2f + 0.25f * (float)Math.sin(spin * 2 + p.z);
            switch (p.kind) {
                case 3:
                    draw(orb, lx, bob, p.z, 0.42f, 0.42f, 0.42f, spin, 1.0f, 1f);
                    break;
                case 4:
                    draw(shieldM, lx, bob, p.z, 0.5f, 0.5f, 0.5f, spin, 0.8f, 0.9f);
                    break;
                case 5:
                    draw(orb, lx, bob, p.z, 0.45f, 0.45f, 0.45f, spin, 0.8f, 1f);
                    break;
                case 6:
                    draw(coinM, lx, bob, p.z, 1.6f, 1.6f, 1.6f, spin, 0.9f, 1f);
                    break;
            }
        }

        // coins (spinning)
        for (float[] c : coins) {
            if (c[3] == 0) continue;
            draw(coinM, c[0], c[1] + 0.1f * (float)Math.sin(spin * 3 + c[2]), c[2],
                1f, 1f, 1f, spin * 2 + c[2], 0.35f, 1f);
        }

        // birds
        for (float[] b : birds) {
            float flap = 0.5f + 0.5f * (float)Math.sin(b[4]);
            draw(birdM, b[0], b[1], b[2], 1.2f, flap, 1.2f, b[3] > 0 ? 1.57f : -1.57f, 0, 1f);
        }

        // blob shadow under player
        float shScale = Math.max(0.4f, 1.1f - (playerY - 0.7f) * 0.25f);
        draw(circle, playerX, 0.02f, playerZ, shScale, 1, shScale, 0, 0, 0.35f);

        // player
        float tilt = (LANES[lane] - playerX) * -0.35f;
        float sy2 = sliding ? 0.5f : 1f;
        draw(cube, playerX, sliding ? 0.35f : playerY, playerZ, 1.15f, 1.15f * sy2, 1.15f, tilt, 0.3f, 1f);

        // shield bubble
        if (shield) {
            draw(shieldM, playerX, playerY + 0.1f, playerZ, 1.6f, 1.6f, 1.6f, spin, 0.5f, 0.3f);
        }

        particles.render(proj, view);
    }

    private void renderSky() {
        GLES20.glDepthMask(false);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glUseProgram(skyProg);
        GLES20.glUniformMatrix4fv(sVP, 1, false, vp, 0);
        GLES20.glUniform3f(sZenith, zenith[0], zenith[1], zenith[2]);
        GLES20.glUniform3f(sHorizon, horizon[0], horizon[1], horizon[2]);
        GLES20.glUniform3f(sSunDir, lightDir[0], lightDir[1], lightDir[2] - 0.9f);
        GLES20.glUniform3f(sSunCol, sunCol[0], sunCol[1], sunCol[2]);
        GLES20.glUniform1f(sNight, night);
        GLES20.glUniform1f(sTime, time);

        skyM.buffer.position(0);
        GLES20.glVertexAttribPointer(sPos, 3, GLES20.GL_FLOAT, false, Mesh.STRIDE, skyM.buffer);
        GLES20.glEnableVertexAttribArray(sPos);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, skyM.vertexCount);

        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
    }

    private void draw(Mesh m, float x, float y, float z, float sx, float sy, float sz,
                      float rotY, float glow, float alpha) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        Matrix.rotateM(model, 0, (float)Math.toDegrees(rotY), 0, 1, 0);
        Matrix.scaleM(model, 0, sx, sy, sz);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);

        GLES20.glUniformMatrix4fv(hMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(hModel, 1, false, model, 0);
        GLES20.glUniform1f(hGlow, glow);
        GLES20.glUniform1f(hAlpha, alpha);

        if (alpha < 1f) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glDepthMask(false);
        }
        m.buffer.position(0);
        GLES20.glVertexAttribPointer(hPos, 3, GLES20.GL_FLOAT, false, Mesh.STRIDE, m.buffer);
        GLES20.glEnableVertexAttribArray(hPos);
        m.buffer.position(3);
        GLES20.glVertexAttribPointer(hNormal, 3, GLES20.GL_FLOAT, false, Mesh.STRIDE, m.buffer);
        GLES20.glEnableVertexAttribArray(hNormal);
        m.buffer.position(6);
        GLES20.glVertexAttribPointer(hColor, 3, GLES20.GL_FLOAT, false, Mesh.STRIDE, m.buffer);
        GLES20.glEnableVertexAttribArray(hColor);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.vertexCount);
        if (alpha < 1f) {
            GLES20.glDepthMask(true);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private static int u(int p, String n) { return GLES20.glGetUniformLocation(p, n); }
    private static int a(int p, String n) { return GLES20.glGetAttribLocation(p, n); }

    private static int program(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
