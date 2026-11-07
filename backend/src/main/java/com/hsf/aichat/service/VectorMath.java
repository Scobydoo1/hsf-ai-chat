package com.hsf.aichat.service;

import java.nio.ByteBuffer;

/** Helpers to store vectors as bytes and compare them with cosine similarity. */
public final class VectorMath {

    private VectorMath() {
    }

    public static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        for (float f : vector) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    public static float[] fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] v = new float[bytes.length / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = buffer.getFloat();
        }
        return v;
    }

    /** 1 = same direction (very similar meaning), 0 = unrelated */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
