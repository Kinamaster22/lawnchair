package com.android.launcher3;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

/**
 * Home screen icons are pulled toward a magnet AND stretch toward it like gum,
 * with a springy wobble when the magnet arrives or leaves.
 * Uses the raw magnetometer only (no root, no extra libraries).
 * Tuned for 1-2 neodymium rods 5x15 mm held ~3-15 cm from the top of the phone.
 */
public class MagnetPull implements SensorEventListener, Choreographer.FrameCallback {

    // ---- Settings you can change ----
    // Field strengths in microtesla. Neodymium 5x15: ~450 uT at 5 cm, ~55 uT at 10 cm (one rod).
    private static final float NOISE_UT = 8f;     // below this = no magnet
    private static final float QUIET_UT = 12f;    // below this the "zero" is re-learned quickly
    private static final float MAX_UT = 800f;     // field that gives the strongest effect
    // If icons move AWAY from the magnet (depends on which end of the pen is up), set to -1f.
    private static final float DIR_SIGN = 1f;
    private static final float STRETCH = 0.35f;   // gum stretch: 0.35 = up to +35% length
    private static final float MAX_SHIFT_DP = 48f; // how far icons move toward the magnet
    // Gum wobble: bigger K = snappier, smaller C = more wobble
    private static final float SPRING_K = 260f;
    private static final float SPRING_C = 12f;
    // ---------------------------------

    private final Workspace<?> mWorkspace;
    private final SensorManager mSm;
    private final Sensor mSensor;
    private final float mMaxShiftPx;
    private final float[] mBase = new float[3];
    private final int[] mLoc = new int[2];
    private boolean mBaseReady;
    private boolean mRunning;
    private boolean mTouched;
    private long mLastNanos;
    private float mSpring;
    private float mSpringVel;
    private volatile float mPull;
    private volatile float mDirX;
    private volatile float mDirY;

    public MagnetPull(Workspace<?> workspace) {
        mWorkspace = workspace;
        Context c = workspace.getContext();
        mSm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSm == null ? null
                : mSm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        mMaxShiftPx = MAX_SHIFT_DP * c.getResources().getDisplayMetrics().density;
    }

    public void start() {
        if (mRunning || mSensor == null) return;
        mRunning = true;
        mBaseReady = false;
        mLastNanos = 0;
        mSm.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_GAME);
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stop() {
        if (!mRunning) return;
        mRunning = false;
        mSm.unregisterListener(this);
        mPull = 0f;
        mSpring = 0f;
        mSpringVel = 0f;
        reset();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float[] v = e.values;
        if (!mBaseReady) {
            System.arraycopy(v, 0, mBase, 0, 3);
            mBaseReady = true;
            return;
        }
        float dx = v[0] - mBase[0];
        float dy = v[1] - mBase[1];
        float dz = v[2] - mBase[2];
        float m = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        // The "zero" follows Earth's field quickly when quiet, very slowly near a magnet.
        float a = m < QUIET_UT ? 0.01f : 0.0005f;
        for (int i = 0; i < 3; i++) mBase[i] += (v[i] - mBase[i]) * a;

        float p = m <= NOISE_UT ? 0f : (float) Math.min(1.0,
                Math.log(1 + m / NOISE_UT) / Math.log(1 + MAX_UT / NOISE_UT));
        mPull += (p - mPull) * 0.3f;

        float h = (float) Math.hypot(dx, dy);
        if (h > 1e-3f) {
            // device x = right, y = up; screen y points down
            mDirX += (DIR_SIGN * dx / h - mDirX) * 0.25f;
            mDirY += (DIR_SIGN * -dy / h - mDirY) * 0.25f;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mRunning) return;

        float dt = mLastNanos == 0 ? 0.016f : (frameTimeNanos - mLastNanos) / 1e9f;
        mLastNanos = frameTimeNanos;
        if (dt > 0.033f) dt = 0.033f;

        // Springy follow: gives the gum-like overshoot and wobble.
        float acc = SPRING_K * (mPull - mSpring) - SPRING_C * mSpringVel;
        mSpringVel += acc * dt;
        mSpring += mSpringVel * dt;
        if (mSpring > 1.3f) mSpring = 1.3f;
        if (mSpring < -0.15f) mSpring = -0.15f;

        float s = mSpring;
        if (Math.abs(s) > 0.01f || Math.abs(mSpringVel) > 0.05f) {
            mTouched = true;
            View root = mWorkspace.getRootView();
            float cx = root.getWidth() / 2f;
            float cy = root.getHeight() / 2f;
            for (int i = 0; i < mWorkspace.getChildCount(); i++) {
                View page = mWorkspace.getChildAt(i);
                if (page instanceof CellLayout) {
                    pullLayout((CellLayout) page, s, cx, cy);
                }
            }
        } else if (mTouched) {
            mTouched = false;
            reset();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void pullLayout(CellLayout layout, float s, float cx, float cy) {
        ViewGroup container = layout.getShortcutsAndWidgets();
        float dx = mDirX;
        float dy = mDirY;
        float ax = Math.abs(dx);
        float ay = Math.abs(dy);
        for (int i = 0; i < container.getChildCount(); i++) {
            View v = container.getChildAt(i);
            v.getLocationInWindow(mLoc);
            float rx = mLoc[0] + v.getWidth() / 2f - cx;
            float ry = mLoc[1] + v.getHeight() / 2f - cy;
            float len = Math.max(1f, (float) Math.hypot(rx, ry));
            // icons on the magnet's side get pulled and stretched harder
            float w = 0.5f + 0.5f * ((rx / len) * dx + (ry / len) * dy);

            float shift = s * mMaxShiftPx * w;
            v.setTranslationX(dx * shift);
            v.setTranslationY(dy * shift);

            // Gum: anchor the far side, grow toward the magnet, squeeze sideways a bit.
            float st = s * STRETCH * w;
            v.setPivotX(dx >= 0 ? 0f : v.getWidth());
            v.setPivotY(dy >= 0 ? 0f : v.getHeight());
            v.setScaleX(1f + st * ax - 0.3f * st * ay);
            v.setScaleY(1f + st * ay - 0.3f * st * ax);
        }
    }

    private void reset() {
        for (int i = 0; i < mWorkspace.getChildCount(); i++) {
            View page = mWorkspace.getChildAt(i);
            if (page instanceof CellLayout) clearLayout((CellLayout) page);
        }
    }

    private void clearLayout(CellLayout layout) {
        ViewGroup container = layout.getShortcutsAndWidgets();
        for (int i = 0; i < container.getChildCount(); i++) {
            View v = container.getChildAt(i);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
        }
    }
}
