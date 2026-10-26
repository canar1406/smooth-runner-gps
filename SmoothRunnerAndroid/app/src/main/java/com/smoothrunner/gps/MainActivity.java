package com.smoothrunner.gps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * MainActivity (Root + LSPosed Anti-Detection CLI Terminal cho Android):
 * Tích hợp sẵn:
 * 1. Tự động xin quyền Root (su) để bật appops mock_location ngầm mà không cần khai báo trong Manifest.
 * 2. Tích hợp sẵn Module LSPosed (XposedHook) ngay trong APK để xoá cờ Location.isMock() / isFromMockProvider()
 *    giúp vượt qua kiểm tra của vRace Connect ngay khi bấm Start!
 */
public class MainActivity extends Activity implements MockGpsService.SimulationListener {

    private static final int REQ_LOCATION_PERMS = 101;

    /**
     * Được XposedHook tự động hook trả về true khi người dùng đã bật module trong LSPosed
     */
    public static boolean isLSPosedModuleActive() {
        return false;
    }

    private MockGpsService mockService;
    private boolean serviceBound = false;
    private LocationManager locationManager;

    private volatile double roomLat = 10.881915;
    private volatile double roomLng = 106.809219;
    private volatile boolean hasHardwareGpsFix = false;

    private TextView tvHeaderTelemetry;
    private TextView tvConsoleLog;
    private ScrollView scrollConsole;
    private EditText etCommandInput;

    private final LinkedList<String> logHistory = new LinkedList<>();
    private int lastLoggedTier = -1;
    private String lastLoggedStreet = "";
    private boolean lastLoggedLeadIn = true;
    private boolean lastLoggedCapReached = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MockGpsService.LocalBinder binder = (MockGpsService.LocalBinder) service;
            mockService = binder.getService();
            mockService.setSimulationListener(MainActivity.this);
            serviceBound = true;
            if (!mockService.isSimulationRunning()) {
                fetchRealHardwareLocation();
            }
            if (isMockLocationGranted()) {
                checkMockPermissionAndLog();
            } else {
                new Thread(() -> {
                    boolean rootOk = mockService.grantRootMockPermissionQuietly();
                    runOnUiThread(() -> {
                        if (rootOk) {
                            appendLog("[ROOT] Đã cấp quyền Root (su) & bật appops mock_location ngầm thành công!");
                        }
                        checkMockPermissionAndLog();
                    });
                }).start();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            mockService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        buildTerminalConsoleLayout();

        appendLog("========================================================");
        appendLog(" SMOOTH RUNNER CLI — GPS + CHIP IMU SYNC v1.6.3 (PAUSE/HOLD)");
        appendLog("========================================================");
        appendLog(String.format(Locale.US, "[INIT] Phòng mặc định: %.6f, %.6f (KTX Khu A - ĐHQG)", roomLat, roomLng));
        if (isLSPosedModuleActive()) {
            appendLog("[OK] LSPosed Module: ĐÃ KÍCH HOẠT (Đã ẩn Mock GPS + Giả lập rung Chip IMU tự động)!");
        } else {
            appendLog("[BYPASS vRace Connect] APK tích hợp sẵn Giả lập Chip IMU + Ẩn Mock GPS:");
            appendLog(" -> Mở LSPosed -> Bật module 'SmoothRunner GPS'");
            appendLog(" -> Tích chọn 'Hệ thống (System Framework)' và 'vRace Connect'");
            appendLog(" -> Không cần lắc tay! Máy để im trên bàn vẫn tự tạo nhịp bước chân IMU khớp tốc độ chạy!");
        }

        requestRequiredPermissions();

        Intent serviceIntent = new Intent(this, MockGpsService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void buildTerminalConsoleLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#080C14"));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        tvHeaderTelemetry = new TextView(this);
        tvHeaderTelemetry.setTypeface(Typeface.MONOSPACE);
        tvHeaderTelemetry.setTextSize(12.0f);
        tvHeaderTelemetry.setTextColor(Color.parseColor("#10B981"));
        tvHeaderTelemetry.setBackgroundColor(Color.parseColor("#0F172A"));
        tvHeaderTelemetry.setPadding(dp(10), dp(10), dp(10), dp(10));
        tvHeaderTelemetry.setText(buildIdleTelemetryText());

        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.bottomMargin = dp(8);
        root.addView(tvHeaderTelemetry, headerParams);

        scrollConsole = new ScrollView(this);
        scrollConsole.setBackgroundColor(Color.parseColor("#05080F"));
        scrollConsole.setPadding(dp(10), dp(8), dp(10), dp(8));

        tvConsoleLog = new TextView(this);
        tvConsoleLog.setTypeface(Typeface.MONOSPACE);
        tvConsoleLog.setTextSize(11.5f);
        tvConsoleLog.setTextColor(Color.parseColor("#E2E8F0"));
        scrollConsole.addView(tvConsoleLog);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollParams.bottomMargin = dp(8);
        root.addView(scrollConsole, scrollParams);

        LinearLayout quickBar = new LinearLayout(this);
        quickBar.setOrientation(LinearLayout.HORIZONTAL);
        quickBar.addView(createCliButton("▶ start", "#10B981", v -> executeCliCommand("start")));
        quickBar.addView(createCliButton("⏸ pause", "#F59E0B", v -> executeCliCommand("pause")));
        quickBar.addView(createCliButton("🎲 random", "#3B82F6", v -> executeCliCommand("random")));
        quickBar.addView(createCliButton("⏹ stop", "#EF4444", v -> executeCliCommand("stop")));

        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        quickParams.bottomMargin = dp(6);
        root.addView(quickBar, quickParams);

        LinearLayout promptRow = new LinearLayout(this);
        promptRow.setOrientation(LinearLayout.HORIZONTAL);
        promptRow.setGravity(Gravity.CENTER_VERTICAL);
        promptRow.setBackgroundColor(Color.parseColor("#0F172A"));
        promptRow.setPadding(dp(10), dp(4), dp(6), dp(4));

        TextView promptSign = new TextView(this);
        promptSign.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        promptSign.setTextColor(Color.parseColor("#10B981"));
        promptSign.setTextSize(14f);
        promptSign.setText("# ");
        promptRow.addView(promptSign);

        etCommandInput = new EditText(this);
        etCommandInput.setTypeface(Typeface.MONOSPACE);
        etCommandInput.setTextColor(Color.WHITE);
        etCommandInput.setHintTextColor(Color.parseColor("#64748B"));
        etCommandInput.setHint("Lệnh: start | pause | stop | random | tier 1..5 | max km");
        etCommandInput.setTextSize(12.5f);
        etCommandInput.setSingleLine(true);
        etCommandInput.setBackgroundColor(Color.TRANSPARENT);
        etCommandInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        etCommandInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                String cmd = etCommandInput.getText().toString().trim();
                etCommandInput.setText("");
                if (!cmd.isEmpty()) {
                    executeCliCommand(cmd);
                }
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        promptRow.addView(etCommandInput, inputParams);

        Button btnRunCmd = new Button(this);
        btnRunCmd.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnRunCmd.setText("RUN");
        btnRunCmd.setTextSize(11f);
        btnRunCmd.setTextColor(Color.WHITE);
        btnRunCmd.setBackgroundColor(Color.parseColor("#1E293B"));
        btnRunCmd.setOnClickListener(v -> {
            String cmd = etCommandInput.getText().toString().trim();
            etCommandInput.setText("");
            if (!cmd.isEmpty()) {
                executeCliCommand(cmd);
            }
        });
        promptRow.addView(btnRunCmd);

        root.addView(promptRow);
        setContentView(root);
    }

    private Button createCliButton(String label, String hexColor, android.view.View.OnClickListener listener) {
        Button b = new Button(this);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setText(label);
        b.setTextSize(11f);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#1E293B"));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private void executeCliCommand(String rawCmd) {
        appendLog("# " + rawCmd);
        String cmd = rawCmd.toLowerCase(Locale.US).trim();

        if ("start".equals(cmd) || "run".equals(cmd) || "random".equals(cmd)) {
            Intent serviceIntent = new Intent(this, MockGpsService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            if (mockService != null) {
                lastLoggedTier = -1;
                lastLoggedStreet = "";
                lastLoggedLeadIn = true;
                lastLoggedCapReached = false;
                mockService.scanAndStartFromRoomAsync(roomLat, roomLng, 3200.0);
            }
        } else if ("pause".equals(cmd) || "hold".equals(cmd)) {
            if (mockService != null && mockService.isSimulationRunning()) {
                boolean nowPaused = mockService.getEngine().toggleManualHoldPaused();
                if (nowPaused) {
                    appendLog(String.format(Locale.US,
                            "[PAUSE] Đã giữ vị trí tại '%s' (%.6f, %.6f) + giả lập nhích chân tại chỗ! Bạn có thể sang vRace bấm Lưu phiên chạy, sau đó bấm '▶ start' để mở phiên mới ngay từ đây (hoặc bấm '⏸ pause' để chạy tiếp).",
                            mockService.getEngine().getCurrentStreetName(),
                            mockService.getEngine().getCurrentLat(),
                            mockService.getEngine().getCurrentLng()));
                } else {
                    appendLog("[RESUME] Đã tiếp tục chạy phiên hiện tại từ vị trí vừa Pause!");
                }
            } else {
                appendLog("[INFO] Chưa có phiên chạy nào đang hoạt động. Bấm '▶ start' để bắt đầu!");
            }
        } else if ("resume".equals(cmd) || "continue".equals(cmd)) {
            if (mockService != null && mockService.isSimulationRunning()) {
                mockService.getEngine().setManualHoldPaused(false);
                appendLog("[RESUME] Đã tiếp tục chạy từ vị trí vừa Pause!");
            }
        } else if ("stop".equals(cmd)) {
            if (mockService != null) {
                mockService.stopSimulation();
                appendLog("[STOP] Đã tắt hẳn Spoof GPS và đưa điểm xuất phát về lại phòng KTX AH2.");
                tvHeaderTelemetry.setText(buildIdleTelemetryText());
            }
        } else if ("root".equals(cmd) || "mock".equals(cmd)) {
            new Thread(() -> {
                boolean ok = mockService != null && mockService.grantRootMockPermissionQuietly();
                runOnUiThread(() -> {
                    if (ok) {
                        appendLog("[ROOT] Đã chạy lệnh su -c 'appops set ... mock_location allow' thành công!");
                        tvHeaderTelemetry.setText(buildIdleTelemetryText());
                    } else {
                        appendLog("[WARN] Không gọi được su, đang mở Developer Options...");
                        openDeveloperMockSettings();
                    }
                });
            }).start();
        } else if ("rest".equals(cmd)) {
            if (mockService != null) {
                mockService.getEngine().forceTargetTier(0);
                appendLog("[CMD] Chuyển sang trạng thái Nghỉ (hạ dần từng nấc về Nấc 0).");
            }
        } else if (cmd.startsWith("tier ")) {
            try {
                int lvl = Integer.parseInt(cmd.replace("tier", "").trim());
                if (mockService != null) {
                    mockService.getEngine().forceTargetTier(lvl);
                    appendLog("[CMD] Đặt nấc vận tốc đích = Mức " + lvl + " (chuyển mượt từng nấc 1).");
                }
            } catch (Exception e) {
                appendLog("[ERROR] Cú pháp đúng: tier 1 | tier 2 | tier 3 | tier 4 | tier 5");
            }
        } else if (cmd.startsWith("max ")) {
            try {
                double km = Double.parseDouble(cmd.replace("max", "").trim());
                if (mockService != null) {
                    mockService.getEngine().setMaxSessionDistanceKm(km);
                    appendLog(String.format(Locale.US, "[CMD] Đã đặt giới hạn mỗi phiên = %.2f km (đạt mốc sẽ tự dừng chờ lưu).", km));
                }
            } catch (Exception e) {
                appendLog("[ERROR] Cú pháp đúng: max 5 (giới hạn 5km mỗi phiên)");
            }
        } else if (cmd.startsWith("gps ")) {
            try {
                String[] parts = cmd.replace("gps", "").trim().split("[,\\s]+");
                roomLat = Double.parseDouble(parts[0]);
                roomLng = Double.parseDouble(parts[1]);
                appendLog(String.format(Locale.US, "[GPS] Đã cập nhật toạ độ phòng: %.6f, %.6f", roomLat, roomLng));
                tvHeaderTelemetry.setText(buildIdleTelemetryText());
            } catch (Exception e) {
                appendLog("[ERROR] Cú pháp đúng: gps 10.875647 106.804879");
            }
        } else {
            appendLog("Lệnh hỗ trợ: start | stop | random | tier 1..5 | rest | root | gps lat lng");
        }
    }

    private String buildIdleTelemetryText() {
        String fixTag = hasHardwareGpsFix ? "GPS Thật Đã Khớp" : "Mặc định KTX Khu A ĐHQG";
        String mockTag = isMockLocationGranted() ? "ĐÃ BẬT (ROOT/APPOPS READY)" : "CHƯA BẬT (Bấm '🔑 root')";
        String lsposedTag = isLSPosedModuleActive()
                ? "ĐÃ BẬT (Ẩn Mock GPS + Đồng bộ Chip IMU)"
                : "CHƯA BẬT (Bật trong LSPosed cho vRace Connect)";
        return String.format(Locale.US,
                "STATUS   : IDLE (GPS + IMU Pedometer Sync v1.5)\n" +
                "MOCK PERM: %s\n" +
                "LSPOSED  : %s\n" +
                "PHÒNG KTX: %.6f, %.6f (%s)\n" +
                "CHIP IMU : Tự động giả lập nhịp lắc bước chân theo tốc độ chạy",
                mockTag, lsposedTag, roomLat, roomLng, fixTag);
    }

    private void checkMockPermissionAndLog() {
        if (isMockLocationGranted()) {
            appendLog("[OK] Quyền Mock Location: ĐÃ BẬT NGẦM QUA ROOT. Bấm '▶ start' để chạy!");
        } else {
            appendLog("[INFO] Bấm '🔑 root' hoặc '▶ start' để tự cấp quyền qua Root (Magisk/KernelSU).");
        }
        tvHeaderTelemetry.setText(buildIdleTelemetryText());
    }

    private boolean isMockLocationGranted() {
        try {
            AppOpsManager opsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (opsManager != null) {
                int mode = opsManager.checkOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(),
                        getPackageName());
                return mode == AppOpsManager.MODE_ALLOWED;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void openDeveloperMockSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            appendLog("[ERROR] Hãy cấp quyền Root trong Magisk/KernelSU!");
        }
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    perms.add("android.permission.POST_NOTIFICATIONS");
                }
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), REQ_LOCATION_PERMS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION_PERMS) {
            fetchRealHardwareLocation();
        }
    }

    @SuppressLint("MissingPermission")
    private void fetchRealHardwareLocation() {
        if (locationManager == null) return;
        try {
            if (mockService != null && mockService.isSimulationRunning()) return;
            Location gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location best = gpsLoc != null ? gpsLoc : netLoc;
            if (best != null && !best.isFromMockProvider()
                    && SidewalkRouteGenerator.distanceMeters(best.getLatitude(), best.getLongitude(), 10.881915, 106.809219) < 65.0) {
                roomLat = best.getLatitude();
                roomLng = best.getLongitude();
                hasHardwareGpsFix = true;
                appendLog(String.format(Locale.US, "[GPS] Đã lấy vị trí phòng thực tế từ máy: %.6f, %.6f", roomLat, roomLng));
                tvHeaderTelemetry.setText(buildIdleTelemetryText());
            }

            LocationListener oneShot = new LocationListener() {
                @Override
                public void onLocationChanged(Location loc) {
                    if (loc != null && !loc.isFromMockProvider()
                            && (mockService == null || !mockService.isSimulationRunning())
                            && SidewalkRouteGenerator.distanceMeters(loc.getLatitude(), loc.getLongitude(), 10.881915, 106.809219) < 65.0) {
                        roomLat = loc.getLatitude();
                        roomLng = loc.getLongitude();
                        hasHardwareGpsFix = true;
                        runOnUiThread(() -> {
                            appendLog(String.format(Locale.US, "[GPS] Cập nhật GPS phòng chính xác: %.6f, %.6f", roomLat, roomLng));
                            tvHeaderTelemetry.setText(buildIdleTelemetryText());
                        });
                        locationManager.removeUpdates(this);
                    }
                }
                @Override public void onStatusChanged(String p, int s, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, oneShot);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1, oneShot);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSimulationTick(final RunningSimulatorEngine.SimulationSnapshot snap) {
        runOnUiThread(() -> {
            if (snap == null) return;

            String phaseStr;
            if (snap.isManualHoldPaused && !snap.isSessionDistanceCapReached) {
                phaseStr = "⏸ PAUSE TẠI CHỖ (Nhích chân tự nhiên - Lưu vRace / Bấm start mở phiên mới từ đây)";
            } else if (snap.isSessionDistanceCapReached) {
                phaseStr = String.format(Locale.US, "ĐÃ ĐẠT %.2f KM (Nhích nhẹ chờ Lưu / Bấm start mở phiên mới từ đây)",
                        snap.maxSessionDistanceMeters / 1000.0);
            } else if (snap.isInLeadInFromRoom) {
                phaseStr = "TỪ PHÒNG RA VỈA HÈ (Đi bộ Mức 1)";
            } else if (snap.isRestingPhase) {
                phaseStr = String.format(Locale.US, "PHA NGHỈ (Còn %02d:%02d)",
                        (int) (snap.phaseRemainingSeconds / 60), (int) (snap.phaseRemainingSeconds % 60));
            } else {
                phaseStr = String.format(Locale.US, "PHA CHẠY (Còn %02d:%02d)",
                        (int) (snap.phaseRemainingSeconds / 60), (int) (snap.phaseRemainingSeconds % 60));
            }

            String ladderStr;
            if (snap.currentTierLevel == snap.ultimateTargetTierLevel) {
                ladderStr = String.format(Locale.US, "Mức %d (%s)", snap.currentTierLevel, snap.currentTierName);
            } else {
                String arrow = snap.currentTierLevel < snap.ultimateTargetTierLevel ? "↗" : "↘";
                ladderStr = String.format(Locale.US, "Mức %d %s Mức %d (%s)",
                        snap.currentTierLevel, arrow, snap.ultimateTargetTierLevel, snap.currentTierName);
            }

            String bar = buildAsciiSpeedBar(snap.currentSpeedKmh, 16);
            String telemetry = String.format(Locale.US,
                    "GPS MOCK : %.6f, %.6f (±%.1fm)\n" +
                    "VẬN TỐC  : %5.2f km/h [%s] (Đích: %.1f)\n" +
                    "CHIP IMU : %d bước | %d bước/phút (Sải: %.2fm)\n" +
                    "BẬC THANG: %s\n" +
                    "CHU KỲ   : %s | %.1fm / %.1fm\n" +
                    "VỊ TRÍ   : %s",
                    snap.lat, snap.lng, snap.accuracyMeters,
                    snap.currentSpeedKmh, bar, snap.targetSpeedKmh,
                    snap.totalSteps, snap.cadenceSpm, snap.strideLengthMeters,
                    ladderStr,
                    phaseStr, snap.totalDistanceMeters, snap.maxSessionDistanceMeters,
                    snap.currentStreetName);
            tvHeaderTelemetry.setText(telemetry);

            if (lastLoggedLeadIn && !snap.isInLeadInFromRoom) {
                lastLoggedLeadIn = false;
                appendLog(String.format(Locale.US,
                        "[VỈA HÈ] Đã bước từ phòng ra tới '%s'! Bắt đầu chu kỳ Chạy -> Đích Mức %d",
                        snap.currentStreetName, snap.ultimateTargetTierLevel));
            }

            if (snap.currentTierLevel != lastLoggedTier) {
                lastLoggedTier = snap.currentTierLevel;
                appendLog(String.format(Locale.US,
                        "[TIER] Đang ở Mức %d (%s) -> Đích Mức %d | Vận tốc: %.2f km/h",
                        snap.currentTierLevel, snap.currentTierName,
                        snap.ultimateTargetTierLevel, snap.currentSpeedKmh));
            }

            if (snap.isSessionDistanceCapReached && !lastLoggedCapReached) {
                lastLoggedCapReached = true;
                appendLog(String.format(Locale.US,
                        "[FINISH] Đã hoàn thành đủ %.2f km (%.1f m) ngẫu nhiên của phiên chạy! Đã dừng tại chỗ chờ bạn Lưu kết quả trên vRace (Bấm '▶ start' khi muốn bắt đầu phiên mới).",
                        snap.maxSessionDistanceMeters / 1000.0, snap.maxSessionDistanceMeters));
            }

            if (snap.currentStreetName != null && !snap.currentStreetName.equals(lastLoggedStreet)) {
                lastLoggedStreet = snap.currentStreetName;
                appendLog("[ROUTE] Đang di chuyển trên: " + snap.currentStreetName);
            }
        });
    }

    @Override
    public void onCliLog(final String logLine) {
        runOnUiThread(() -> appendLog(logLine));
    }

    @Override
    public void onMockError(final String errorMessage) {
        runOnUiThread(() -> appendLog("[ERROR] " + errorMessage));
    }

    private void appendLog(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logHistory.add("[" + ts + "] " + line);
        while (logHistory.size() > 120) {
            logHistory.removeFirst();
        }
        StringBuilder sb = new StringBuilder();
        for (String s : logHistory) {
            sb.append(s).append("\n");
        }
        if (tvConsoleLog != null) {
            tvConsoleLog.setText(sb.toString());
            scrollConsole.post(() -> scrollConsole.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private String buildAsciiSpeedBar(double speedKmh, int width) {
        double ratio = Math.max(0.0, Math.min(1.0, speedKmh / 16.0));
        int filled = (int) Math.round(ratio * width);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? "█" : "░");
        }
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tvHeaderTelemetry != null && (mockService == null || !mockService.isSimulationRunning())) {
            tvHeaderTelemetry.setText(buildIdleTelemetryText());
        }
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            if (mockService != null) {
                mockService.setSimulationListener(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }
}
