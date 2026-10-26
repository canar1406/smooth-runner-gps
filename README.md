# 🏃 SmoothRunner GPS & IMU Sync (`v1.6.4`)

Ứng dụng giả lập chạy bộ vỉa hè tự nhiên dành cho Android (**Root + LSPosed**), tích hợp sẵn **đồng bộ cảm biến bước chân Chip IMU** và **bản đồ Offline toàn khu vực Làng Đại Học ĐHQG TP.HCM (KTX Khu A ↔ KTX Khu B)**.

---

## ✨ Tính năng nổi bật (`v1.6.4` — `versionCode 23`)

1. **Bản đồ Offline Làng Đại Học ĐHQG (`KTX Khu A ↔ KTX Khu B`)**:
   * Nhúng sẵn `1.374` tuyến đường & vỉa hè (`1.177` nút giao) trong `assets/osm_lang_dai_hoc.json.gz` (`93 KB`).
   * Quét bản đồ tức thì trong **`0,03 giây`** ngay cả khi dùng mạng 4G yếu hoặc không có mạng.
2. **Quỹ đạo Vỉa hè & Lộ trình Đa phong cách**:
   * Mặc định xuất phát từ **Tòa AH2 (`10.881915, 106.809219`)** $\rightarrow$ đi bộ theo đường trục chính nội khu KTX Khu A $\rightarrow$ ra **Cổng chính KTX Khu A (`10.878319, 106.806151`)** (chặn toàn bộ cổng phụ bị khoá).
   * Bám sát **lề vỉa hè phía ngoài (`4,2m – 5,2m`)** theo đúng chiều đường một chiều / đường đôi có con lươn, tuyệt đối không chạy giữa lòng đường.
   * Tự động quay ngẫu nhiên 4 phong cách chạy mỗi phiên:
     * Chạy xuyên **KTX Khu A $\rightarrow$ KTX Khu B** (qua *Quảng Trường Sáng Tạo – Nguyễn Du – Mạc Đĩnh Chi*).
     * Chạy xuyên **KTX Khu A $\rightarrow$ KTX Khu B** (qua *Tạ Quang Bửu – Thánh Gióng – Lê Quý Đôn – Hải Thượng Lãn Ông – Tôn Thất Tùng – Văn Cao*).
     * Pha trộn **Đi hết tuyến đường + Ngẫu nhiên quay đầu giữa chừng**.
     * Khám phá đa hướng quanh Làng Đại Học.
3. **Random Quãng đường Tự dừng mỗi phiên**:
   * Không dừng ở mốc tròn `5,00 km` cố định. Mỗi phiên tự động quay ngẫu nhiên đích dừng trong khoảng **`3,60 km – 5,90 km`** lẻ tới từng mét (ví dụ `3,74 km`, `4,63 km`, `4,85 km`, `5,67 km`...) kèm giai đoạn đi bộ giảm tốc tự nhiên `48m – 116m` cuối bài.
4. **Nút `⏸ pause` Giữ vị trí & Nối tiếp phiên mới không nhảy GPS**:
   * Khi bấm **`⏸ pause`** (hoặc khi chạm đích tự dừng), nhân vật **đứng nghỉ ngay tại vỉa hè hiện tại** và giả lập **chuyển động nhích chân đổi trụ tự nhiên tại chỗ (`0,25 – 0,78 km/h`, phạm vi `1,4m – 2,1m`)** thay vì nhảy ngược về phòng KTX.
   * Sau khi sang app chạy bộ bấm **Lưu phiên chạy**, nếu bấm **`▶ start`** hoặc **`🎲 random`**, phiên chạy mới sẽ **xuất phát ngay từ vị trí vỉa hè đang đứng**, không phát sinh nghi điểm dịch chuyển tức thời về phòng.
5. **Module LSPosed (`XposedHook`) tích hợp sẵn trong APK**:
   * Tự động cấp quyền `appops set com.smoothrunner.gps android:mock_location allow` ngầm qua Root (`su`).
   * Ẩn hoàn toàn cờ Mock Location (`isFromMockProvider`, `isMock`, `mockLocation` bundle extras) và khoá chống nhiễu vị trí Wi-Fi/Cell thật khi chuyển ứng dụng.
   * Tự động giả lập nhịp bước chân (`TYPE_STEP_COUNTER`, `TYPE_STEP_DETECTOR`) và rung lắc gia tốc kế 3 trục (`TYPE_ACCELEROMETER`) khớp với vận tốc GPS theo thời gian thực — máy để im trên bàn vẫn đếm bước chuẩn xác.
   * Tự động chuẩn hoá toạ độ `starting_latitude/longitude` khớp 100% với điểm đầu tiên của track GPS và đồng bộ tổng số bước `step` giữa tất cả các chặng (`lapse`) trên `vRace Connect`.

---

## 🛠️ Hướng dẫn Cài đặt (Yêu cầu máy đã Root + cài LSPosed)

### Bước 1: Cài đặt APK
Cài trực tiếp file **[`SmoothRunner-GPS.apk`](./SmoothRunner-GPS.apk)** lên điện thoại Android (hoặc cài qua ADB):
```bash
adb install -r SmoothRunner-GPS.apk
```

### Bước 2: Kích hoạt Module trong LSPosed
1. Mở ứng dụng **LSPosed** trên điện thoại $\rightarrow$ vào mục **Mô-đun (Modules)**.
2. Bật mô-đun **SmoothRunner GPS**.
3. Tích chọn phạm vi áp dụng (**Scope**):
   * ✅ **Hệ thống (System Framework)** *(tuỳ chọn)*
   * ✅ **vRace Connect** (`fpt.vne.vrace`)
   * ✅ **SmoothRunner GPS** (`com.smoothrunner.gps`)
4. Buộc dừng (**Force Stop**) ứng dụng **vRace Connect** (hoặc khởi động lại máy trong lần đầu bật module).

### Bước 3: Cấp quyền Root cho SmoothRunner GPS
1. Mở ứng dụng **SmoothRunner GPS**.
2. Khi hộp thoại xin quyền **Superuser (Magisk / KernelSU / APatch)** hiện lên, chọn **Cho phép (Grant)**.
3. Trên đầu màn hình sẽ báo:
   * `MOCK PERM: ĐÃ BẬT (ROOT/APPOPS READY)`
   * `LSPOSED  : ĐÃ BẬT (Ẩn Mock GPS + Đồng bộ Chip IMU)`

---

## 🎮 Hướng dẫn Sử dụng Chuẩn

### 1. Bắt đầu phiên chạy đầu tiên (từ phòng KTX Khu A)
1. Mở **SmoothRunner GPS**, bấm nút **`▶ start`**.
   * Dòng log `[TARGET]` và dòng `CHU KỲ` trên bảng xanh sẽ hiển thị **số km đích ngẫu nhiên** của phiên (ví dụ: `182.2m / 5670.6m`).
2. Mở ứng dụng **vRace Connect** $\rightarrow$ bấm **Bắt đầu (Start)**.
3. Có thể để máy chạy nền hoặc tắt màn hình, dịch vụ Foreground của SmoothRunner sẽ tự động điều khiển đi bộ từ phòng ra cổng chính rồi chuyển sang chạy trên vỉa hè.

### 2. Dừng giữa chừng hoặc khi đạt đích để Lưu kết quả (Quy trình chống nhảy GPS)
1. Khi muốn dừng lưu bài chạy (hoặc khi app báo `[FINISH] Đã hoàn thành đủ ... km`):
   * Nếu đang chạy giữa chừng và muốn dừng sớm: Bấm nút **`⏸ pause`** trên **SmoothRunner GPS** trước.
   * Lúc này nhân vật sẽ đứng tại chỗ trên vỉa hè hiện tại và nhích chân nhẹ tự nhiên (`0,25 – 0,78 km/h`).
2. Chuyển sang **vRace Connect** $\rightarrow$ bấm **Tạm dừng (Pause)** $\rightarrow$ bấm **Kết thúc / Lưu (Finish & Save)**.
3. Sau khi đã Lưu xong trên vRace:
   * **Muốn mở tiếp một phiên chạy mới ngay từ vỉa hè đang đứng**: Bấm **`▶ start`** (hoặc **`🎲 random`**) trên SmoothRunner $\rightarrow$ mở phiên chạy mới trên vRace.
   * **Muốn chạy tiếp phiên đang dở**: Bấm lại nút **`⏸ pause`**.
   * **Muốn nghỉ hẳn và trả GPS về lại phòng KTX AH2**: Bấm nút **`⏹ stop`**.

---

## ⌨️ Danh sách Lệnh CLI (gõ vào ô `# Lệnh:` ở cuối màn hình)

| Lệnh | Tác dụng |
| :--- | :--- |
| `start` | Bắt đầu chạy (từ phòng AH2, hoặc nối tiếp ngay từ vị trí đang `⏸ pause` ngoài đường) |
| `pause` | Tạm dừng giữ nguyên vị trí vỉa hè hiện tại + giả lập nhích chân nhẹ tại chỗ (bấm lần nữa để chạy tiếp) |
| `resume` | Tiếp tục chạy phiên hiện tại sau khi `pause` |
| `random` | Quét và đổi sang lộ trình vỉa hè ngẫu nhiên mới |
| `stop` | Dừng hẳn giả lập GPS và đưa điểm xuất phát về lại phòng KTX AH2 |
| `max <km>` | Đặt mốc quãng đường mục tiêu xoay quanh `<km>` (ví dụ: `max 4.5` hoặc `max 0` để về lại chế độ random tự động) |
| `tier 1` .. `tier 5` | Ép chuyển mượt từng nấc vận tốc tới Mức 1 (`3.8–5.0 km/h`) .. Mức 5 (`11.2–13.4 km/h`) |
| `rest` | Chuyển sang pha đi bộ nghỉ |
| `gps <lat> <lng>` | Đổi toạ độ phòng xuất phát mặc định (ví dụ: `gps 10.881915 106.809219`) |
| `root` | Chạy lại lệnh cấp quyền `appops mock_location` qua `su` |

---

## 📂 Cấu trúc Dự án

```text
├── SmoothRunner-GPS.apk                  # File APK bản v1.6.4 (versionCode 23) cài đặt ngay
├── README.md                             # Tài liệu hướng dẫn sử dụng chi tiết
├── HUONG_DAN_CAI_DAT.md                  # Tài liệu hướng dẫn nhanh tiếng Việt
├── spoof_runner.py                       # Script điều khiển từ máy tính qua ADB (tuỳ chọn)
└── SmoothRunnerAndroid/                  # Mã nguồn Android Studio / Java
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── xposed_init               # Điểm vào module LSPosed
        │   └── osm_lang_dai_hoc.json.gz  # Bản đồ Offline Làng Đại Học ĐHQG (KTX Khu A ↔ Khu B)
        └── java/com/smoothrunner/gps/
            ├── MainActivity.java         # Giao diện CLI Terminal & điều khiển trực tiếp
            ├── MockGpsService.java       # Foreground Service phát GPS 5Hz & đồng bộ IMU
            ├── RunningSimulatorEngine.java # Động cơ vật lý chạy bộ, bậc thang tốc độ & Pause tại chỗ
            ├── SidewalkRouteGenerator.java # Thuật toán tìm đường vỉa hè OSM & 4 chế độ Random
            ├── SpeedTier.java            # Định nghĩa các bậc vận tốc an toàn (3.8 - 13.6 km/h)
            └── XposedHook.java           # Hook LSPosed ẩn Mock GPS, giả lập IMU & chuẩn hoá dữ liệu
```

---

## 📄 Bản quyền (Copyright)

**© 2026 [Heavietnam](https://home.heavietnam.com/). All rights reserved.**
Phát triển & tối ưu bởi **[Heavietnam](https://home.heavietnam.com/)**.
