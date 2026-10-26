package com.smoothrunner.gps;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * XposedHook v1.5.3:
 * 1. Xoá triệt để cờ Mock Location (isMock / isFromMockProvider -> false).
 * 2. Chặn 100% hiện tượng nhảy GPS về vị trí phòng cũ khi thoát/chuyển ứng dụng (background <-> foreground),
 *    đồng thời GIỮ NGUYÊN toạ độ lịch sử của từng điểm Location đã phát để vRace tính đúng 100% quãng đường (km)!
 * 3. Giả lập đồng bộ Chip IMU (Accelerometer + Linear Acceleration + Gyroscope + Step Counter / Detector).
 */
public class XposedHook implements IXposedHookLoadPackage {

    private static volatile boolean simActive = false;
    private static volatile long lastSignalElapsedMs = 0L;
    private static volatile double srLat = 0.0;
    private static volatile double srLng = 0.0;
    private static volatile float srBearing = 0.0f;
    private static volatile float srAcc = 3.8f;
    private static volatile float currentSpeedMs = 0.0f;
    private static volatile double syntheticSteps = 0.0;
    private static volatile double imuPhaseRad = 0.0;
    private static volatile long lastImuIntegrateNs = 0L;
    private static volatile float hardwareBaseSteps = -1.0f;
    private static volatile int lastDispatchedStepInt = 0;
    private static volatile int lastEngineRawSteps = 0;

    private static synchronized void syncEngineSteps(int engineSteps) {
        if (engineSteps < 0) return;
        if (engineSteps < lastEngineRawSteps) {
            // Bắt đầu phiên chạy mới (engineSteps reset về 0) -> cộng dồn tiếp trên nền syntheticSteps
            lastEngineRawSteps = 0;
        }
        int delta = engineSteps - lastEngineRawSteps;
        if (delta > 0) {
            lastEngineRawSteps = engineSteps;
            if (syntheticSteps < lastDispatchedStepInt + delta) {
                syntheticSteps = lastDispatchedStepInt + delta;
            }
        }
    }

    private static volatile boolean receiverRegistered = false;
    private static volatile boolean stepTimerRunning = false;
    private static final Map<SensorEventListener, SensorEventListener> wrappedListeners =
            new ConcurrentHashMap<SensorEventListener, SensorEventListener>();
    private static final Set<StepListenerEntry> stepListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<StepListenerEntry, Boolean>());

    // Lưu tập các đối tượng Location đã được kiểm tra để KHÔNG bao giờ ghi đè lại toạ độ lịch sử
    // của điểm cũ khi vRace gọi prevLoc.distanceTo(newLoc) / distanceBetween(prevLoc, newLoc)!
    private static final Set<Location> verifiedLocations =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Location, Boolean>()));

    private static final class StepListenerEntry {
        final SensorEventListener listener;
        final Sensor sensor;

        StepListenerEntry(SensorEventListener listener, Sensor sensor) {
            this.listener = listener;
            this.sensor = sensor;
        }
    }

    private static final class DelegatingSensorEventListener implements SensorEventListener {
        private final SensorEventListener delegate;

        DelegatingSensorEventListener(SensorEventListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            try {
                if (isSimulationActiveNow() && event != null && event.sensor != null && event.values != null) {
                    advanceImuPhysicsIfNeeded();
                    int type = event.sensor.getType();
                    float v = currentSpeedMs;

                    if (type == Sensor.TYPE_ACCELEROMETER && event.values.length >= 3) {
                        double ampVert = (v < 0.18f) ? 0.08 : Math.min(16.5, 2.2 + 3.4 * v);
                        double ampFwd  = (v < 0.18f) ? 0.05 : Math.min(9.5,  1.1 + 1.8 * v);
                        double ampLat  = (v < 0.18f) ? 0.04 : Math.min(5.5,  0.6 + 1.1 * v);

                        event.values[0] = (float) (ampLat * Math.cos(imuPhaseRad * 0.5));
                        event.values[1] = (float) (4.8 + ampVert * (Math.sin(imuPhaseRad) + 0.35 * Math.sin(2.0 * imuPhaseRad - 0.4)));
                        event.values[2] = (float) (8.4 + ampFwd * Math.cos(imuPhaseRad + 0.3));
                    } else if (type == Sensor.TYPE_LINEAR_ACCELERATION && event.values.length >= 3) {
                        double ampVert = (v < 0.18f) ? 0.06 : Math.min(15.5, 2.0 + 3.2 * v);
                        double ampFwd  = (v < 0.18f) ? 0.04 : Math.min(8.5,  1.0 + 1.6 * v);
                        double ampLat  = (v < 0.18f) ? 0.03 : Math.min(5.0,  0.5 + 1.0 * v);

                        event.values[0] = (float) (ampLat * Math.cos(imuPhaseRad * 0.5));
                        event.values[1] = (float) (ampVert * (Math.sin(imuPhaseRad) + 0.35 * Math.sin(2.0 * imuPhaseRad - 0.4)));
                        event.values[2] = (float) (ampFwd * Math.cos(imuPhaseRad + 0.3));
                    } else if (type == Sensor.TYPE_GYROSCOPE && event.values.length >= 3) {
                        double gyroAmp = (v < 0.18f) ? 0.02 : Math.min(2.4, 0.35 + 0.45 * v);
                        event.values[0] = (float) (gyroAmp * Math.sin(imuPhaseRad));
                        event.values[1] = (float) (gyroAmp * 0.6 * Math.cos(imuPhaseRad * 0.5));
                        event.values[2] = (float) (gyroAmp * 0.4 * Math.sin(imuPhaseRad + 0.7));
                    } else if (type == Sensor.TYPE_STEP_COUNTER && event.values.length >= 1) {
                        if (hardwareBaseSteps < 0.0f && event.values[0] >= 0.0f) {
                            hardwareBaseSteps = event.values[0];
                        }
                        float base = hardwareBaseSteps >= 0.0f ? hardwareBaseSteps : 1000.0f;
                        event.values[0] = base + (float) Math.floor(syntheticSteps);
                    } else if (type == Sensor.TYPE_STEP_DETECTOR && event.values.length >= 1) {
                        event.values[0] = 1.0f;
                    }
                }
            } catch (Throwable ignored) {
            }
            delegate.onSensorChanged(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            delegate.onAccuracyChanged(sensor, accuracy);
        }
    }

    /**
     * Giữ trạng thái simActive suốt phiên chạy (tối đa 2 giờ) để khi người dùng thoát vRace
     * chuyển sang ứng dụng khác lâu hơn 6 giây rồi quay lại, khoá chống nhảy GPS về phòng vẫn hoạt động 100%!
     */
    private static boolean isSimulationActiveNow() {
        if (!simActive) return false;
        long ageMs = SystemClock.elapsedRealtime() - lastSignalElapsedMs;
        return ageMs < 7200_000L;
    }

    private static synchronized void advanceImuPhysicsIfNeeded() {
        long nowNs = SystemClock.elapsedRealtimeNanos();
        if (lastImuIntegrateNs == 0L) {
            lastImuIntegrateNs = nowNs;
            return;
        }
        double dt = (nowNs - lastImuIntegrateNs) * 1e-9;
        if (dt <= 0.0 || dt > 1.0) {
            lastImuIntegrateNs = nowNs;
            return;
        }
        if (dt < 0.008) {
            return;
        }
        lastImuIntegrateNs = nowNs;

        float v = isSimulationActiveNow() ? currentSpeedMs : 0.0f;
        if (v >= 0.18f) {
            double strideLen = Math.max(0.55, Math.min(1.42, 0.48 + 0.215 * v));
            double cadenceHz = v / strideLen;
            syntheticSteps += (v * dt) / strideLen;
            imuPhaseRad = (imuPhaseRad + 2.0 * Math.PI * cadenceHz * dt) % (4.0 * Math.PI);
        } else {
            imuPhaseRad = (imuPhaseRad + 2.0 * Math.PI * 0.25 * dt) % (4.0 * Math.PI);
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || lpparam.packageName == null) return;
        if ("android".equals(lpparam.packageName) || "system".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("[SmoothRunner] Active in package: " + lpparam.packageName);

        if ("com.smoothrunner.gps".equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(
                        "com.smoothrunner.gps.MainActivity",
                        lpparam.classLoader,
                        "isLSPosedModuleActive",
                        XC_MethodReplacement.returnConstant(true));
            } catch (Throwable ignored) {
            }
            return;
        }

        hookLocationAntiDetection();
        hookSettingsAntiDetection();
        if (!"com.google.android.gms".equals(lpparam.packageName)) {
            hookApplicationReceiverForImuSync();
            hookImuAndStepSensors();
            hookVraceExerciseModel(lpparam.classLoader);
        }
    }

    private void hookLocationAntiDetection() {
        try {
            XposedHelpers.findAndHookMethod(
                    Location.class,
                    "isFromMockProvider",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sanitizeLocationOnce((Location) param.thisObject);
                            param.setResult(false);
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            if (Build.VERSION.SDK_INT >= 31) {
                XposedHelpers.findAndHookMethod(
                        Location.class,
                        "isMock",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                sanitizeLocationOnce((Location) param.thisObject);
                                param.setResult(false);
                            }
                        });
            }
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                    Location.class,
                    "setIsFromMockProvider",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sanitizeLocationOnce((Location) param.thisObject);
                            param.args[0] = false;
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            if (Build.VERSION.SDK_INT >= 31) {
                XposedHelpers.findAndHookMethod(
                        Location.class,
                        "setMock",
                        boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                sanitizeLocationOnce((Location) param.thisObject);
                                param.args[0] = false;
                            }
                        });
            }
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                    Location.class,
                    "getLatitude",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sanitizeLocationOnce((Location) param.thisObject);
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    Location.class,
                    "getLongitude",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sanitizeLocationOnce((Location) param.thisObject);
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                    Location.class,
                    "getExtras",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Location loc = (Location) param.thisObject;
                            sanitizeLocationOnce(loc);
                            Bundle extras = (Bundle) param.getResult();
                            if (extras != null && extras.containsKey("mockLocation")) {
                                extras.putBoolean("mockLocation", false);
                                extras.remove("mockLocation");
                                param.setResult(extras);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Kiểm tra mỗi đối tượng Location ĐÚNG 1 LẦN DUY NHẤT khi nó vừa xuất hiện:
     * - Nếu là Location do SmoothRunner phát ra (có "sr_speed_ms"): lưu lại (srLat, srLng) mới nhất,
     *   xoá các key nội bộ và GIỮ NGUYÊN toạ độ riêng của đối tượng Location đó để khi vRace tính
     *   khoảng cách giữa điểm cũ và điểm mới (prevLoc -> newLoc) sẽ ra đúng 100% quãng đường!
     * - Nếu là Location lạ (do Wi-Fi phòng KTX hoặc cache cũ khi chuyển app trả về, lệch > 25m so với
     *   vị trí đang chạy): ghi đè toạ độ của nó về (srLat, srLng) hiện tại ngay lần đầu tiên!
     */
    private static void sanitizeLocationOnce(Location loc) {
        if (loc == null) return;
        try {
            Object rawExtras = XposedHelpers.getObjectField(loc, "mExtras");
            if (rawExtras instanceof Bundle) {
                Bundle b = (Bundle) rawExtras;
                if (b.containsKey("sr_speed_ms")) {
                    double lat = b.getDouble("sr_lat", 0.0);
                    double lng = b.getDouble("sr_lng", 0.0);
                    if (lat != 0.0 && lng != 0.0) {
                        srLat = lat;
                        srLng = lng;
                    }
                    srBearing = b.getFloat("sr_bearing", srBearing);
                    srAcc = b.getFloat("sr_acc", srAcc);
                    currentSpeedMs = b.getFloat("sr_speed_ms", 0.0f);
                    int steps = b.getInt("sr_steps", -1);
                    syncEngineSteps(steps);
                    simActive = true;
                    lastSignalElapsedMs = SystemClock.elapsedRealtime();
                    b.remove("sr_lat");
                    b.remove("sr_lng");
                    b.remove("sr_bearing");
                    b.remove("sr_acc");
                    b.remove("sr_speed_ms");
                    b.remove("sr_steps");
                    b.remove("sr_cadence");
                    b.remove("mockLocation");
                    verifiedLocations.add(loc);
                    return;
                }
            }

            if (verifiedLocations.contains(loc)) {
                return;
            }
            verifiedLocations.add(loc);

            String provider = loc.getProvider();
            boolean isRealProviderFix = provider != null && !provider.isEmpty();
            if (isRealProviderFix && isSimulationActiveNow() && srLat != 0.0 && srLng != 0.0) {
                // Đọc trực tiếp qua hàm gốc bằng cách kiểm tra khoảng cách: vì loc đã nằm trong verifiedLocations
                // nên lời gọi getLatitude()/getLongitude() bên dưới sẽ return ngay ở dòng verifiedLocations.contains(loc)
                double lat = loc.getLatitude();
                double lng = loc.getLongitude();
                double dMeters = fastDistanceMeters(lat, lng, srLat, srLng);
                if (dMeters > 22.0) {
                    // Điểm GPS lạ (nhảy về phòng KTX khi chuyển app hoặc do Wi-Fi scan) -> ép về vị trí đang chạy!
                    loc.setLatitude(srLat);
                    loc.setLongitude(srLng);
                    loc.setSpeed(currentSpeedMs);
                    loc.setBearing(srBearing);
                    if (srAcc > 0.0f) {
                        loc.setAccuracy(srAcc);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static double fastDistanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
        return 6378137.0 * c;
    }

    private void hookSettingsAntiDetection() {
        try {
            XposedHelpers.findAndHookMethod(
                    Settings.Secure.class,
                    "getString",
                    ContentResolver.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if ("mock_location".equals(param.args[1])) {
                                param.setResult("0");
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(
                    Settings.Secure.class,
                    "getInt",
                    ContentResolver.class,
                    String.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if ("mock_location".equals(param.args[1])) {
                                param.setResult(0);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private void hookApplicationReceiverForImuSync() {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Application app = (Application) param.thisObject;
                            registerImuSyncReceiverIfNeeded(app);
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void registerImuSyncReceiverIfNeeded(Context ctx) {
        if (receiverRegistered || ctx == null) return;
        try {
            Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    boolean active = intent.getBooleanExtra("active", false);
                    simActive = active;
                    if (active) {
                        lastSignalElapsedMs = SystemClock.elapsedRealtime();
                        double lat = intent.getDoubleExtra("lat", 0.0);
                        double lng = intent.getDoubleExtra("lng", 0.0);
                        if (lat != 0.0 && lng != 0.0) {
                            srLat = lat;
                            srLng = lng;
                        }
                        srBearing = intent.getFloatExtra("bearing", srBearing);
                        srAcc = intent.getFloatExtra("acc", srAcc);
                        currentSpeedMs = intent.getFloatExtra("speedMs", 0.0f);
                        int steps = intent.getIntExtra("steps", -1);
                        syncEngineSteps(steps);
                    } else {
                        currentSpeedMs = 0.0f;
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.smoothrunner.gps.IMU_SYNC");
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(receiver, filter, 2);
            } else {
                appCtx.registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private void hookImuAndStepSensors() {
        try {
            XposedBridge.hookAllMethods(
                    SensorManager.class,
                    "registerListener",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 2) return;
                            if (!(param.args[0] instanceof SensorEventListener)) return;
                            if (!(param.args[1] instanceof Sensor)) return;

                            SensorEventListener original = (SensorEventListener) param.args[0];
                            Sensor sensor = (Sensor) param.args[1];
                            if (original == null || sensor == null) return;
                            if (original instanceof DelegatingSensorEventListener) return;

                            SensorEventListener proxy = wrappedListeners.get(original);
                            if (proxy == null) {
                                proxy = new DelegatingSensorEventListener(original);
                                wrappedListeners.put(original, proxy);
                            }
                            param.args[0] = proxy;

                            int type = sensor.getType();
                            if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) {
                                stepListeners.add(new StepListenerEntry(original, sensor));
                                ensureSyntheticStepDispatcherRunning();
                            }
                        }
                    });

            XposedBridge.hookAllMethods(
                    SensorManager.class,
                    "unregisterListener",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 1) return;
                            if (param.args[0] instanceof SensorEventListener) {
                                SensorEventListener original = (SensorEventListener) param.args[0];
                                SensorEventListener proxy = wrappedListeners.get(original);
                                if (proxy != null) {
                                    param.args[0] = proxy;
                                }
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void ensureSyntheticStepDispatcherRunning() {
        if (stepTimerRunning) return;
        stepTimerRunning = true;
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        Runnable stepTask = new Runnable() {
            @Override
            public void run() {
                try {
                    if (isSimulationActiveNow() && currentSpeedMs >= 0.18f) {
                        advanceImuPhysicsIfNeeded();
                        int currentStepInt = (int) Math.floor(syntheticSteps);
                        if (currentStepInt > lastDispatchedStepInt) {
                            int delta = Math.min(5, currentStepInt - lastDispatchedStepInt);
                            lastDispatchedStepInt = currentStepInt;
                            dispatchSyntheticStepToListeners(currentStepInt, delta);
                        }
                    }
                } catch (Throwable ignored) {
                }
                mainHandler.postDelayed(this, 120L);
            }
        };
        mainHandler.postDelayed(stepTask, 120L);
    }

    private static void dispatchSyntheticStepToListeners(int stepCount, int deltaSteps) {
        for (StepListenerEntry entry : stepListeners) {
            try {
                if (entry.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                    SensorEvent ev = createSensorEvent(entry.sensor, 1);
                    if (ev != null) {
                        float base = hardwareBaseSteps >= 0.0f ? hardwareBaseSteps : 1000.0f;
                        ev.values[0] = base + stepCount;
                        entry.listener.onSensorChanged(ev);
                    }
                } else {
                    for (int i = 0; i < deltaSteps; i++) {
                        SensorEvent ev = createSensorEvent(entry.sensor, 1);
                        if (ev != null) {
                            ev.values[0] = 1.0f;
                            entry.listener.onSensorChanged(ev);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static SensorEvent createSensorEvent(Sensor sensor, int valueCount) {
        try {
            Constructor<SensorEvent> ctor = SensorEvent.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            SensorEvent ev = ctor.newInstance(valueCount);
            ev.sensor = sensor;
            ev.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
            ev.timestamp = SystemClock.elapsedRealtimeNanos();
            return ev;
        } catch (Throwable t1) {
            try {
                Constructor<SensorEvent> ctor0 = SensorEvent.class.getDeclaredConstructor();
                ctor0.setAccessible(true);
                SensorEvent ev = ctor0.newInstance();
                Field valuesField = SensorEvent.class.getDeclaredField("values");
                valuesField.setAccessible(true);
                valuesField.set(ev, new float[valueCount]);
                ev.sensor = sensor;
                ev.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
                ev.timestamp = SystemClock.elapsedRealtimeNanos();
                return ev;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /**
     * Khắc phục triệt để 2 lỗi cố hữu của vRace Connect (fpt.vne.vrace.vrace_connect.model.Exercise):
     * 1. Khi bấm Tạm dừng (Pause) để Lưu bài chạy, vRace tự ghi đè startingLatitudeInDegree / startingLongitudeInDegree
     *    bằng toạ độ lúc bấm Pause (khiến starting_latlng bị lệch so với điểm đầu tiên của track GPS).
     *    -> Tự động khoá startingLatitudeInDegree / startingLongitudeInDegree đúng bằng toạ độ điểm đầu tiên trong lapse!
     * 2. Cứ mỗi khi qua mốc split 1.00 km, vRace reset biến step tổng về 0 (khiến bài chạy 1.02 km chỉ lưu step = 29).
     *    -> Tự động cộng tổng "steps" của tất cả các chặng trong lapse và gán vào trường step của Exercise!
     */
    private void hookVraceExerciseModel(ClassLoader classLoader) {
        try {
            XC_MethodHook getterNormalizer = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    normalizeVraceExerciseEntity(param.thisObject);
                }
            };

            XposedHelpers.findAndHookMethod(
                    "fpt.vne.vrace.vrace_connect.model.Exercise",
                    classLoader,
                    "getStartTimeInSeconds",
                    getterNormalizer);
            XposedHelpers.findAndHookMethod(
                    "fpt.vne.vrace.vrace_connect.model.Exercise",
                    classLoader,
                    "getStartingLatitudeInDegree",
                    getterNormalizer);
            XposedHelpers.findAndHookMethod(
                    "fpt.vne.vrace.vrace_connect.model.Exercise",
                    classLoader,
                    "getStartingLongitudeInDegree",
                    getterNormalizer);
            XposedHelpers.findAndHookMethod(
                    "fpt.vne.vrace.vrace_connect.model.Exercise",
                    classLoader,
                    "getStep",
                    getterNormalizer);
            XposedHelpers.findAndHookMethod(
                    "fpt.vne.vrace.vrace_connect.model.Exercise",
                    classLoader,
                    "getLapse",
                    getterNormalizer);
        } catch (Throwable ignored) {
        }
    }

    private static void normalizeVraceExerciseEntity(Object exerciseObj) {
        if (exerciseObj == null) return;
        try {
            Class<?> cls = exerciseObj.getClass();
            Field lapseField = cls.getDeclaredField("lapse");
            lapseField.setAccessible(true);
            String lapse = (String) lapseField.get(exerciseObj);
            if (lapse == null || lapse.isEmpty()) return;

            // 1. Lấy toạ độ đầu tiên của chặng 1 (latitude / longitude đầu tiên trong coordinates) làm startingLatitude / startingLongitude chuẩn
            int latKeyIdx = lapse.indexOf("latitude");
            if (latKeyIdx >= 0) {
                int lngKeyIdx = lapse.indexOf("longitude", latKeyIdx);
                if (lngKeyIdx >= 0) {
                    float firstLat = parseFloatAfterKey(lapse, latKeyIdx + 8);
                    float firstLng = parseFloatAfterKey(lapse, lngKeyIdx + 9);
                    if (firstLat > 8.0f && firstLat < 24.0f && firstLng > 102.0f && firstLng < 110.0f) {
                        Field fStartLat = cls.getDeclaredField("startingLatitudeInDegree");
                        Field fStartLng = cls.getDeclaredField("startingLongitudeInDegree");
                        fStartLat.setAccessible(true);
                        fStartLng.setAccessible(true);
                        fStartLat.setFloat(exerciseObj, firstLat);
                        fStartLng.setFloat(exerciseObj, firstLng);
                    }
                }
            }

            // 2. Cộng tổng số bước ("steps":N) của tất cả các chặng (laps) trong lapse để cột step luôn khớp 100%
            int totalStepsFromLaps = 0;
            String stepToken = "\"steps\":";
            int searchFrom = 0;
            while (true) {
                int idx = lapse.indexOf(stepToken, searchFrom);
                if (idx < 0) break;
                int numStart = idx + stepToken.length();
                while (numStart < lapse.length() && lapse.charAt(numStart) == ' ') {
                    numStart++;
                }
                int numEnd = numStart;
                while (numEnd < lapse.length() && Character.isDigit(lapse.charAt(numEnd))) {
                    numEnd++;
                }
                if (numEnd > numStart) {
                    totalStepsFromLaps += Integer.parseInt(lapse.substring(numStart, numEnd));
                }
                searchFrom = numEnd;
            }
            if (totalStepsFromLaps > 0) {
                Field fStep = cls.getDeclaredField("step");
                fStep.setAccessible(true);
                int currentStep = fStep.getInt(exerciseObj);
                if (currentStep != totalStepsFromLaps) {
                    fStep.setInt(exerciseObj, totalStepsFromLaps);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static float parseFloatAfterKey(String s, int fromIdx) {
        int i = fromIdx;
        int limit = Math.min(s.length(), fromIdx + 16);
        while (i < limit) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-') break;
            i++;
        }
        int end = i;
        while (end < s.length()) {
            char c = s.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == 'E' || c == 'e') {
                end++;
            } else {
                break;
            }
        }
        if (end > i) {
            return Float.parseFloat(s.substring(i, end));
        }
        return 0.0f;
    }
}
