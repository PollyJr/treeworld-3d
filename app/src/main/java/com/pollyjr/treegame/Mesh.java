package com.pollyjr.treegame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Minimal 3D mesh: interleaved position(3) + normal(3) + color(3). Mini engine v2. */
public class Mesh {
    public final FloatBuffer buffer;
    public final int vertexCount;
    public static final int FLOATS_PER_VERTEX = 9;
    public static final int STRIDE = FLOATS_PER_VERTEX * 4;

    private Mesh(float[] data) {
        this.vertexCount = data.length / FLOATS_PER_VERTEX;
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        this.buffer = bb.asFloatBuffer();
        this.buffer.put(data);
        this.buffer.position(0);
    }

    public static class Builder {
        private final java.util.List<Float> v = new java.util.ArrayList<>();

        public Builder tri(float[] a, float[] b, float[] c, float r, float g, float bl) {
            float ux = b[0]-a[0], uy = b[1]-a[1], uz = b[2]-a[2];
            float vx = c[0]-a[0], vy = c[1]-a[1], vz = c[2]-a[2];
            float nx = uy*vz-uz*vy, ny = uz*vx-ux*vz, nz = ux*vy-uy*vx;
            float len = (float)Math.sqrt(nx*nx+ny*ny+nz*nz);
            if (len > 0) { nx/=len; ny/=len; nz/=len; }
            vert(a, nx, ny, nz, r, g, bl);
            vert(b, nx, ny, nz, r, g, bl);
            vert(c, nx, ny, nz, r, g, bl);
            return this;
        }

        private void vert(float[] p, float nx, float ny, float nz, float r, float g, float b) {
            v.add(p[0]); v.add(p[1]); v.add(p[2]);
            v.add(nx); v.add(ny); v.add(nz);
            v.add(r); v.add(g); v.add(b);
        }

        public Mesh build() {
            float[] data = new float[v.size()];
            for (int i = 0; i < data.length; i++) data[i] = v.get(i);
            return new Mesh(data);
        }
    }

    public static Mesh cube(float r, float g, float b) {
        Builder bb = new Builder();
        float[][] p = {
            {-0.5f,-0.5f,-0.5f},{0.5f,-0.5f,-0.5f},{0.5f,0.5f,-0.5f},{-0.5f,0.5f,-0.5f},
            {-0.5f,-0.5f,0.5f},{0.5f,-0.5f,0.5f},{0.5f,0.5f,0.5f},{-0.5f,0.5f,0.5f}
        };
        int[][] faces = {
            {0,1,2},{0,2,3}, {4,6,5},{4,7,6}, {0,3,7},{0,7,4},
            {1,5,6},{1,6,2}, {3,2,6},{3,6,7}, {0,4,5},{0,5,1}
        };
        for (int[] f : faces) bb.tri(p[f[0]], p[f[1]], p[f[2]], r, g, b);
        return bb.build();
    }

    public static Mesh cone(int sides, float radius, float height, float r, float g, float b) {
        Builder bb = new Builder();
        float[] tip = {0, height, 0};
        float[] baseC = {0, 0, 0};
        for (int i = 0; i < sides; i++) {
            double a1 = 2*Math.PI*i/sides, a2 = 2*Math.PI*(i+1)/sides;
            float[] p1 = {(float)(radius*Math.cos(a1)), 0, (float)(radius*Math.sin(a1))};
            float[] p2 = {(float)(radius*Math.cos(a2)), 0, (float)(radius*Math.sin(a2))};
            bb.tri(p1, p2, tip, r, g, b);
            bb.tri(baseC, p2, p1, r*0.6f, g*0.6f, b*0.6f);
        }
        return bb.build();
    }

    public static Mesh cylinder(int sides, float radius, float height, float r, float g, float b) {
        Builder bb = new Builder();
        for (int i = 0; i < sides; i++) {
            double a1 = 2*Math.PI*i/sides, a2 = 2*Math.PI*(i+1)/sides;
            float[] b1 = {(float)(radius*Math.cos(a1)), 0, (float)(radius*Math.sin(a1))};
            float[] b2 = {(float)(radius*Math.cos(a2)), 0, (float)(radius*Math.sin(a2))};
            float[] t1 = {b1[0], height, b1[2]};
            float[] t2 = {b2[0], height, b2[2]};
            bb.tri(b1, b2, t2, r, g, b);
            bb.tri(b1, t2, t1, r, g, b);
            bb.tri(new float[]{0,0,0}, b2, b1, r*0.7f, g*0.7f, b*0.7f);
            bb.tri(new float[]{0,height,0}, t1, t2, r*0.7f, g*0.7f, b*0.7f);
        }
        return bb.build();
    }

    /** Lying cylinder (log) along X axis, centered at origin. */
    public static Mesh log(int sides, float radius, float length, float r, float g, float b) {
        Builder bb = new Builder();
        float hl = length/2;
        for (int i = 0; i < sides; i++) {
            double a1 = 2*Math.PI*i/sides, a2 = 2*Math.PI*(i+1)/sides;
            float[] l1 = {-hl, (float)(radius*Math.sin(a1)), (float)(radius*Math.cos(a1))};
            float[] l2 = {-hl, (float)(radius*Math.sin(a2)), (float)(radius*Math.cos(a2))};
            float[] r1 = {hl, l1[1], l1[2]};
            float[] r2 = {hl, l2[1], l2[2]};
            bb.tri(l1, r1, r2, r, g, b);
            bb.tri(l1, r2, l2, r, g, b);
            bb.tri(new float[]{-hl,0,0}, l2, l1, r*0.7f, g*0.7f, b*0.7f);
            bb.tri(new float[]{hl,0,0}, r1, r2, r*0.7f, g*0.7f, b*0.7f);
        }
        return bb.build();
    }

    public static Mesh sphere(int stacks, int slices, float r, float g, float b) {
        Builder bb = new Builder();
        for (int i = 0; i < stacks; i++) {
            double ph1 = Math.PI*i/stacks - Math.PI/2, ph2 = Math.PI*(i+1)/stacks - Math.PI/2;
            for (int j = 0; j < slices; j++) {
                double th1 = 2*Math.PI*j/slices, th2 = 2*Math.PI*(j+1)/slices;
                float[] p1 = sp(ph1, th1), p2 = sp(ph1, th2), p3 = sp(ph2, th2), p4 = sp(ph2, th1);
                bb.tri(p1, p2, p3, r, g, b);
                bb.tri(p1, p3, p4, r, g, b);
            }
        }
        return bb.build();
    }

    private static float[] sp(double phi, double theta) {
        return new float[]{(float)(Math.cos(phi)*Math.cos(theta)), (float)Math.sin(phi), (float)(Math.cos(phi)*Math.sin(theta))};
    }

    public static Mesh ground(float size, float r, float g, float b) {
        Builder bb = new Builder();
        float h = size/2;
        bb.tri(new float[]{-h,0,-h}, new float[]{-h,0,h}, new float[]{h,0,h}, r, g, b);
        bb.tri(new float[]{-h,0,-h}, new float[]{h,0,h}, new float[]{h,0,-h}, r, g, b);
        return bb.build();
    }

    /** Flat circle on XZ plane, normal up (blob shadows). */
    public static Mesh circle(int sides, float r, float g, float b) {
        Builder bb = new Builder();
        float[] c = {0, 0, 0};
        for (int i = 0; i < sides; i++) {
            double a1 = 2*Math.PI*i/sides, a2 = 2*Math.PI*(i+1)/sides;
            float[] p1 = {(float)Math.cos(a1), 0, (float)Math.sin(a1)};
            float[] p2 = {(float)Math.cos(a2), 0, (float)Math.sin(a2)};
            bb.tri(c, p2, p1, r, g, b);
        }
        return bb.build();
    }

    /** Simple low-poly bird: two angled wings. */
    public static Mesh bird(float r, float g, float b) {
        Builder bb = new Builder();
        bb.tri(new float[]{0,0,0.3f}, new float[]{-1,0.35f,-0.3f}, new float[]{0,0,-0.3f}, r, g, b);
        bb.tri(new float[]{0,0,0.3f}, new float[]{0,0,-0.3f}, new float[]{1,0.35f,-0.3f}, r, g, b);
        return bb.build();
    }
}
