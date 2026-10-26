package com.smoothrunner.gps;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * RunningSimulatorEngine:
 * Bộ máy vật lý sinh học (Bio-Physics Engine) mô phỏng người chạy bộ thực tế:
 * 1. Xuất phát từ trong phòng KTX Khu A (0.0 km/h) -> Đi bộ (Mức 1: 3-5 km/h) ra vỉa hè.
 * 2. Khi ra tới vỉa hè: Chạy 3–5 phút (random) <-> Nghỉ 1–5 phút (random).
 * 3. Chuyển tốc độ mượt TỪNG NẤC MỘT (0 <-> 1 <-> 2 <-> 3 <-> 4 <-> 5), không nhảy cóc.
 * 4. Biến thiên vận tốc liên tục mượt mà (C2-Smooth) trong từng nấc.
 */
public class RunningSimulatorEngine {

    public enum SpeedTier {
        REST(0, "Đi bộ hồi sức", 3.8, 4.6),
        NORMAL_WALK(1, "Đi bộ bình thường", 4.5, 5.8),
        FAST_WALK(2, "Đi bộ nhanh", 5.8, 7.2),
        JOGGING(3, "Chạy bộ nhẹ (Jogging)", 7.2, 9.2),
        MEDIUM_RUN(4, "Chạy bộ trung bình", 9.2, 11.2),
        FAST_RUN(5, "Chạy nhanh", 11.2, 13.5);

        public final int level;
        public final String label;
        public final double minKmh;
        public final double maxKmh;

        SpeedTier(int level, String label, double minKmh, double maxKmh) {
            this.level = level;
            this.label = label;
            this.minKmh = minKmh;
            this.maxKmh = maxKmh;
        }

        public static SpeedTier fromLevel(int lvl) {
            for (SpeedTier t : values()) {
                if (t.level == lvl) return t;
            }
            return JOGGING;
        }
    }

    public static class SimulationSnapshot {
        public double lat;
        public double lng;
        public double altitudeMeters;
        public double currentSpeedKmh;
        public double targetSpeedKmh;
        public float bearingDegrees;
        public float accuracyMeters;
        public int currentTierLevel;
        public int ultimateTargetTierLevel;
        public String currentTierName;
        public String targetTierName;
        public String currentStreetName;
        public boolean isInLeadInFromRoom;
        public boolean isRestingPhase;
        public boolean isManualHoldPaused;
        public boolean isSessionDistanceCapReached;
        public double maxSessionDistanceMeters;
        public double phaseRemainingSeconds;
        public double phaseTotalSeconds;
        public double totalDistanceMeters;
        public double totalElapsedSeconds;
        public int completedLaps;
        public int totalSteps;
        public int cadenceSpm;
        public double strideLengthMeters;
        public float imuAccelX;
        public float imuAccelY;
        public float imuAccelZ;
    }

    private final Random random = new Random();

    private List<SidewalkRouteGenerator.LatLng> leadInPoints = new ArrayList<>();
    private boolean isInLeadInFromRoom = false;
    private int leadInWaypointIndex = 0;
    private double leadInSegmentProgress = 0.0;

    private List<SidewalkRouteGenerator.LatLng> routePoints = new ArrayList<>();
    private int currentWaypointIndex = 0;
    private double segmentProgressMeters = 0.0;
    private int completedLaps = 0;

    private double currentLat = 10.875647;
    private double currentLng = 106.804879;
    private String currentStreetName = "Trong phòng KTX Khu A";
    private double baseAltitude = 8.5;
    private float currentBearing = 0.0f;

    private double currentSpeedKmh = 0.0;
    private double currentAccelKmhPerSec = 0.0;
    private double instantaneousTargetKmh = 4.0;

    private int currentTierLevel = 1;
    private int ultimateTargetTierLevel = 3;
    private double tierStepDwellRemaining = 0.0;

    private boolean isRestingPhase = false;
    private boolean standingRestSubMode = false;
    private double phaseRemainingSeconds = 240.0;
    private double phaseTotalSeconds = 240.0;
    private double intraRunTierChangeTimer = 45.0;

    private int runMinMinutes = 6;
    private int runMaxMinutes = 10;
    private int restMinMinutes = 1;
    private int restMaxMinutes = 2;
    private boolean[] enabledTiers = new boolean[]{true, true, true, true, true, true};

    private double wavePhase1 = random.nextDouble() * Math.PI * 2;
    private double wavePhase2 = random.nextDouble() * Math.PI * 2;
    private double wavePhase3 = random.nextDouble() * Math.PI * 2;
    private double ouSpeedNoiseKmh = 0.0;
    private double gnssAlongTrackNoiseM = 0.0;
    private double gnssCrossTrackNoiseM = 0.0;
    private double totalDistanceMeters = 0.0;
    private double maxSessionDistanceMeters = 4785.0;
    private double cooldownWindowMeters = 75.0;
    private boolean userLockedMaxDistance = false;
    private double userBaseMaxKm = 5.0;
    private boolean sessionDistanceCapReached = false;
    private boolean manualHoldPaused = false;
    private double holdAnchorLat = 0.0;
    private double holdAnchorLng = 0.0;
    private double holdElapsedSeconds = 0.0;
    private boolean hasOutdoorPosition = false;
    private double totalElapsedSeconds = 0.0;
    private double lapLateralOffsetMeters = 0.0;
    private double cumulativeSteps = 0.0;
    private double stepPhaseRad = 0.0;

    public synchronized void setLeadInAndSidewalkRoute(
            List<SidewalkRouteGenerator.LatLng> leadIn,
            List<SidewalkRouteGenerator.LatLng> sidewalkRoute) {
        if (sidewalkRoute != null && sidewalkRoute.size() >= 2) {
            this.routePoints = new ArrayList<>(sidewalkRoute);
        }
        if (leadIn != null && leadIn.size() >= 2) {
            this.leadInPoints = new ArrayList<>(leadIn);
            this.isInLeadInFromRoom = true;
            this.leadInWaypointIndex = 0;
            this.leadInSegmentProgress = 0.0;
            this.currentLat = leadIn.get(0).lat;
            this.currentLng = leadIn.get(0).lng;
            this.currentStreetName = leadIn.get(0).streetName;
            this.currentBearing = SidewalkRouteGenerator.bearingDegrees(
                    leadIn.get(0).lat, leadIn.get(0).lng,
                    leadIn.get(1).lat, leadIn.get(1).lng);
        } else if (!this.routePoints.isEmpty()) {
            this.leadInPoints.clear();
            this.isInLeadInFromRoom = false;
            this.currentLat = this.routePoints.get(0).lat;
            this.currentLng = this.routePoints.get(0).lng;
            this.currentStreetName = this.routePoints.get(0).streetName;
        }
    }

    public synchronized void setRoute(List<SidewalkRouteGenerator.LatLng> points) {
        if (points != null && points.size() >= 2) {
            this.routePoints = new ArrayList<>(points);
            if (!isInLeadInFromRoom) {
                this.currentWaypointIndex = 0;
                this.segmentProgressMeters = 0.0;
            }
        }
    }

    public synchronized void configureIntervals(int runMin, int runMax, int restMin, int restMax) {
        this.runMinMinutes = Math.max(1, runMin);
        this.runMaxMinutes = Math.max(this.runMinMinutes, runMax);
        this.restMinMinutes = Math.max(1, restMin);
        this.restMaxMinutes = Math.max(this.restMinMinutes, restMax);
    }

    public synchronized void setEnabledTiers(boolean t1, boolean t2, boolean t3, boolean t4, boolean t5) {
        enabledTiers[1] = t1;
        enabledTiers[2] = t2;
        enabledTiers[3] = t3;
        enabledTiers[4] = t4;
        enabledTiers[5] = t5;
        if (!t1 && !t2 && !t3 && !t4 && !t5) {
            enabledTiers[3] = true;
        }
    }

    public synchronized void setMaxSessionDistanceKm(double maxKm) {
        if (maxKm <= 0.0) {
            this.userLockedMaxDistance = false;
            this.maxSessionDistanceMeters = rollNaturalSessionDistanceMeters();
        } else {
            this.userLockedMaxDistance = true;
            this.userBaseMaxKm = Math.max(0.8, maxKm);
            double jitterMeters = (random.nextDouble() - 0.5) * 260.0 + random.nextGaussian() * 14.5;
            this.maxSessionDistanceMeters = Math.max(600.0, this.userBaseMaxKm * 1000.0 + jitterMeters);
        }
    }

    public synchronized double getMaxSessionDistanceMeters() {
        return this.maxSessionDistanceMeters;
    }

    public synchronized void setManualHoldPaused(boolean paused) {
        if (paused && !this.manualHoldPaused) {
            this.holdAnchorLat = this.currentLat;
            this.holdAnchorLng = this.currentLng;
            this.holdElapsedSeconds = 0.0;
        }
        this.manualHoldPaused = paused;
    }

    public synchronized boolean toggleManualHoldPaused() {
        setManualHoldPaused(!this.manualHoldPaused);
        return this.manualHoldPaused;
    }

    public synchronized boolean isManualHoldPaused() {
        return this.manualHoldPaused;
    }

    public synchronized boolean hasActiveOutdoorPosition() {
        return this.hasOutdoorPosition && (this.totalDistanceMeters > 15.0 || this.manualHoldPaused || this.sessionDistanceCapReached);
    }

    public synchronized double getCurrentLat() {
        return this.currentLat;
    }

    public synchronized double getCurrentLng() {
        return this.currentLng;
    }

    public synchronized String getCurrentStreetName() {
        return this.currentStreetName;
    }

    public synchronized void resetToDormRoom(double roomLat, double roomLng) {
        this.manualHoldPaused = false;
        this.sessionDistanceCapReached = false;
        this.hasOutdoorPosition = false;
        this.totalDistanceMeters = 0.0;
        this.currentLat = roomLat;
        this.currentLng = roomLng;
        this.currentStreetName = "Trong phòng KTX Khu A";
    }

    /**
     * Sinh ngẫu nhiên quãng đường mục tiêu cho mỗi phiên chạy:
     * - Không bao giờ dừng ở số tròn 5.00 km (hay 4.97 km lặp đi lặp lại).
     * - Chọn ngẫu nhiên mốc km nguyên K thuộc {3, 4, 5} kết hợp phần lẻ [0.58 .. 0.94] km + nhiễu lẻ tới từng mét/cm
     *   (ví dụ: 3.68 km, 3.84 km, 4.62 km, 4.79 km, 4.88 km, 5.64 km, 5.83 km...).
     * - Việc giữ phần lẻ cuối cùng trong khoảng 0.58..0.94 km đồng thời giúp split cuối trong vRace
     *   luôn dài 550m-920m (~800 - 1,350 bước chân), tránh bị lẻ vài chục mét khiến cột step của split cuối quá nhỏ.
     */
    private double rollNaturalSessionDistanceMeters() {
        int[] wholeKmChoices = new int[]{3, 4, 4, 4, 5, 5};
        int wholeKm = wholeKmChoices[random.nextInt(wholeKmChoices.length)];
        double fractionalKm = 0.58 + random.nextDouble() * 0.35; // 0.58 .. 0.93 km
        double fineNoiseMeters = random.nextGaussian() * 9.5;
        return Math.max(3550.0, wholeKm * 1000.0 + fractionalKm * 1000.0 + fineNoiseMeters);
    }

    public synchronized void startNewRunSession() {
        this.totalDistanceMeters = 0.0;
        this.sessionDistanceCapReached = false;
        this.manualHoldPaused = false;
        this.holdElapsedSeconds = 0.0;
        this.hasOutdoorPosition = true;
        this.totalElapsedSeconds = 0.0;
        this.completedLaps = 0;
        this.cumulativeSteps = 0.0;
        this.stepPhaseRad = 0.0;
        this.currentWaypointIndex = 0;
        this.segmentProgressMeters = 0.0;
        this.cooldownWindowMeters = 48.0 + random.nextDouble() * 68.0; // 48m .. 116m cuối hạ tốc tự nhiên

        if (this.userLockedMaxDistance) {
            double jitterMeters = (random.nextDouble() - 0.5) * 260.0 + random.nextGaussian() * 14.5;
            this.maxSessionDistanceMeters = Math.max(600.0, this.userBaseMaxKm * 1000.0 + jitterMeters);
        } else {
            this.maxSessionDistanceMeters = rollNaturalSessionDistanceMeters();
        }

        if (this.leadInPoints != null && this.leadInPoints.size() >= 2) {
            this.isInLeadInFromRoom = true;
            this.leadInWaypointIndex = 0;
            this.leadInSegmentProgress = 0.0;
            this.currentLat = leadInPoints.get(0).lat;
            this.currentLng = leadInPoints.get(0).lng;
            this.currentStreetName = leadInPoints.get(0).streetName;
            this.currentSpeedKmh = 4.6;
            this.currentAccelKmhPerSec = 0.0;
            this.currentTierLevel = 1;
            this.ultimateTargetTierLevel = 2;
            this.isRestingPhase = false;
            this.phaseTotalSeconds = 360.0;
            this.phaseRemainingSeconds = 360.0;
        } else {
            this.isInLeadInFromRoom = false;
            this.currentSpeedKmh = 6.5;
            this.currentAccelKmhPerSec = 0.0;
            this.currentTierLevel = 2;
            enterRunPhase();
        }
    }

    public synchronized void forceTargetTier(int targetLevel) {
        if (targetLevel == 0) {
            enterRestPhase();
        } else {
            this.isRestingPhase = false;
            this.ultimateTargetTierLevel = Math.max(1, Math.min(5, targetLevel));
            if (this.currentTierLevel != this.ultimateTargetTierLevel) {
                this.tierStepDwellRemaining = 5.0 + random.nextDouble() * 4.0;
            }
        }
    }

    private void enterRunPhase() {
        this.isRestingPhase = false;
        this.standingRestSubMode = false;
        double minSec = runMinMinutes * 60.0;
        double maxSec = runMaxMinutes * 60.0;
        this.phaseTotalSeconds = minSec + random.nextDouble() * (maxSec - minSec);
        this.phaseRemainingSeconds = this.phaseTotalSeconds;

        this.ultimateTargetTierLevel = pickRandomRunningTier();
        this.tierStepDwellRemaining = 3.2 + random.nextDouble() * 2.0;
        this.intraRunTierChangeTimer = 50.0 + random.nextDouble() * 40.0;
    }

    private void enterRestPhase() {
        this.isRestingPhase = true;
        double minSec = restMinMinutes * 60.0;
        double maxSec = restMaxMinutes * 60.0;
        this.phaseTotalSeconds = minSec + random.nextDouble() * (maxSec - minSec);
        this.phaseRemainingSeconds = this.phaseTotalSeconds;

        this.ultimateTargetTierLevel = 1;
        this.standingRestSubMode = false;
        this.tierStepDwellRemaining = 3.5 + random.nextDouble() * 2.5;
    }

    private int pickRandomRunningTier() {
        // Ưu tiên chọn nấc chạy bộ thực sự (Mức 3: 7-9 km/h, Mức 4: 9-12 km/h, Mức 5: 12-16 km/h)
        int[] weighted = new int[]{3, 3, 3, 4, 4, 4, 5, 2};
        List<Integer> candidates = new ArrayList<>();
        for (int lvl : weighted) {
            if (lvl >= 1 && lvl <= 5 && enabledTiers[lvl]) {
                candidates.add(lvl);
            }
        }
        if (candidates.isEmpty()) return 3;
        return candidates.get(random.nextInt(candidates.size()));
    }

    public synchronized SimulationSnapshot step(double dtSeconds) {
        if (manualHoldPaused || totalDistanceMeters >= maxSessionDistanceMeters) {
            if (totalDistanceMeters >= maxSessionDistanceMeters && !sessionDistanceCapReached) {
                sessionDistanceCapReached = true;
                holdAnchorLat = currentLat;
                holdAnchorLng = currentLng;
                holdElapsedSeconds = 0.0;
            }
            if (holdAnchorLat == 0.0 || holdAnchorLng == 0.0) {
                holdAnchorLat = currentLat;
                holdAnchorLng = currentLng;
            }

            holdElapsedSeconds += dtSeconds;
            // Mô phỏng người đang đứng nghỉ / bấm điện thoại lưu phiên trên vỉa hè:
            // Thỉnh thoảng nhích chân đổi trụ 1-2 bước nhẹ trong phạm vi 1.4m - 2.1m dọc vỉa hè
            double swayAlongM = 1.35 * Math.sin(holdElapsedSeconds * 0.27 + wavePhase1)
                    + 0.42 * Math.sin(holdElapsedSeconds * 0.63 + wavePhase2);
            double swayCrossM = 0.32 * Math.cos(holdElapsedSeconds * 0.21 + wavePhase3);
            double brngRad = Math.toRadians(currentBearing);
            double dNorth = swayAlongM * Math.cos(brngRad) - swayCrossM * Math.sin(brngRad);
            double dEast  = swayAlongM * Math.sin(brngRad) + swayCrossM * Math.cos(brngRad);
            double dLat = Math.toDegrees(dNorth / 6371000.0);
            double dLng = Math.toDegrees(dEast / (6371000.0 * Math.max(0.2, Math.cos(Math.toRadians(holdAnchorLat)))));

            currentLat = holdAnchorLat + dLat;
            currentLng = holdAnchorLng + dLng;

            // Vận tốc nhích chân tại chỗ dao động tự nhiên 0.25 .. 0.78 km/h (0.07 .. 0.22 m/s)
            double microWave = 0.5 + 0.5 * Math.sin(holdElapsedSeconds * 0.45 + wavePhase2);
            double microSpeedKmh = 0.24 + microWave * 0.54;
            currentSpeedKmh = microSpeedKmh;
            currentAccelKmhPerSec = 0.0;
            currentTierLevel = 0;
            ultimateTargetTierLevel = 0;

            // Nhịp đổi chân nhẹ khi đứng tại chỗ (~1 bước mỗi 5-7 giây khi microWave > 0.72)
            int microCadence = 0;
            if (microWave > 0.72) {
                double microStepDelta = (microSpeedKmh / 3.6) * dtSeconds / 0.52;
                cumulativeSteps += microStepDelta;
                microCadence = 28 + (int) Math.round(microWave * 14);
            }
            stepPhaseRad = (stepPhaseRad + 2.0 * Math.PI * 0.35 * dtSeconds) % (4.0 * Math.PI);

            SimulationSnapshot holdSnap = new SimulationSnapshot();
            holdSnap.lat = currentLat;
            holdSnap.lng = currentLng;
            holdSnap.altitudeMeters = baseAltitude + 0.15 * Math.sin(holdElapsedSeconds * 0.2);
            holdSnap.currentSpeedKmh = microSpeedKmh;
            holdSnap.targetSpeedKmh = 0.5;
            holdSnap.bearingDegrees = (float) ((currentBearing + 12.0 * Math.sin(holdElapsedSeconds * 0.19) + 360.0) % 360.0);
            holdSnap.accuracyMeters = 3.5f + (float) (0.3 * Math.abs(Math.sin(holdElapsedSeconds * 0.17)));
            holdSnap.currentTierLevel = 0;
            holdSnap.ultimateTargetTierLevel = 0;
            if (manualHoldPaused && !sessionDistanceCapReached) {
                holdSnap.currentTierName = "ĐANG PAUSE GIỮ VỊ TRÍ (Nhích chân tự nhiên tại chỗ)";
                holdSnap.targetTierName = "PAUSE TẠI CHỖ (Có thể Lưu vRace / Bấm start chạy phiên mới từ đây)";
            } else {
                holdSnap.currentTierName = String.format(java.util.Locale.US,
                        "ĐÃ ĐẠT ĐÍCH %.2f KM (Đứng nhích nhẹ chờ lưu)", maxSessionDistanceMeters / 1000.0);
                holdSnap.targetTierName = String.format(java.util.Locale.US,
                        "ĐÃ ĐẠT ĐÍCH %.2f KM", maxSessionDistanceMeters / 1000.0);
            }
            holdSnap.currentStreetName = currentStreetName;
            holdSnap.isInLeadInFromRoom = false;
            holdSnap.isRestingPhase = true;
            holdSnap.isManualHoldPaused = manualHoldPaused;
            holdSnap.isSessionDistanceCapReached = sessionDistanceCapReached;
            holdSnap.maxSessionDistanceMeters = maxSessionDistanceMeters;
            holdSnap.phaseRemainingSeconds = phaseRemainingSeconds;
            holdSnap.phaseTotalSeconds = phaseTotalSeconds;
            holdSnap.totalDistanceMeters = sessionDistanceCapReached ? maxSessionDistanceMeters : totalDistanceMeters;
            holdSnap.totalElapsedSeconds = totalElapsedSeconds;
            holdSnap.completedLaps = completedLaps;
            holdSnap.totalSteps = (int) Math.floor(cumulativeSteps);
            holdSnap.cadenceSpm = microCadence;
            holdSnap.strideLengthMeters = microCadence > 0 ? 0.48 : 0.0;
            holdSnap.imuAccelX = (float) (0.18 * Math.sin(stepPhaseRad * 0.5) + 0.05 * random.nextGaussian());
            holdSnap.imuAccelY = (float) (4.8 + 0.22 * Math.cos(stepPhaseRad) + 0.06 * random.nextGaussian());
            holdSnap.imuAccelZ = (float) (8.4 + 0.25 * Math.sin(stepPhaseRad) + 0.06 * random.nextGaussian());
            return holdSnap;
        }

        totalElapsedSeconds += dtSeconds;

        // Trong đoạn cuối (ngẫu nhiên 48m - 116m) trước khi chạm mốc đích, hạ dần tốc độ về Đi bộ (Mức 1) tự nhiên
        if (maxSessionDistanceMeters - totalDistanceMeters <= cooldownWindowMeters) {
            ultimateTargetTierLevel = 1;
            if (currentTierLevel > 1) {
                currentTierLevel = 1;
            }
        } else if (isInLeadInFromRoom) {
            currentTierLevel = 2;
            ultimateTargetTierLevel = 2;
        } else {
            phaseRemainingSeconds -= dtSeconds;
            if (phaseRemainingSeconds <= 0) {
                if (isRestingPhase) {
                    enterRunPhase();
                } else {
                    enterRestPhase();
                }
            } else if (!isRestingPhase) {
                intraRunTierChangeTimer -= dtSeconds;
                if (intraRunTierChangeTimer <= 0 && currentTierLevel == ultimateTargetTierLevel) {
                    ultimateTargetTierLevel = pickRandomRunningTier();
                    tierStepDwellRemaining = 6.0 + random.nextDouble() * 5.0;
                    intraRunTierChangeTimer = 45.0 + random.nextDouble() * 35.0;
                }
            }

            if (currentTierLevel != ultimateTargetTierLevel) {
                tierStepDwellRemaining -= dtSeconds;
                if (tierStepDwellRemaining <= 0) {
                    if (currentTierLevel < ultimateTargetTierLevel) {
                        currentTierLevel++;
                    } else {
                        currentTierLevel--;
                    }
                    tierStepDwellRemaining = 3.2 + random.nextDouble() * 2.0;
                }
            }
        }

        SpeedTier activeTier = SpeedTier.fromLevel(currentTierLevel);
        double osc1 = Math.sin(totalElapsedSeconds * 0.34 + wavePhase1);
        double osc2 = Math.sin(totalElapsedSeconds * 0.15 + wavePhase2);
        double osc3 = Math.sin(totalElapsedSeconds * 0.72 + wavePhase3);
        double normalizedWave = 0.5 + 0.24 * osc1 + 0.16 * osc2 + 0.08 * osc3;
        normalizedWave = Math.max(0.05, Math.min(0.95, normalizedWave));

        if (currentTierLevel == 0) {
            instantaneousTargetKmh = 3.85 + normalizedWave * (4.6 - 3.85);
        } else {
            double span = activeTier.maxKmh - activeTier.minKmh;
            instantaneousTargetKmh = activeTier.minKmh + normalizedWave * span;
        }

        double cornerFactor = isInLeadInFromRoom ? 0.94 : computeCornerSlowdownFactor();
        // Nhiễu Ornstein-Uhlenbeck mô phỏng dao động nhịp chân & hơi thở tự nhiên của con người mỗi giây
        double sqrtDt = Math.sqrt(Math.max(0.02, dtSeconds));
        ouSpeedNoiseKmh += -0.85 * ouSpeedNoiseKmh * dtSeconds + 1.15 * random.nextGaussian() * sqrtDt;
        ouSpeedNoiseKmh = Math.max(-1.35, Math.min(1.35, ouSpeedNoiseKmh));

        double effectiveTargetKmh = Math.max(3.8, Math.min(13.5, (instantaneousTargetKmh + ouSpeedNoiseKmh) * cornerFactor));

        double speedError = effectiveTargetKmh - currentSpeedKmh;
        double maxRateKmhPerSec = 0.85;
        double desiredAccel = Math.max(-maxRateKmhPerSec, Math.min(maxRateKmhPerSec, speedError * 0.55));

        double maxJerk = 0.95;
        double accelDelta = desiredAccel - currentAccelKmhPerSec;
        double maxDelta = maxJerk * dtSeconds;
        currentAccelKmhPerSec += Math.max(-maxDelta, Math.min(maxDelta, accelDelta)) + 0.18 * random.nextGaussian() * sqrtDt;

        currentSpeedKmh += currentAccelKmhPerSec * dtSeconds;
        // Khóa cứng ngưỡng Predator League: luôn nằm trong 3.8 km/h (15:47/km) .. 13.6 km/h (4:25/km)
        // để tuyệt đối không bao giờ vi phạm ngưỡng 4:00/km .. 18:00/km ở bất kỳ Split 1km nào!
        double minSafeKmh = (totalElapsedSeconds > 3.0) ? 3.8 : 0.0;
        if (currentSpeedKmh < minSafeKmh) {
            currentSpeedKmh = minSafeKmh;
            currentAccelKmhPerSec = 0.0;
        } else if (currentSpeedKmh > 13.6) {
            currentSpeedKmh = 13.6;
            currentAccelKmhPerSec = 0.0;
        }

        double speedMetersPerSec = currentSpeedKmh / 3.6;
        double stepDistanceMeters = speedMetersPerSec * dtSeconds;

        double strideLen = 0.0;
        double cadenceHz = 0.0;
        if (speedMetersPerSec >= 0.18) {
            strideLen = Math.max(0.55, Math.min(1.42, 0.48 + 0.215 * speedMetersPerSec));
            double deltaSteps = stepDistanceMeters / strideLen;
            cumulativeSteps += deltaSteps;
            cadenceHz = speedMetersPerSec / strideLen;
            stepPhaseRad = (stepPhaseRad + 2.0 * Math.PI * cadenceHz * dtSeconds) % (4.0 * Math.PI);
        } else {
            stepPhaseRad = (stepPhaseRad + 2.0 * Math.PI * 0.25 * dtSeconds) % (4.0 * Math.PI);
        }

        double ampVert = (speedMetersPerSec < 0.18) ? 0.08 : Math.min(16.5, 2.2 + 3.4 * speedMetersPerSec);
        double ampFwd  = (speedMetersPerSec < 0.18) ? 0.05 : Math.min(9.5,  1.1 + 1.8 * speedMetersPerSec);
        double ampLat  = (speedMetersPerSec < 0.18) ? 0.04 : Math.min(5.5,  0.6 + 1.1 * speedMetersPerSec);

        if (isInLeadInFromRoom) {
            advanceAlongLeadIn(stepDistanceMeters, dtSeconds);
        } else {
            advanceAlongSidewalk(stepDistanceMeters, dtSeconds);
        }
        if (totalDistanceMeters >= maxSessionDistanceMeters) {
            totalDistanceMeters = maxSessionDistanceMeters;
            sessionDistanceCapReached = true;
        }

        SimulationSnapshot snap = new SimulationSnapshot();
        snap.lat = currentLat;
        snap.lng = currentLng;
        snap.altitudeMeters = baseAltitude + 0.6 * Math.sin(totalDistanceMeters * 0.03);
        snap.currentSpeedKmh = currentSpeedKmh;
        snap.targetSpeedKmh = effectiveTargetKmh;
        snap.bearingDegrees = currentBearing;
        snap.accuracyMeters = isInLeadInFromRoom
                ? 5.8f
                : (float) (3.4 + 1.2 * Math.abs(Math.sin(totalElapsedSeconds * 0.19)));
        snap.currentTierLevel = currentTierLevel;
        snap.ultimateTargetTierLevel = ultimateTargetTierLevel;
        snap.currentTierName = isInLeadInFromRoom ? "Từ phòng ra vỉa hè" : activeTier.label;
        snap.targetTierName = SpeedTier.fromLevel(ultimateTargetTierLevel).label;
        snap.currentStreetName = currentStreetName;
        snap.isInLeadInFromRoom = isInLeadInFromRoom;
        snap.isRestingPhase = isRestingPhase;
        snap.isSessionDistanceCapReached = sessionDistanceCapReached;
        snap.maxSessionDistanceMeters = maxSessionDistanceMeters;
        snap.phaseRemainingSeconds = Math.max(0.0, phaseRemainingSeconds);
        snap.phaseTotalSeconds = phaseTotalSeconds;
        snap.totalDistanceMeters = totalDistanceMeters;
        snap.totalElapsedSeconds = totalElapsedSeconds;
        snap.completedLaps = completedLaps;
        snap.totalSteps = (int) Math.floor(cumulativeSteps);
        snap.cadenceSpm = (int) Math.round(cadenceHz * 60.0);
        snap.strideLengthMeters = strideLen;
        snap.imuAccelX = (float) (ampLat * Math.cos(stepPhaseRad * 0.5) + (random.nextDouble() - 0.5) * 0.15);
        snap.imuAccelY = (float) (4.8 + ampVert * (Math.sin(stepPhaseRad) + 0.35 * Math.sin(2.0 * stepPhaseRad - 0.4)) + (random.nextDouble() - 0.5) * 0.2);
        snap.imuAccelZ = (float) (8.4 + ampFwd * Math.cos(stepPhaseRad + 0.3) + (random.nextDouble() - 0.5) * 0.15);
        return snap;
    }

    private void advanceAlongLeadIn(double distanceToMove, double dtSeconds) {
        if (leadInPoints == null || leadInPoints.size() < 2) {
            isInLeadInFromRoom = false;
            enterRunPhase();
            return;
        }

        totalDistanceMeters += distanceToMove;
        double remaining = distanceToMove;
        int n = leadInPoints.size();

        while (remaining > 0.0 && isInLeadInFromRoom) {
            if (leadInWaypointIndex >= n - 1) {
                isInLeadInFromRoom = false;
                currentWaypointIndex = 0;
                segmentProgressMeters = 0.0;
                enterRunPhase();
                return;
            }

            SidewalkRouteGenerator.LatLng start = leadInPoints.get(leadInWaypointIndex);
            SidewalkRouteGenerator.LatLng end = leadInPoints.get(leadInWaypointIndex + 1);
            double segLength = SidewalkRouteGenerator.distanceMeters(start.lat, start.lng, end.lat, end.lng);

            if (segLength < 0.05) {
                leadInWaypointIndex++;
                leadInSegmentProgress = 0.0;
                continue;
            }

            double distLeft = segLength - leadInSegmentProgress;
            if (remaining < distLeft) {
                leadInSegmentProgress += remaining;
                remaining = 0.0;
            } else {
                remaining -= distLeft;
                leadInSegmentProgress = 0.0;
                leadInWaypointIndex++;
            }
        }

        if (!isInLeadInFromRoom) {
            advanceAlongSidewalk(remaining, dtSeconds);
            return;
        }

        int idx = Math.min(leadInWaypointIndex, n - 2);
        SidewalkRouteGenerator.LatLng a = leadInPoints.get(idx);
        SidewalkRouteGenerator.LatLng b = leadInPoints.get(idx + 1);
        double segLen = Math.max(0.05, SidewalkRouteGenerator.distanceMeters(a.lat, a.lng, b.lat, b.lng));
        double ratio = Math.max(0.0, Math.min(1.0, leadInSegmentProgress / segLen));

        currentLat = a.lat + ratio * (b.lat - a.lat);
        currentLng = a.lng + ratio * (b.lng - a.lng);
        currentStreetName = a.streetName;
        float targetBearing = SidewalkRouteGenerator.bearingDegrees(a.lat, a.lng, b.lat, b.lng);
        currentBearing = smoothBearing(currentBearing, targetBearing, (float) Math.min(1.0, dtSeconds * 6.0));
    }

    private double computeCornerSlowdownFactor() {
        if (routePoints.size() < 4) return 1.0;
        int n = routePoints.size();
        SidewalkRouteGenerator.LatLng p0 = routePoints.get(currentWaypointIndex % n);
        SidewalkRouteGenerator.LatLng p1 = routePoints.get((currentWaypointIndex + 1) % n);
        SidewalkRouteGenerator.LatLng p2 = routePoints.get((currentWaypointIndex + 3) % n);

        float b1 = SidewalkRouteGenerator.bearingDegrees(p0.lat, p0.lng, p1.lat, p1.lng);
        float b2 = SidewalkRouteGenerator.bearingDegrees(p1.lat, p1.lng, p2.lat, p2.lng);
        double diff = Math.abs(b2 - b1);
        if (diff > 180.0) diff = 360.0 - diff;

        if (diff > 35.0) {
            double reduction = Math.min(0.18, (diff - 35.0) / 300.0);
            return 1.0 - reduction;
        }
        return 1.0;
    }

    private void advanceAlongSidewalk(double distanceToMove, double dtSeconds) {
        if (routePoints.size() < 2) return;

        if (distanceToMove < 0.02) {
            double microAngle = random.nextDouble() * 360.0;
            SidewalkRouteGenerator.LatLng jittered = SidewalkRouteGenerator.offsetByMeters(
                    currentLat, currentLng, 0.025 * dtSeconds, microAngle);
            currentLat = jittered.lat;
            currentLng = jittered.lng;
            return;
        }

        totalDistanceMeters += distanceToMove;
        double remaining = distanceToMove;
        int n = routePoints.size();

        while (remaining > 0.0) {
            SidewalkRouteGenerator.LatLng start = routePoints.get(currentWaypointIndex % n);
            SidewalkRouteGenerator.LatLng end = routePoints.get((currentWaypointIndex + 1) % n);
            double segLength = SidewalkRouteGenerator.distanceMeters(start.lat, start.lng, end.lat, end.lng);

            if (segLength < 0.05) {
                currentWaypointIndex = (currentWaypointIndex + 1) % n;
                segmentProgressMeters = 0.0;
                continue;
            }

            double distLeftInSeg = segLength - segmentProgressMeters;
            if (remaining < distLeftInSeg) {
                segmentProgressMeters += remaining;
                remaining = 0.0;
            } else {
                remaining -= distLeftInSeg;
                segmentProgressMeters = 0.0;
                currentWaypointIndex++;
                if (currentWaypointIndex >= n - 1) {
                    java.util.Collections.reverse(routePoints);
                    currentWaypointIndex = 0;
                    completedLaps++;
                    lapLateralOffsetMeters = (random.nextDouble() - 0.5) * 0.75;
                }
            }
        }

        SidewalkRouteGenerator.LatLng a = routePoints.get(currentWaypointIndex % n);
        SidewalkRouteGenerator.LatLng b = routePoints.get((currentWaypointIndex + 1) % n);
        double segLen = Math.max(0.05, SidewalkRouteGenerator.distanceMeters(a.lat, a.lng, b.lat, b.lng));
        double ratio = Math.max(0.0, Math.min(1.0, segmentProgressMeters / segLen));

        double interpLat = a.lat + ratio * (b.lat - a.lat);
        double interpLng = a.lng + ratio * (b.lng - a.lng);

        float targetBearing = SidewalkRouteGenerator.bearingDegrees(a.lat, a.lng, b.lat, b.lng);
        currentBearing = smoothBearing(currentBearing, targetBearing, (float) Math.min(1.0, dtSeconds * 6.0));

        // Nhiễu vi mô thu phát vệ tinh GNSS thực tế trên điện thoại (Kalman-filtered multipath ~18-26cm/s)
        double sqrtDt = Math.sqrt(Math.max(0.02, dtSeconds));
        gnssAlongTrackNoiseM = 0.55 * gnssAlongTrackNoiseM + 0.16 * random.nextGaussian() * sqrtDt;
        gnssAlongTrackNoiseM = Math.max(-0.38, Math.min(0.38, gnssAlongTrackNoiseM));
        gnssCrossTrackNoiseM = 0.65 * gnssCrossTrackNoiseM + 0.11 * random.nextGaussian() * sqrtDt;
        gnssCrossTrackNoiseM = Math.max(-0.32, Math.min(0.32, gnssCrossTrackNoiseM));

        double strideSway = 0.05 * Math.sin(totalDistanceMeters * 1.5) + gnssCrossTrackNoiseM;
        double normalBearing = (targetBearing + 90.0) % 360.0;
        SidewalkRouteGenerator.LatLng acrossPos = SidewalkRouteGenerator.offsetByMeters(
                interpLat, interpLng, strideSway, normalBearing);
        SidewalkRouteGenerator.LatLng naturalPos = SidewalkRouteGenerator.offsetByMeters(
                acrossPos.lat, acrossPos.lng, gnssAlongTrackNoiseM, targetBearing);

        currentLat = naturalPos.lat;
        currentLng = naturalPos.lng;
        currentStreetName = a.streetName;
    }

    private float smoothBearing(float current, float target, float factor) {
        float diff = ((target - current + 540.0f) % 360.0f) - 180.0f;
        return (current + diff * factor + 360.0f) % 360.0f;
    }
}
