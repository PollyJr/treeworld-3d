package com.pollyjr.treegame;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private GameRenderer renderer;
    private GLSurfaceView glView;
    private SoundSynth sound;
    private Vibrator vibrator;
    private boolean vibrateOn = true;

    private TextView hud, powerHud, debugHud, centerMsg, subMsg, pauseBtn, soundBtn, vibBtn;
    private final Handler handler = new Handler();

    private float downX, downY;
    private long downTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sound = new SoundSynth();
        sound.enabled = getPreferences(MODE_PRIVATE).getBoolean("sound", true);
        vibrateOn = getPreferences(MODE_PRIVATE).getBoolean("vibrate", true);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        sound.start();

        renderer = new GameRenderer();
        renderer.sound = sound;
        renderer.best = getPreferences(MODE_PRIVATE).getInt("best", 0);
        renderer.events = new GameRenderer.Events() {
            @Override public void crash() { buzz(250); }
            @Override public void milestone() { buzz(60); }
            @Override public void shieldBlock() { buzz(120); }
        };

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        FrameLayout root = new FrameLayout(this);
        root.addView(glView, lp(-1, -1));

        hud = label(16, Color.WHITE, Gravity.START);
        hud.setPadding(28, 28, 0, 0);
        root.addView(hud, lp(-2, -2));

        powerHud = label(13, 0xFFFFE28A, Gravity.START);
        powerHud.setPadding(28, 130, 0, 0);
        root.addView(powerHud, lp(-2, -2));

        debugHud = label(11, 0xAAFFFFFF, Gravity.END);
        FrameLayout.LayoutParams dlp = lp(-2, -2);
        dlp.gravity = Gravity.TOP | Gravity.END;
        debugHud.setPadding(0, 28, 28, 0);
        root.addView(debugHud, dlp);

        centerMsg = label(26, Color.WHITE, Gravity.CENTER);
        root.addView(centerMsg, lp(-1, -1));

        subMsg = label(15, 0xDDFFFFFF, Gravity.CENTER);
        FrameLayout.LayoutParams slp = lp(-1, -2);
        slp.gravity = Gravity.BOTTOM;
        subMsg.setPadding(0, 0, 0, 90);
        root.addView(subMsg, slp);

        pauseBtn = pill("\u23F8");
        FrameLayout.LayoutParams plp = lp(-2, -2);
        plp.gravity = Gravity.TOP | Gravity.END;
        plp.setMargins(0, 90, 20, 0);
        pauseBtn.setVisibility(View.GONE);
        pauseBtn.setOnClickListener(v -> togglePause());
        root.addView(pauseBtn, plp);

        soundBtn = pill("Sound: ON");
        FrameLayout.LayoutParams sblp = lp(-2, -2);
        sblp.gravity = Gravity.BOTTOM | Gravity.START;
        sblp.setMargins(24, 0, 0, 24);
        soundBtn.setOnClickListener(v -> {
            sound.enabled = !sound.enabled;
            soundBtn.setText("Sound: " + (sound.enabled ? "ON" : "OFF"));
            getPreferences(MODE_PRIVATE).edit().putBoolean("sound", sound.enabled).apply();
        });
        soundBtn.setText("Sound: " + (sound.enabled ? "ON" : "OFF"));
        root.addView(soundBtn, sblp);

        vibBtn = pill("Vibrate: ON");
        FrameLayout.LayoutParams vblp = lp(-2, -2);
        vblp.gravity = Gravity.BOTTOM | Gravity.END;
        vblp.setMargins(0, 0, 24, 24);
        vibBtn.setOnClickListener(v -> {
            vibrateOn = !vibrateOn;
            vibBtn.setText("Vibrate: " + (vibrateOn ? "ON" : "OFF"));
            getPreferences(MODE_PRIVATE).edit().putBoolean("vibrate", vibrateOn).apply();
        });
        vibBtn.setText("Vibrate: " + (vibrateOn ? "ON" : "OFF"));
        root.addView(vibBtn, vblp);

        View touch = new View(this);
        FrameLayout.LayoutParams tlp = lp(-1, -1);
        root.addView(touch, tlp);
        // keep buttons on top
        pauseBtn.bringToFront(); soundBtn.bringToFront(); vibBtn.bringToFront();
        touch.setOnTouchListener((v, e) -> handleTouch(e));

        setContentView(root);
        startHudLoop();
    }

    private void togglePause() {
        if (renderer.state != GameRenderer.State.PLAYING) return;
        renderer.paused = !renderer.paused;
    }

    private boolean handleTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); downTime = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - downX, dy = e.getY() - downY;
                long dt = System.currentTimeMillis() - downTime;
                float adx = Math.abs(dx), ady = Math.abs(dy);
                if (renderer.paused) { renderer.paused = false; return true; }
                switch (renderer.state) {
                    case READY:
                        renderer.state = GameRenderer.State.PLAYING;
                        break;
                    case GAME_OVER:
                        renderer.restart();
                        break;
                    case PLAYING:
                        if (adx > 70 && adx > ady) renderer.inputLane = dx > 0 ? 1 : -1;
                        else if (ady > 70 && ady > adx) {
                            if (dy < 0) renderer.inputJump = true; else renderer.inputSlide = true;
                        } else if (dt < 300) renderer.inputJump = true;
                        break;
                }
                return true;
        }
        return true;
    }

    private void startHudLoop() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                updateHud();
                handler.postDelayed(this, 200);
            }
        }, 200);
    }

    private void updateHud() {
        String mult = renderer.multiplier > 1 ? "  x" + renderer.multiplier : "";
        hud.setText(String.format("SCORE %d%s\nBEST %d\nDIST %dm  COINS %d",
            renderer.score, mult, renderer.best, (int)renderer.distance, renderer.coinsCollected));

        StringBuilder pw = new StringBuilder();
        if (renderer.shield) pw.append("\uD83D\uDEE1 SHIELD\n");
        if (renderer.magnetT > 0) pw.append(String.format("\uD83E\uDDF2 MAGNET %.0fs\n", renderer.magnetT));
        if (renderer.x2T > 0) pw.append(String.format("\u2B50 2X SCORE %.0fs\n", renderer.x2T));
        powerHud.setText(pw.toString());

        String[] phaseNames = {"DAY", "SUNSET", "NIGHT", "DAWN"};
        debugHud.setText(String.format("FPS %d | SPD %.1f\n%s | DEBUG 0.2",
            renderer.fps, renderer.speed, phaseNames[renderer.phase]));

        boolean playing = renderer.state == GameRenderer.State.PLAYING && !renderer.paused;
        pauseBtn.setVisibility(playing ? View.VISIBLE : View.GONE);
        boolean menu = renderer.state == GameRenderer.State.READY;
        soundBtn.setVisibility(menu ? View.VISIBLE : View.GONE);
        vibBtn.setVisibility(menu ? View.VISIBLE : View.GONE);

        if (renderer.paused) {
            centerMsg.setText("PAUSED");
            subMsg.setText("Tap to resume");
        } else switch (renderer.state) {
            case READY:
                centerMsg.setText("TREEWORLD 3D");
                subMsg.setText("Best: " + renderer.best +
                    "\nSwipe \u25C0 \u25B6 move \u2022 Swipe \u25B2/tap jump \u2022 Swipe \u25BC slide" +
                    "\nJump rocks & logs \u2022 Slide under branches\n\nTAP TO PLAY");
                break;
            case GAME_OVER:
                centerMsg.setText("GAME OVER\n" + renderer.score);
                subMsg.setText(String.format("Distance %dm \u2022 Coins %d \u2022 Best %d\n\nTap to run again",
                    (int)renderer.distance, renderer.coinsCollected, renderer.best));
                break;
            default:
                centerMsg.setText("");
                subMsg.setText("");
        }
    }

    private TextView label(int size, int color, int gravity) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setGravity(gravity);
        t.setShadowLayer(5, 1, 2, Color.BLACK);
        return t;
    }

    private TextView pill(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(28, 14, 28, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x66000000);
        bg.setCornerRadius(40);
        t.setBackground(bg);
        return t;
    }

    private FrameLayout.LayoutParams lp(int w, int h) {
        return new FrameLayout.LayoutParams(w, h);
    }

    private void buzz(long ms) {
        if (!vibrateOn || vibrator == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26)
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(ms);
        } catch (Exception ignored) {}
    }

    @Override protected void onPause() {
        super.onPause();
        glView.onPause();
        if (renderer.state == GameRenderer.State.PLAYING) renderer.paused = true;
        save();
    }
    @Override protected void onResume() { super.onResume(); glView.onResume(); }
    @Override protected void onDestroy() { super.onDestroy(); save(); sound.stop(); }
    @Override public void onBackPressed() {
        if (renderer.state == GameRenderer.State.PLAYING) togglePause();
        else super.onBackPressed();
    }
    private void save() {
        getPreferences(MODE_PRIVATE).edit().putInt("best", renderer.best).apply();
    }
}
