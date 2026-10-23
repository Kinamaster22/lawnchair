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
 * Icons on the home screen are pulled toward a magnet held near the phone.
 * Uses the raw magnetometer only (no root, no extra libraries).
 * Heuristic effect, not the libraries.dev ML model.
 */
public class MagnetPull implements SensorEventListener, Choreographer.FrameCallback {

    // Tune for a weak magnet: lower NOISE_UT (e.g. 3) and MAX_UT (e.g. 60).
    private static final float NOISE_UT = 6f;
    private static final float QUIET_UT = 10f;
    private static final float MAX_UT = 150f;

    private final Workspace<?> mWorkspace;
    private final SensorManager mSm;
    private final Sensor mSensor;
    private final float mMaxShiftPx;
    private final float[] mBase = new float[3];
    private final int[] mLoc = new int[2];
    private boolean mBaseReady;
    private boolean mRunning;
    private boolean mTouched;
    private volatile float mPull;
    private volatile float mDirX;
    private volatile float mDirY;

    public MagnetPull(Workspace<?> workspace) {
        mWorkspace = workspace;
        Context c = workspace.getContext();
        mSm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSm == null ? null
                : mSm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        mMaxShiftPx = 48f * c.getResources().getDisplayMetrics().density;
    }

    public void start() {
        if (mRunning || mSensor == null) return;
        mRunning = true;
        mBaseReady = false;
        mSm.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_GAME);
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stop() {
        if (!mRunning) return;
        mRunning = false;
        mSm.unregisterListener(this);
        mPull = 0f;
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

        // Baseline follows Earth's field quickly when quiet, very slowly near a magnet.
        float a = m < QUIET_UT ? 0.01f : 0.0005f;
        for (int i = 0; i < 3; i++) mBase[i] += (v[i] - mBase[i]) * a;

        float p = m <= NOISE_UT ? 0f : (float) Math.min(1.0,
                Math.log(1 + m / NOISE_UT) / Math.log(1 + MAX_UT / NOISE_UT));
        mPull += (p - mPull) * 0.25f;

        float h = (float) Math.hypot(dx, dy);
        if (h > 1e-3f) {
            // device x = right, y = up; screen y points down
            mDirX += (dx / h - mDirX) * 0.25f;
            mDirY += (-dy / h - mDirY) * 0.25f;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mRunning) return;
        float p = mPull;
        if (p > 0.01f) {
            mTouched = true;
            View root = mWorkspace.getRootView();
            float cx = root.getWidth() / 2f;
            float cy = root.getHeight() / 2f;
            for (int i = 0; i < mWorkspace.getChildCount(); i++) {
                View page = mWorkspace.getChildAt(i);
                if (page instanceof CellLayout) {
                    pullLayout((CellLayout) page, p, cx, cy);
                }
            }
        } else if (mTouched) {
            mTouched = false;
            reset();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void pullLayout(CellLayout layout, float p, float cx, float cy) {
        ViewGroup container = layout.getShortcutsAndWidgets();
        for (int i = 0; i < container.getChildCount(); i++) {
            View v = container.getChildAt(i);
            v.getLocationInWindow(mLoc);
            float rx = mLoc[0] + v.getWidth() / 2f - cx;
            float ry = mLoc[1] + v.getHeight() / 2f - cy;
            float len = Math.max(1f, (float) Math.hypot(rx, ry));
            // icons on the magnet's side get pulled harder
            float w = 0.5f + 0.5f * ((rx / len) * mDirX + (ry / len) * mDirY);
            float shift = p * mMaxShiftPx * w;
            v.setTranslationX(mDirX * shift);
            v.setTranslationY(mDirY * shift);
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
        }
    }
}
