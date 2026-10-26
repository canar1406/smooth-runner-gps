package com.smoothrunner.gps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * MockGpsService (Root + LSPosed Anti-Detection Edition):
 * 1. Tự động dùng quyền Root (su -c) để bật appops android:mock_location allow và xoá biến
 *    settings secure mock_location = 0 mà không cần khai báo ACCESS_MOCK_LOCATION trong Manifest.
 * 2. Bơm đồng thời vào cả 3 Provider: GPS_PROVIDER ("gps"), NETWORK_PROVIDER ("network"), và FUSED ("fused").
 * 3. Xoá cờ isMock / isFromMockProvider bằng Reflection + kết hợp Module XposedHook tích hợp sẵn.
 */
public class MockGpsService extends Service {

    private static final String CHANNEL_ID = "smooth_runner_gps_channel";
    private static final int NOTIFICATION_ID = 1001;

    public interface SimulationListener {
        void onSimulationTick(RunningSimulatorEngine.SimulationSnapshot snapshot);
        void onCliLog(String logLine);
        void onMockError(String errorMessage);
    }

    public class LocalBinder extends Binder {
        public MockGpsService getService() {
            return MockGpsService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final RunningSimulatorEngine engine = new RunningSimulatorEngine();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> simulationTask;
    private PowerManager.WakeLock wakeLock;
    private LocationManager locationManager;
    private SimulationListener listener;

    private volatile boolean isRunning = false;
    private volatile boolean providersInitialized = false;
    private volatile String lastErrorMessage = null;
    private volatile RunningSimulatorEngine.SimulationSnapshot latestSnapshot = null;
    private int notificationTickCounter = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (action == null) {
                action = intent.getStringExtra("action");
            }
            if ("STOP_SIMULATION".equals(action) || "CLI_STOP".equals(action)) {
                stopSimulation();
                stopSelf();
                return START_NOT_STICKY;
            }
            if ("CLI_PUSH".equals(action)) {
                startForeground(NOTIFICATION_ID, buildNotification("CLI GPS Spoofer đang điều khiển toạ độ..."));
                if (!providersInitialized) {
                    grantRootMockPermissionQuietly();
                    providersInitialized = setupMockProviders();
                }
                if (providersInitialized) {
                    RunningSimulatorEngine.SimulationSnapshot snap = new RunningSimulatorEngine.SimulationSnapshot();
                    snap.lat = intent.getDoubleExtra("lat", 10.875647);
                    snap.lng = intent.getDoubleExtra("lng", 106.804879);
                    snap.currentSpeedKmh = intent.getDoubleExtra("speedKmh", 0.0);
                    snap.bearingDegrees = intent.getFloatExtra("bearing", 0.0f);
                    snap.altitudeMeters = intent.getDoubleExtra("alt", 8.5);
                    snap.accuracyMeters = intent.getFloatExtra("acc", 3.8f);
                    pushAllProviders(snap);
                }
                return START_STICKY;
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification("Sẵn sàng Spoof GPS (Root/LSPosed)..."));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setSimulationListener(SimulationListener listener) {
        this.listener = listener;
    }

    public RunningSimulatorEngine getEngine() {
        return engine;
    }

    public boolean isSimulationRunning() {
        return isRunning;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    /**
     * Dùng quyền Root (su) tự động cấp quyền appops mock_location và ẩn cờ secure mock_location
     */
    public boolean grantRootMockPermissionQuietly() {
        boolean alreadyAllowed = false;
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (ops != null && ops.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(),
                    getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED) {
                alreadyAllowed = true;
            }
        } catch (Exception ignored) {
        }
        try {
            String pkg = getPackageName();
            String cmd = "appops set " + pkg + " android:mock_location allow; "
                    + "settings put secure mock_location 0; "
                    + "appops set fpt.vne.vrace FINE_LOCATION allow; "
                    + "appops set fpt.vne.vrace COARSE_LOCATION allow; "
                    + "appops set fpt.vne.vrace RUN_IN_BACKGROUND allow; "
                    + "appops set fpt.vne.vrace RUN_ANY_IN_BACKGROUND allow; "
                    + "dumpsys deviceidle whitelist +fpt.vne.vrace >/dev/null 2>&1; "
                    + "dumpsys deviceidle whitelist +" + pkg + " >/dev/null 2>&1";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int code = p.waitFor();
            return code == 0 || alreadyAllowed;
        } catch (Exception e) {
            return alreadyAllowed;
        }
    }

    public synchronized boolean startSimulation(
            List<SidewalkRouteGenerator.LatLng> leadIn,
            List<SidewalkRouteGenerator.LatLng> route) {
        lastErrorMessage = null;
        grantRootMockPermissionQuietly();

        if (!setupMockProviders()) {
            return false;
        }

        if (route != null && route.size() >= 2) {
            engine.setLeadInAndSidewalkRoute(leadIn, route);
        }
        engine.startNewRunSession();
        acquireWakeLock();

        startForeground(NOTIFICATION_ID, buildNotification("Đang khởi động chạy bộ từ phòng ra vỉa hè..."));

        if (simulationTask != null && !simulationTask.isCancelled()) {
            simulationTask.cancel(false);
        }

        isRunning = true;
        simulationTask = scheduler.scheduleAtFixedRate(this::tickSimulation, 0, 200, TimeUnit.MILLISECONDS);
        return true;
    }

    public void scanAndStartFromRoomAsync(final double roomLat, final double roomLng, final double radiusMeters) {
        new Thread(() -> {
            SidewalkRouteGenerator.preloadBundledMapIfNeeded(getApplicationContext());
            boolean rootOk = grantRootMockPermissionQuietly();
            if (rootOk) {
                emitLog("[ROOT] Đã tự động cấp quyền appops mock_location & ẩn secure settings qua su!");
            }
            boolean continueFromOutdoorSpot = isRunning && engine.hasActiveOutdoorPosition();
            double startLat = continueFromOutdoorSpot ? engine.getCurrentLat() : roomLat;
            double startLng = continueFromOutdoorSpot ? engine.getCurrentLng() : roomLng;
            String currentSpotStreet = engine.getCurrentStreetName();

            if (continueFromOutdoorSpot) {
                emitLog(String.format(Locale.US,
                        "[CONTINUE] Đang đứng ngoài đường ('%s' @ %.6f, %.6f) -> Mở phiên mới ngay tại chỗ, không nhảy về phòng!",
                        currentSpotStreet, startLat, startLng));
            } else {
                emitLog(String.format(Locale.US,
                        "[OSM] Đang quét mạng lưới đường Làng Đại Học (KTX Khu A ↔ KTX Khu B, bán kính %.0fm)...",
                        radiusMeters));
            }

            SidewalkRouteGenerator.GeneratedPlan plan =
                    SidewalkRouteGenerator.generateFromRoomToRandomSidewalks(
                            startLat, startLng, radiusMeters, 145, !continueFromOutdoorSpot);
            String firstStreet = plan.sidewalkPoints.isEmpty()
                    ? "Vỉa hè nội khu"
                    : plan.sidewalkPoints.get(0).streetName;
            emitLog(String.format(Locale.US, "[OK] Bản đồ: %d nút giao | Lối đi: '%s'",
                    plan.totalIntersectionNodes, firstStreet));
            emitLog("[RANDOM STYLE] " + plan.routeStyleSummary);
            boolean ok = startSimulation(
                    continueFromOutdoorSpot ? null : plan.leadInPoints,
                    plan.sidewalkPoints);
            if (ok) {
                double targetMeters = engine.getMaxSessionDistanceMeters();
                emitLog(String.format(Locale.US,
                        "[TARGET] Đích tự dừng ngẫu nhiên phiên này: %.2f km (%.1f m)",
                        targetMeters / 1000.0, targetMeters));
                if (continueFromOutdoorSpot) {
                    emitLog("[START] Bắt đầu phiên chạy mới ngay từ vị trí vừa Pause ('" + firstStreet + "')!");
                } else {
                    emitLog("[START] Bắt đầu đi bộ từ phòng AH2 -> Cổng chính KTX Khu A -> Chạy theo chế độ ngẫu nhiên...");
                }
            }
        }).start();
    }

    private void emitLog(String msg) {
        if (listener != null) {
            listener.onCliLog(msg);
        }
    }

    public synchronized void stopSimulation() {
        isRunning = false;
        if (simulationTask != null) {
            simulationTask.cancel(false);
            simulationTask = null;
        }
        engine.resetToDormRoom(10.881915, 106.809219);
        broadcastImuSyncState(false, null);
        teardownMockProviders();
        releaseWakeLock();
        stopForeground(true);
    }

    private void broadcastImuSyncState(boolean active, RunningSimulatorEngine.SimulationSnapshot snap) {
        try {
            Intent sync = new Intent("com.smoothrunner.gps.IMU_SYNC");
            sync.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            sync.putExtra("active", active);
            if (active && snap != null) {
                sync.putExtra("lat", snap.lat);
                sync.putExtra("lng", snap.lng);
                sync.putExtra("bearing", snap.bearingDegrees);
                sync.putExtra("acc", snap.accuracyMeters);
                sync.putExtra("speedMs", (float) (snap.currentSpeedKmh / 3.6));
                sync.putExtra("steps", snap.totalSteps);
                sync.putExtra("cadenceSpm", snap.cadenceSpm);
                sync.putExtra("imuX", snap.imuAccelX);
                sync.putExtra("imuY", snap.imuAccelY);
                sync.putExtra("imuZ", snap.imuAccelZ);
            } else {
                sync.putExtra("speedMs", 0.0f);
                sync.putExtra("cadenceSpm", 0);
            }
            sendBroadcast(sync);
        } catch (Exception ignored) {
        }
    }

    private void tickSimulation() {
        if (!isRunning) return;
        try {
            RunningSimulatorEngine.SimulationSnapshot snap = engine.step(0.20);
            latestSnapshot = snap;

            pushAllProviders(snap);
            broadcastImuSyncState(true, snap);

            if (listener != null) {
                listener.onSimulationTick(snap);
            }

            notificationTickCounter++;
            if (notificationTickCounter >= 5) {
                notificationTickCounter = 0;
                int remMin = (int) (snap.phaseRemainingSeconds / 60);
                int remSec = (int) (snap.phaseRemainingSeconds % 60);
                String phaseTag = snap.isInLeadInFromRoom ? "Ra vỉa hè" : (snap.isRestingPhase ? "Nghỉ" : "Chạy");
                String text = String.format(Locale.US,
                        "%s (Mức %d->%d) | %.1f km/h | %s %02d:%02d | %.2f km",
                        snap.currentTierName,
                        snap.currentTierLevel,
                        snap.ultimateTargetTierLevel,
                        snap.currentSpeedKmh,
                        phaseTag, remMin, remSec,
                        snap.totalDistanceMeters / 1000.0);
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.notify(NOTIFICATION_ID, buildNotification(text));
                }
            }
        } catch (SecurityException se) {
            lastErrorMessage = "Chưa có quyền Mock Location! Hãy cấp quyền Root (su) hoặc chọn app trong Developer Options.";
            isRunning = false;
            if (listener != null) {
                listener.onMockError(lastErrorMessage);
            }
        } catch (Exception e) {
            lastErrorMessage = "Lỗi giả lập GPS: " + e.getMessage();
        }
    }

    private void pushAllProviders(RunningSimulatorEngine.SimulationSnapshot snap) {
        pushMockLocation(LocationManager.GPS_PROVIDER, snap);
        pushMockLocation(LocationManager.NETWORK_PROVIDER, snap);
        try {
            pushMockLocation("fused", snap);
        } catch (Exception ignored) {
        }
    }

    private boolean setupMockProviders() {
        try {
            registerSingleTestProvider(LocationManager.GPS_PROVIDER);
            registerSingleTestProvider(LocationManager.NETWORK_PROVIDER);
            try {
                registerSingleTestProvider("fused");
            } catch (Exception ignored) {
            }
            providersInitialized = true;
            return true;
        } catch (SecurityException se) {
            lastErrorMessage = "Hãy cấp quyền Root (su) khi được hỏi hoặc chọn 'SmoothRunner GPS' trong Developer Options!";
            if (listener != null) {
                listener.onMockError(lastErrorMessage);
            }
            return false;
        } catch (Exception e) {
            lastErrorMessage = "Không thể bật Mock Provider: " + e.getMessage();
            if (listener != null) {
                listener.onMockError(lastErrorMessage);
            }
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void registerSingleTestProvider(String providerName) {
        try {
            locationManager.removeTestProvider(providerName);
        } catch (Exception ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            locationManager.addTestProvider(
                    providerName,
                    false, false, false, false,
                    true, true, true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE);
        } else {
            locationManager.addTestProvider(
                    providerName,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE);
        }
        locationManager.setTestProviderEnabled(providerName, true);
    }

    public void pushMockLocation(String provider, RunningSimulatorEngine.SimulationSnapshot snap) {
        Location mockLoc = new Location(provider);
        mockLoc.setLatitude(snap.lat);
        mockLoc.setLongitude(snap.lng);
        mockLoc.setAltitude(snap.altitudeMeters);
        mockLoc.setSpeed((float) (snap.currentSpeedKmh / 3.6));
        mockLoc.setBearing(snap.bearingDegrees);
        mockLoc.setAccuracy(snap.accuracyMeters);
        mockLoc.setTime(System.currentTimeMillis());
        mockLoc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mockLoc.setSpeedAccuracyMetersPerSecond(0.12f);
            mockLoc.setBearingAccuracyDegrees(2.5f);
            mockLoc.setVerticalAccuracyMeters(1.5f);
        }

        // Đặt số vệ tinh GPS giả lập hợp lệ + đồng bộ toạ độ & IMU ngầm cho XposedHook
        Bundle extras = new Bundle();
        extras.putInt("satellites", 11);
        extras.putBoolean("mockLocation", false);
        extras.putDouble("sr_lat", snap.lat);
        extras.putDouble("sr_lng", snap.lng);
        extras.putFloat("sr_bearing", snap.bearingDegrees);
        extras.putFloat("sr_acc", snap.accuracyMeters);
        extras.putFloat("sr_speed_ms", (float) (snap.currentSpeedKmh / 3.6));
        extras.putInt("sr_steps", snap.totalSteps);
        extras.putInt("sr_cadence", snap.cadenceSpm);
        mockLoc.setExtras(extras);

        stripMockFlagReflectively(mockLoc);
        locationManager.setTestProviderLocation(provider, mockLoc);
    }

    private void stripMockFlagReflectively(Location loc) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                Method setMock = Location.class.getDeclaredMethod("setMock", boolean.class);
                setMock.setAccessible(true);
                setMock.invoke(loc, false);
            }
        } catch (Throwable ignored) {
        }
        try {
            Method setFromMock = Location.class.getDeclaredMethod("setIsFromMockProvider", boolean.class);
            setFromMock.setAccessible(true);
            setFromMock.invoke(loc, false);
        } catch (Throwable ignored) {
        }
    }

    private void teardownMockProviders() {
        providersInitialized = false;
        String[] providers = new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"};
        for (String p : providers) {
            try {
                locationManager.setTestProviderEnabled(p, false);
                locationManager.removeTestProvider(p);
            } catch (Exception ignored) {
            }
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmoothRunnerGPS::MockRunWakeLock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SmoothRunner CLI Service",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String contentText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingOpen = PendingIntent.getActivity(this, 0, openIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("SmoothRunner CLI (Root/LSPosed)")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingOpen)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopSimulation();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        super.onDestroy();
    }
}
