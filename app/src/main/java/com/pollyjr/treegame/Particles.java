package com.pollyjr.treegame;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * CPU-simulated particle system rendered as GL_POINTS.
 * Handles leaves, fireflies, dust trails, sparkle bursts, crash debris.
 */
public class Particles {
    private static final int MAX = 600;
    private final float[] px = new float[MAX];
    private final float[] py = new float[MAX];
    private final float[] pz = new float[MAX];
    private final float[] vx = new float[MAX];
    private final float[] vy = new float[MAX];
    private final float[] vz = new float[MAX];
    private final float[] life = new float[MAX];
    private final float[] maxLife = new float[MAX];
    private final float[] size = new float[MAX];
    private final float[] cr = new float[MAX];
    private final float[] cg = new float[MAX];
    private final float[] cb = new float[MAX];
    private final float[] sway = new float[MAX];
    private int count = 0;
    private final Random rng = new Random();

    private final FloatBuffer drawBuf;
    private int program;
    private int hMVP, hPos, hData, hColor;
    private final float[] mvp = new float[16];

    private static final String VERT =
        "uniform mat4 uMVP;\n" +
        "attribute vec3 aPos;\n" +
        "attribute float aSize;\n" +
        "attribute vec4 aColor;\n" +
        "varying vec4 vColor;\n" +
        "void main(){\n" +
        "  gl_Position = uMVP * vec4(aPos, 1.0);\n" +
        "  gl_PointSize = aSize * 140.0 / max(gl_Position.w, 1.0);\n" +
        "  vColor = aColor;\n" +
        "}\n";
    private static final String FRAG =
        "precision mediump float;\n" +
        "varying vec4 vColor;\n" +
        "void main(){\n" +
        "  vec2 d = gl_PointCoord - vec2(0.5);\n" +
        "  float a = smoothstep(0.5, 0.15, length(d));\n" +
        "  gl_FragColor = vec4(vColor.rgb, vColor.a * a);\n" +
        "}\n";

    public Particles() {
        ByteBuffer bb = ByteBuffer.allocateDirect(MAX * 8 * 4);
        bb.order(ByteOrder.nativeOrder());
        drawBuf = bb.asFloatBuffer();
    }

    public void initGL() {
        int v = compile(GLES20.GL_VERTEX_SHADER, VERT);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, FRAG);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        GLES20.glLinkProgram(program);
        hMVP = GLES20.glGetUniformLocation(program, "uMVP");
        hPos = GLES20.glGetAttribLocation(program, "aPos");
        hData = GLES20.glGetAttribLocation(program, "aSize");
        hColor = GLES20.glGetAttribLocation(program, "aColor");
    }

    public void spawn(float x, float y, float z, float dx, float dy, float dz,
                      float lifeSec, float sz, float r, float g, float b, float swayAmt) {
        if (count >= MAX) return;
        int i = count++;
        px[i]=x; py[i]=y; pz[i]=z; vx[i]=dx; vy[i]=dy; vz[i]=dz;
        life[i]=lifeSec; maxLife[i]=lifeSec; size[i]=sz;
        cr[i]=r; cg[i]=g; cb[i]=b; sway[i]=swayAmt;
    }

    public void burst(float x, float y, float z, int n, float spd, float lifeSec, float sz,
                      float r, float g, float b) {
        for (int i = 0; i < n; i++) {
            float a = (float)(rng.nextFloat()*Math.PI*2);
            float e = rng.nextFloat()*1.2f;
            spawn(x, y, z,
                (float)Math.cos(a)*spd*(0.4f+rng.nextFloat()*0.6f),
                e*spd*0.8f,
                (float)Math.sin(a)*spd*(0.4f+rng.nextFloat()*0.6f),
                lifeSec*(0.6f+rng.nextFloat()*0.7f), sz, r, g, b, 0);
        }
    }

    public void update(float dt) {
        for (int i = 0; i < count; i++) {
            life[i] -= dt;
            if (life[i] <= 0) {
                int l = --count;
                px[i]=px[l]; py[i]=py[l]; pz[i]=pz[l]; vx[i]=vx[l]; vy[i]=vy[l]; vz[i]=vz[l];
                life[i]=life[l]; maxLife[i]=maxLife[l]; size[i]=size[l];
                cr[i]=cr[l]; cg[i]=cg[l]; cb[i]=cb[l]; sway[i]=sway[l];
                i--; continue;
            }
            float t = maxLife[i] - life[i];
            px[i] += (vx[i] + (sway[i] > 0 ? (float)Math.sin(t*3+i)*sway[i] : 0)) * dt;
            py[i] += vy[i] * dt;
            pz[i] += vz[i] * dt;
            if (sway[i] == 0) vy[i] -= 6f * dt; // gravity only for non-floating particles
        }
    }

    public void render(float[] proj, float[] view) {
        if (count == 0) return;
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        drawBuf.clear();
        for (int i = 0; i < count; i++) {
            float alpha = Math.min(1f, life[i] / (maxLife[i]*0.5f));
            drawBuf.put(px[i]).put(py[i]).put(pz[i]).put(size[i]);
            drawBuf.put(cr[i]).put(cg[i]).put(cb[i]).put(alpha);
        }
        drawBuf.position(0);

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(hMVP, 1, false, mvp, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);

        drawBuf.position(0);
        GLES20.glVertexAttribPointer(hPos, 3, GLES20.GL_FLOAT, false, 32, drawBuf);
        GLES20.glEnableVertexAttribArray(hPos);
        drawBuf.position(3);
        GLES20.glVertexAttribPointer(hData, 1, GLES20.GL_FLOAT, false, 32, drawBuf);
        GLES20.glEnableVertexAttribArray(hData);
        drawBuf.position(4);
        GLES20.glVertexAttribPointer(hColor, 4, GLES20.GL_FLOAT, false, 32, drawBuf);
        GLES20.glEnableVertexAttribArray(hColor);

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    public void clear() { count = 0; }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Particle shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
