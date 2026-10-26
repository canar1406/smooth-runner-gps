#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
spoof_runner.py — Công cụ Command-Line (CLI) Giả Lập Chạy Bộ Vỉa Hè Mượt Mà
=============================================================================
Tính năng cốt lõi:
1. Xuất phát chính xác từ toạ độ GPS TRONG PHÒNG của bạn (vận tốc 0.0 km/h),
   đi bộ mượt mà (Mức 1: 3–5 km/h) từ trong phòng ra tới mép vỉa hè gần nhất.
2. Quét toàn bộ mạng lưới vỉa hè quanh phòng (Overpass OpenStreetMap), loại bỏ
   hẻm cụt (dead-end) và cầu thang (steps), dịch chuyển quỹ đạo vào đúng lề
   vỉa hè theo bề rộng đường thực tế và bo tròn góc ngã tư bằng Chaikin Spline.
3. Khi đã ra tới vỉa hè: Tự động RANDOM tuyến đường vỉa hè bất kỳ tại mỗi ngã tư
   quanh khu vực bạn ở (một tuyến vỉa hè có thể chạy lặp lại nhiều lần mượt mà).
4. 5 Mức độ vận tốc + Pha Nghỉ:
   - Nấc 0: Nghỉ thư giãn (Đứng nghỉ thở 0–0.4 km/h hoặc Đi bộ chậm 2.2–3.8 km/h)
   - Mức 1: Đi bộ bình thường (3–5 km/h)
   - Mức 2: Đi bộ nhanh (5–7 km/h)
   - Mức 3: Chạy bộ nhẹ - Jogging (7–9 km/h)
   - Mức 4: Chạy bộ trung bình (9–12 km/h)
   - Mức 5: Chạy nhanh (12–16 km/h)
5. Chu kỳ: Chạy 3–5 phút (random) <-> Nghỉ 1–5 phút (random). Sau mỗi khoảng nghỉ
   tự động random tốc độ chạy mới.
6. Chuyển tốc độ TỪNG NẤC MỘT (0 <-> 1 <-> 2 <-> 3 <-> 4 <-> 5), tuyệt đối không nhảy cóc,
   kết hợp sóng điều hoà đa tần số + bộ lọc quán tính bậc 2 giúp vận tốc thay đổi
   liên tục nhưng cực kỳ mượt mà.
"""

import argparse
import json
import math
import os
import random
import re
import shutil
import signal
import ssl
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Dict, List, Optional, Set, Tuple

EARTH_RADIUS = 6378137.0  # mét
SSL_CTX = ssl._create_unverified_context()


# ==============================================================================
# 1. ĐỊNH NGHĨA 5 MỨC ĐỘ VẬN TỐC + TRẠNG THÁI NGHỈ
# ==============================================================================
@dataclass
class SpeedTier:
    level: int
    name: str
    min_kmh: float
    max_kmh: float


SPEED_TIERS: Dict[int, SpeedTier] = {
    0: SpeedTier(0, "Nghỉ thư giãn", 0.0, 3.8),
    1: SpeedTier(1, "Đi bộ bình thường", 3.0, 5.0),
    2: SpeedTier(2, "Đi bộ nhanh", 5.0, 7.0),
    3: SpeedTier(3, "Chạy bộ nhẹ (Jogging)", 7.0, 9.0),
    4: SpeedTier(4, "Chạy bộ trung bình", 9.0, 12.0),
    5: SpeedTier(5, "Chạy nhanh", 12.0, 16.0),
}


@dataclass
class Waypoint:
    lat: float
    lng: float
    street_name: str = "Vỉa hè"
    sidewalk_offset_m: float = 3.6


# ==============================================================================
# 2. CÁC HÀM HÌNH HỌC ĐỊA CẦU & LÀM MƯỢT GÓC VỈA HÈ (SPLINE)
# ==============================================================================
def distance_meters(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    d_lat = math.radians(lat2 - lat1)
    d_lng = math.radians(lng2 - lng1)
    a = (
        math.sin(d_lat / 2.0) ** 2
        + math.cos(math.radians(lat1))
        * math.cos(math.radians(lat2))
        * math.sin(d_lng / 2.0) ** 2
    )
    return EARTH_RADIUS * 2.0 * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1.0 - a)))


def bearing_degrees(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_lam = math.radians(lng2 - lng1)
    y = math.sin(d_lam) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(d_lam)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def offset_by_meters(lat: float, lng: float, dist_m: float, bearing_deg: float) -> Tuple[float, float]:
    if abs(dist_m) < 0.005:
        return lat, lng
    brng = math.radians(bearing_deg)
    lat1 = math.radians(lat)
    lng1 = math.radians(lng)
    ang_dist = dist_m / EARTH_RADIUS

    lat2 = math.asin(
        math.sin(lat1) * math.cos(ang_dist)
        + math.cos(lat1) * math.sin(ang_dist) * math.cos(brng)
    )
    lng2 = lng1 + math.atan2(
        math.sin(brng) * math.sin(ang_dist) * math.cos(lat1),
        math.cos(ang_dist) - math.sin(lat1) * math.sin(lat2),
    )
    return math.degrees(lat2), math.degrees(lng2)


def smooth_bearing(current: float, target: float, factor: float) -> float:
    diff = ((target - current + 540.0) % 360.0) - 180.0
    return (current + diff * factor + 360.0) % 360.0


def estimate_sidewalk_offset_from_tags(tags: dict) -> float:
    """
    Tính khoảng cách từ tim đường (centerline) sang lề vỉa hè dựa theo thuộc tính thực tế của đường.
    Nếu đường đã là vỉa hè / phố đi bộ / lối đi công viên (footway, pedestrian, path, sidewalk) -> 0.0m.
    """
    if not tags:
        return 3.6
    hw = tags.get("highway", "")
    fw = tags.get("footway", "")
    if hw in ("footway", "pedestrian", "path", "corridor") or fw == "sidewalk":
        return 0.0

    width_str = re.sub(r"[^0-9.]", "", str(tags.get("width", "")))
    if width_str:
        try:
            w = float(width_str)
            if 2.0 <= w <= 40.0:
                return (w / 2.0) + 1.0
        except ValueError:
            pass

    lanes_str = re.sub(r"[^0-9.]", "", str(tags.get("lanes", "")))
    if lanes_str:
        try:
            lanes = float(lanes_str)
            if 1.0 <= lanes <= 8.0:
                return (lanes * 3.25 / 2.0) + 1.2
        except ValueError:
            pass

    mapping = {
        "primary": 8.2,
        "primary_link": 8.2,
        "secondary": 6.5,
        "secondary_link": 6.5,
        "tertiary": 4.8,
        "tertiary_link": 4.8,
        "residential": 3.6,
        "unclassified": 3.6,
        "living_street": 1.5,
        "service": 1.5,
    }
    return mapping.get(hw, 3.6)


# ==============================================================================
# 3. ĐỒ THỊ VỈA HÈ QUANH PHÒNG & BỘ TẠO LỘ TRÌNH VỈA HÈ RANDOM
# ==============================================================================
class SidewalkNetworkGraph:
    def __init__(self, room_lat: float, room_lng: float, radius_m: float = 400.0):
        self.room_lat = room_lat
        self.room_lng = room_lng
        self.radius_m = radius_m
        self.nodes: Dict[int, Waypoint] = {}
        self.adj: Dict[int, Set[int]] = {}
        self.edge_names: Dict[Tuple[int, int], str] = {}
        self.edge_offsets: Dict[Tuple[int, int], float] = {}

    def scan_from_osm(self) -> int:
        """
        Quét toàn bộ vỉa hè & đường phố người đi bộ đi được quanh phòng từ
        OpenStreetMap API v0.6 chính thức (nhanh và ổn định 100%).
        """
        # Tính bounding box (min_lon, min_lat, max_lon, max_lat) từ bán kính radius_m
        d_lat = (self.radius_m / EARTH_RADIUS) * (180.0 / math.pi)
        d_lng = d_lat / max(0.2, math.cos(math.radians(self.room_lat)))
        min_lat = self.room_lat - d_lat
        max_lat = self.room_lat + d_lat
        min_lng = self.room_lng - d_lng
        max_lng = self.room_lng + d_lng

        bbox_url = (
            f"https://api.openstreetmap.org/api/0.6/map.json"
            f"?bbox={min_lng:.6f},{min_lat:.6f},{max_lng:.6f},{max_lat:.6f}"
        )

        data = None
        try:
            req = urllib.request.Request(
                bbox_url, headers={"User-Agent": "SmoothRunnerCLI/1.2"}
            )
            with urllib.request.urlopen(req, timeout=10, context=SSL_CTX) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
        except Exception:
            data = None

        allowed_highways = {
            "footway",
            "pedestrian",
            "path",
            "living_street",
            "residential",
            "tertiary",
            "tertiary_link",
            "secondary",
            "secondary_link",
            "primary",
            "primary_link",
            "unclassified",
            "service",
        }

        if data and "elements" in data:
            raw_nodes: Dict[int, Tuple[float, float]] = {}
            for el in data["elements"]:
                if el.get("type") == "node" and "lat" in el and "lon" in el:
                    raw_nodes[int(el["id"])] = (float(el["lat"]), float(el["lon"]))

            for el in data["elements"]:
                if el.get("type") != "way":
                    continue
                tags = el.get("tags", {})
                hw = tags.get("highway", "")
                if hw not in allowed_highways:
                    continue
                if hw == "steps" or tags.get("access") == "private" or tags.get("foot") == "no":
                    continue

                nodes_list = [int(nid) for nid in el.get("nodes", []) if int(nid) in raw_nodes]
                if len(nodes_list) < 2:
                    continue

                offset = estimate_sidewalk_offset_from_tags(tags)
                default_label = (
                    "Lối đi bộ nội khu KTX Khu A"
                    if offset == 0.0
                    else "Đường nội khu ĐHQG"
                )
                street_name = tags.get("name", default_label)
                if offset > 0 and not street_name.startswith("Vỉa hè"):
                    street_name = f"Vỉa hè {street_name}"

                for nid in nodes_list:
                    lat_val, lng_val = raw_nodes[nid]
                    self.nodes[nid] = Waypoint(
                        lat=lat_val,
                        lng=lng_val,
                        street_name=street_name,
                        sidewalk_offset_m=offset,
                    )

                for i in range(len(nodes_list) - 1):
                    u, v = nodes_list[i], nodes_list[i + 1]
                    if u == v:
                        continue
                    self.adj.setdefault(u, set()).add(v)
                    self.adj.setdefault(v, set()).add(u)
                    self.edge_names[(u, v)] = street_name
                    self.edge_names[(v, u)] = street_name
                    self.edge_offsets[(u, v)] = offset
                    self.edge_offsets[(v, u)] = offset

            # Cắt tỉa các nhánh cụt (Dead-end pruning) để người chạy không bị kẹt trong ngõ cụt
            pruned = True
            while pruned:
                pruned = False
                dead_ids = [nid for nid, nbs in self.adj.items() if len(nbs) < 2]
                if dead_ids:
                    pruned = True
                    for d in dead_ids:
                        self.adj.pop(d, None)
                    for nbs in self.adj.values():
                        nbs.difference_update(dead_ids)

        if len(self.adj) < 4:
            self._build_fallback_neighborhood_grid()

        return len(self.adj)

    def _build_fallback_neighborhood_grid(self):
        """Mạng lưới vỉa hè 3x3 ô phố quanh phòng khi không có Internet"""
        self.nodes.clear()
        self.adj.clear()
        step_m = 90.0
        nid = 1
        grid = {}
        for r in range(-1, 2):
            for c in range(-1, 2):
                p_north = offset_by_meters(self.room_lat, self.room_lng, r * step_m + 22.0, 0.0)
                pt = offset_by_meters(p_north[0], p_north[1], c * step_m + 22.0, 90.0)
                grid[(r, c)] = nid
                self.nodes[nid] = Waypoint(pt[0], pt[1], f"Vỉa hè Khu phố #{nid}", 3.6)
                self.adj[nid] = set()
                nid += 1

        for r in range(-1, 2):
            for c in range(-1, 2):
                u = grid[(r, c)]
                if r + 1 <= 1:
                    v = grid[(r + 1, c)]
                    self.adj[u].add(v)
                    self.adj[v].add(u)
                    self.edge_names[(u, v)] = f"Vỉa hè Đường Bắc-Nam #{c+2}"
                    self.edge_names[(v, u)] = f"Vỉa hè Đường Bắc-Nam #{c+2}"
                    self.edge_offsets[(u, v)] = 3.6
                    self.edge_offsets[(v, u)] = 3.6
                if c + 1 <= 1:
                    v = grid[(r, c + 1)]
                    self.adj[u].add(v)
                    self.adj[v].add(u)
                    self.edge_names[(u, v)] = f"Vỉa hè Đường Đông-Tây #{r+2}"
                    self.edge_names[(v, u)] = f"Vỉa hè Đường Đông-Tây #{r+2}"
                    self.edge_offsets[(u, v)] = 3.6
                    self.edge_offsets[(v, u)] = 3.6

    def build_lead_in_and_random_sidewalk_stream(
        self, num_segments: int = 35
    ) -> Tuple[List[Waypoint], List[Waypoint]]:
        """
        Tạo:
        1. lead_in_points: Đường đi bộ mượt từ đúng vị trí TRONG PHÒNG ra tới mép vỉa hè gần nhất.
        2. sidewalk_points: Hành trình vỉa hè được chọn NGẪU NHIÊN qua các ngã tư quanh phòng.
        """
        all_ids = sorted(
            self.adj.keys(),
            key=lambda nid: distance_meters(
                self.room_lat, self.room_lng, self.nodes[nid].lat, self.nodes[nid].lng
            ),
        )
        entry_id = all_ids[0]
        walk_ids = [entry_id]
        curr = entry_id
        prev = None

        for _ in range(num_segments):
            neighbors = list(self.adj.get(curr, []))
            if not neighbors:
                break
            # Ưu tiên không quay đầu 180 độ ngay lập tức tại ngã tư
            forward = [n for n in neighbors if n != prev]
            in_radius = [
                n
                for n in forward
                if distance_meters(
                    self.room_lat, self.room_lng, self.nodes[n].lat, self.nodes[n].lng
                )
                <= self.radius_m * 1.05
            ]
            candidates = in_radius if in_radius else (forward if forward else neighbors)
            nxt = random.choice(candidates)
            walk_ids.append(nxt)
            prev = curr
            curr = nxt

        # Dịch từng cạnh sang đúng vỉa hè bên phải theo bề rộng đường thực tế
        shifted_corners: List[Waypoint] = []
        for i in range(len(walk_ids) - 1):
            u = walk_ids[i]
            v = walk_ids[i + 1]
            nu = self.nodes[u]
            nv = self.nodes[v]
            d = distance_meters(nu.lat, nu.lng, nv.lat, nv.lng)
            if d < 0.3:
                continue
            brng = bearing_degrees(nu.lat, nu.lng, nv.lat, nv.lng)
            right_normal = (brng + 90.0) % 360.0
            off_m = self.edge_offsets.get((u, v), 3.6)
            st_name = self.edge_names.get((u, v), nu.street_name)

            s_lat1, s_lng1 = offset_by_meters(nu.lat, nu.lng, off_m, right_normal)
            s_lat2, s_lng2 = offset_by_meters(nv.lat, nv.lng, off_m, right_normal)
            shifted_corners.append(Waypoint(s_lat1, s_lng1, st_name, off_m))
            shifted_corners.append(Waypoint(s_lat2, s_lng2, st_name, off_m))

        sidewalk_points = self._smooth_and_subdivide(shifted_corners, spacing_m=1.8)

        # Tạo đường dẫn mượt từ TRONG PHÒNG (room_lat, room_lng) ra tới điểm đầu vỉa hè
        first_sw = sidewalk_points[0]
        second_sw = sidewalk_points[min(1, len(sidewalk_points) - 1)]
        sw_brng = bearing_degrees(first_sw.lat, first_sw.lng, second_sw.lat, second_sw.lng)
        dist_room_to_sw = distance_meters(self.room_lat, self.room_lng, first_sw.lat, first_sw.lng)

        raw_lead = [Waypoint(self.room_lat, self.room_lng, "Trong phòng ➔ Lối ra", 0.0)]
        if dist_room_to_sw > 5.0:
            app_lat, app_lng = offset_by_meters(
                first_sw.lat,
                first_sw.lng,
                min(7.5, dist_room_to_sw * 0.35),
                (sw_brng + 180.0) % 360.0,
            )
            raw_lead.append(Waypoint(app_lat, app_lng, "Cổng nhà / Lối ra vỉa hè", 0.0))
        raw_lead.append(Waypoint(first_sw.lat, first_sw.lng, first_sw.street_name, 0.0))

        lead_in_points = self._subdivide_linear(raw_lead, spacing_m=1.4)
        return lead_in_points, sidewalk_points

    def _subdivide_linear(self, pts: List[Waypoint], spacing_m: float = 1.4) -> List[Waypoint]:
        if len(pts) < 2:
            return pts
        dense: List[Waypoint] = []
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            d = distance_meters(a.lat, a.lng, b.lat, b.lng)
            steps = max(1, int(math.ceil(d / spacing_m)))
            for s in range(steps):
                t = s / float(steps)
                dense.append(
                    Waypoint(
                        a.lat + t * (b.lat - a.lat),
                        a.lng + t * (b.lng - a.lng),
                        a.street_name,
                        a.sidewalk_offset_m,
                    )
                )
        dense.append(pts[-1])
        return dense

    def _smooth_and_subdivide(self, pts: List[Waypoint], spacing_m: float = 1.8) -> List[Waypoint]:
        if len(pts) < 3:
            return self._subdivide_linear(pts, spacing_m)

        # Chaikin corner-cutting (2 lượt) để bo tròn mượt các góc rẽ ngã tư vỉa hè
        curr = list(pts)
        for _ in range(2):
            nxt_pass: List[Waypoint] = [curr[0]]
            for i in range(len(curr) - 1):
                p0, p1 = curr[i], curr[i + 1]
                seg_len = distance_meters(p0.lat, p0.lng, p1.lat, p1.lng)
                if seg_len < 0.4:
                    continue
                ratio = min(0.22, 3.2 / seg_len) if seg_len > 10.0 else 0.25
                nxt_pass.append(
                    Waypoint(
                        p0.lat + ratio * (p1.lat - p0.lat),
                        p0.lng + ratio * (p1.lng - p0.lng),
                        p0.street_name,
                        p0.sidewalk_offset_m,
                    )
                )
                nxt_pass.append(
                    Waypoint(
                        p0.lat + (1.0 - ratio) * (p1.lat - p0.lat),
                        p0.lng + (1.0 - ratio) * (p1.lng - p0.lng),
                        p1.street_name,
                        p1.sidewalk_offset_m,
                    )
                )
            nxt_pass.append(curr[-1])
            curr = nxt_pass

        return self._subdivide_linear(curr, spacing_m)


# ==============================================================================
# 4. BỘ ĐẨY TÍN HIỆU SPOOF GPS QUA COMMAND LINE (ADB / ANDROID / IOS / GPX)
# ==============================================================================
class CommandLineGpsSpooferBackend:
    def __init__(self, apk_path: Optional[str] = None, gpx_out_path: str = "live_spoof_position.gpx"):
        script_dir = os.path.dirname(os.path.abspath(__file__))
        local_adb = os.path.join(script_dir, "platform-tools", "adb")
        self.adb_bin = shutil.which("adb") or (local_adb if os.path.exists(local_adb) else None)
        self.pymobiledevice_bin = shutil.which("pymobiledevice3")
        self.gpx_out_path = gpx_out_path
        self.apk_path = apk_path
        self.mode = "GPX_FILE"
        self.device_name = "Local GPX Stream"
        self._detect_and_prepare_device()

    def _detect_and_prepare_device(self):
        # 1. Kiểm tra thiết bị Android kết nối qua ADB
        if self.adb_bin:
            try:
                out = subprocess.check_output(
                    [self.adb_bin, "devices"], stderr=subprocess.DEVNULL, text=True, timeout=3
                )
                lines = [
                    ln.strip()
                    for ln in out.strip().splitlines()[1:]
                    if ln.strip() and "\tdevice" in ln
                ]
                if lines:
                    serial = lines[0].split("\t")[0]
                    self.device_name = f"Android ({serial})"
                    # Kiểm tra xem SmoothRunner-GPS.apk đã được cài chưa, nếu chưa thì tự cài
                    pkg_out = subprocess.check_output(
                        [self.adb_bin, "shell", "pm", "list", "packages", "com.smoothrunner.gps"],
                        stderr=subprocess.DEVNULL,
                        text=True,
                        timeout=4,
                    )
                    if "com.smoothrunner.gps" not in pkg_out and self.apk_path and os.path.exists(self.apk_path):
                        print(f"[*] Đang tự động cài đặt {os.path.basename(self.apk_path)} qua ADB...")
                        subprocess.run(
                            [self.adb_bin, "install", "-r", self.apk_path],
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                            timeout=20,
                        )
                    # Tự động cấp quyền Mock Location qua dòng lệnh ADB (không cần mở cài đặt bằng tay!)
                    subprocess.run(
                        [
                            self.adb_bin,
                            "shell",
                            "appops",
                            "set",
                            "com.smoothrunner.gps",
                            "android:mock_location",
                            "allow",
                        ],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        timeout=4,
                    )
                    self.mode = "ANDROID_ADB"
                    return
            except Exception:
                pass

        # 2. Kiểm tra nếu đang chạy trực tiếp bên trong Termux trên điện thoại Android
        if os.path.exists("/system/bin/am"):
            self.mode = "ANDROID_LOCAL_SHELL"
            self.device_name = "Android Local Shell (Termux)"
            return

        # 3. Kiểm tra iPhone qua pymobiledevice3
        if self.pymobiledevice_bin:
            self.mode = "IOS_PYMOBILEDEVICE3"
            self.device_name = "iPhone (pymobiledevice3)"

    def try_read_device_real_gps(self) -> Optional[Tuple[float, float]]:
        """Đọc vị trí GPS thật hiện tại trong phòng từ thiết bị Android (nếu đang cắm ADB)"""
        if self.mode == "ANDROID_ADB" and self.adb_bin:
            try:
                out = subprocess.check_output(
                    [self.adb_bin, "shell", "dumpsys", "location"],
                    stderr=subprocess.DEVNULL,
                    text=True,
                    timeout=4,
                )
                m = re.search(r"Location\[(?:gps|network|fused)\s+([0-9.-]+),([0-9.-]+)", out)
                if m:
                    return float(m.group(1)), float(m.group(2))
            except Exception:
                pass
        return None

    def push_location(
        self,
        lat: float,
        lng: float,
        speed_kmh: float,
        bearing_deg: float,
        alt_m: float,
        acc_m: float,
    ):
        # Luôn cập nhật file GPX thời gian thực
        self._write_live_gpx(lat, lng, speed_kmh, bearing_deg, alt_m)

        if self.mode == "ANDROID_ADB" and self.adb_bin:
            # Đẩy trực tiếp vào dịch vụ nền MockGpsService thông qua ADB lệnh CLI_PUSH
            cmd = [
                self.adb_bin,
                "shell",
                "am",
                "start-foreground-service",
                "-n",
                "com.smoothrunner.gps/.MockGpsService",
                "--es",
                "action",
                "CLI_PUSH",
                "--ef",
                "bearing",
                f"{bearing_deg:.2f}",
                "--ef",
                "acc",
                f"{acc_m:.2f}",
                "--ed",
                "lat",
                f"{lat:.7f}",
                "--ed",
                "lng",
                f"{lng:.7f}",
                "--ed",
                "speedKmh",
                f"{speed_kmh:.3f}",
                "--ed",
                "alt",
                f"{alt_m:.2f}",
            ]
            subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        elif self.mode == "ANDROID_LOCAL_SHELL":
            cmd = [
                "/system/bin/am",
                "start-foreground-service",
                "-n",
                "com.smoothrunner.gps/.MockGpsService",
                "--es",
                "action",
                "CLI_PUSH",
                "--ef",
                "bearing",
                f"{bearing_deg:.2f}",
                "--ef",
                "acc",
                f"{acc_m:.2f}",
                "--ed",
                "lat",
                f"{lat:.7f}",
                "--ed",
                "lng",
                f"{lng:.7f}",
                "--ed",
                "speedKmh",
                f"{speed_kmh:.3f}",
                "--ed",
                "alt",
                f"{alt_m:.2f}",
            ]
            subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        elif self.mode == "IOS_PYMOBILEDEVICE3" and self.pymobiledevice_bin:
            cmd = [
                self.pymobiledevice_bin,
                "developer",
                "dvt",
                "simulate-location",
                "set",
                "--",
                f"{lat:.7f}",
                f"{lng:.7f}",
            ]
            subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def stop_spoofing(self):
        if self.mode == "ANDROID_ADB" and self.adb_bin:
            subprocess.run(
                [
                    self.adb_bin,
                    "shell",
                    "am",
                    "startservice",
                    "-n",
                    "com.smoothrunner.gps/.MockGpsService",
                    "--es",
                    "action",
                    "CLI_STOP",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )

    def _write_live_gpx(
        self, lat: float, lng: float, speed_kmh: float, bearing_deg: float, alt_m: float
    ):
        gpx = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<gpx version="1.1" creator="SmoothRunnerCLI">\n'
            f'  <wpt lat="{lat:.7f}" lon="{lng:.7f}">\n'
            f"    <ele>{alt_m:.2f}</ele>\n"
            f"    <speed>{speed_kmh / 3.6:.3f}</speed>\n"
            f"    <course>{bearing_deg:.1f}</course>\n"
            "  </wpt>\n"
            "</gpx>\n"
        )
        try:
            with open(self.gpx_out_path, "w", encoding="utf-8") as f:
                f.write(gpx)
        except Exception:
            pass


# ==============================================================================
# 5. BỘ MÁY VẬT LÝ CHẠY BỘ MƯỢT MÀ TRÊN COMMAND LINE (CLI ENGINE)
# ==============================================================================
def get_default_ktx_khu_a_location() -> Tuple[float, float, str]:
    """
    Toạ độ GPS mặc định tại Ký túc xá Khu A — Đại học Quốc gia TP.HCM
    (Tránh dùng định vị theo IP nhà mạng vì IP VNPT/Viettel thường trỏ nhầm ra trạm Hà Nội).
    """
    return (
        10.875647,
        106.804879,
        "Ký túc xá Khu A - ĐHQG TP.HCM (Đông Hòa / Linh Trung, Thủ Đức)",
    )


def geocode_address(address: str) -> Optional[Tuple[float, float, str]]:
    """Tìm toạ độ GPS chính xác từ địa chỉ nhà/phòng của người dùng qua Nominatim OSM"""
    try:
        url = (
            "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
            + urllib.parse.quote(address)
        )
        req = urllib.request.Request(url, headers={"User-Agent": "SmoothRunnerCLI/1.0"})
        with urllib.request.urlopen(req, timeout=6, context=SSL_CTX) as resp:
            arr = json.loads(resp.read().decode("utf-8"))
            if arr:
                return float(arr[0]["lat"]), float(arr[0]["lon"]), arr[0].get("display_name", address)
    except Exception:
        pass
    return None


def format_mm_ss(seconds: float) -> str:
    seconds = max(0, int(seconds))
    return f"{seconds // 60:02d}:{seconds % 60:02d}"


def build_speed_bar(speed_kmh: float, width: int = 24) -> str:
    ratio = max(0.0, min(1.0, speed_kmh / 16.0))
    filled = int(round(ratio * width))
    return "█" * filled + "░" * (width - filled)


def main():
    parser = argparse.ArgumentParser(
        description="SmoothRunner CLI — Giả lập chạy bộ từ trong phòng ra vỉa hè & chạy trên vỉa hè Random mượt mà"
    )
    parser.add_argument("--lat", type=float, help="Vĩ độ (Latitude) vị trí phòng hiện tại của bạn")
    parser.add_argument("--lng", type=float, help="Kinh độ (Longitude) vị trí phòng hiện tại của bạn")
    parser.add_argument("--address", type=str, help="Hoặc nhập địa chỉ nhà/phòng của bạn để tự tìm toạ độ")
    parser.add_argument("--radius", type=float, default=380.0, help="Bán kính khu vực vỉa hè quanh phòng (mét, mặc định: 380m)")
    parser.add_argument("--run-min", type=float, default=3.0, help="Thời gian chạy tối thiểu mỗi chặng (phút, mặc định: 3)")
    parser.add_argument("--run-max", type=float, default=5.0, help="Thời gian chạy tối đa mỗi chặng (phút, mặc định: 5)")
    parser.add_argument("--rest-min", type=float, default=1.0, help="Thời gian nghỉ tối thiểu (phút, mặc định: 1)")
    parser.add_argument("--rest-max", type=float, default=5.0, help="Thời gian nghỉ tối đa (phút, mặc định: 5)")
    parser.add_argument("--interval", type=float, default=0.25, help="Chu kỳ cập nhật GPS (giây, mặc định: 0.25s = 4Hz)")
    parser.add_argument("--max-ticks", type=int, default=0, help="Số bước chạy thử tối đa (0 = chạy vô hạn tới khi bấm Ctrl+C)")
    args = parser.parse_args()

    script_dir = os.path.dirname(os.path.abspath(__file__))
    apk_path = os.path.join(script_dir, "SmoothRunner-GPS.apk")
    spoofer = CommandLineGpsSpooferBackend(
        apk_path=apk_path,
        gpx_out_path=os.path.join(script_dir, "live_spoof_position.gpx"),
    )

    # 1. Xác định toạ độ GPS trong phòng của người dùng
    room_lat, room_lng, loc_desc = None, None, ""
    if args.lat is not None and args.lng is not None:
        room_lat, room_lng = args.lat, args.lng
        loc_desc = "Toạ độ truyền từ tham số --lat / --lng"
    elif args.address:
        geo = geocode_address(args.address)
        if geo:
            room_lat, room_lng, loc_desc = geo
    if room_lat is None or room_lng is None:
        adb_loc = spoofer.try_read_device_real_gps()
        if adb_loc:
            room_lat, room_lng = adb_loc
            loc_desc = "Đọc trực tiếp từ GPS thật của điện thoại qua ADB"
        else:
            room_lat, room_lng, loc_desc = get_default_ktx_khu_a_location()

    print("=" * 78)
    print(" 🏃 SMOOTH RUNNER GPS SPOOFER — COMMAND LINE EDITION (NO GUI)")
    print("=" * 78)
    print(f" • Thiết bị đích     : {spoofer.device_name} [{spoofer.mode}]")
    print(f" • Vị trí trong phòng: {room_lat:.6f}, {room_lng:.6f} ({loc_desc})")
    print(f" • Chu kỳ Chạy/Nghỉ  : Chạy {args.run_min:.0f}–{args.run_max:.0f} phút (random) | Nghỉ {args.rest_min:.0f}–{args.rest_max:.0f} phút (random)")
    print(f" • Quy tắc chuyển tốc: Chuyển mượt TỪNG NẤC 1 (Nấc 0 <-> 1 <-> 2 <-> 3 <-> 4 <-> 5)")
    print("-" * 78)
    print(f" [*] Đang quét các vỉa hè & lối đi bộ trong bán kính {args.radius:.0f}m quanh phòng bạn...")

    graph = SidewalkNetworkGraph(room_lat, room_lng, args.radius)
    node_count = graph.scan_from_osm()
    lead_in_pts, sidewalk_pts = graph.build_lead_in_and_random_sidewalk_stream(num_segments=40)

    lead_in_dist = sum(
        distance_meters(lead_in_pts[i].lat, lead_in_pts[i].lng, lead_in_pts[i + 1].lat, lead_in_pts[i + 1].lng)
        for i in range(len(lead_in_pts) - 1)
    )
    print(f" [✓] Đã tìm thấy {node_count} nút giao vỉa hè quanh phòng!")
    print(f" [✓] Giai đoạn 1 (Từ phòng ra vỉa hè): Dài {lead_in_dist:.1f}m -> Tiếp cận '{sidewalk_pts[0].street_name}'")
    print(" [✓] Giai đoạn 2 (Trên vỉa hè)       : Tự động Random nhánh rẽ vỉa hè tại mỗi ngã tư")
    print("-" * 78)
    print(" Đang phát tín hiệu GPS giả lập thời gian thực (Nhấn Ctrl+C để dừng và trả lại GPS gốc):\n")

    # Trạng thái vật lý
    is_in_lead_in = True
    active_path = lead_in_pts
    wp_index = 0
    seg_progress_m = 0.0

    curr_lat = room_lat
    curr_lng = room_lng
    curr_bearing = bearing_degrees(
        active_path[0].lat, active_path[0].lng, active_path[min(1, len(active_path) - 1)].lat, active_path[min(1, len(active_path) - 1)].lng
    )
    curr_speed_kmh = 0.0
    curr_accel_kmh_s = 0.0

    current_tier = 1
    ultimate_target_tier = 1
    tier_dwell_rem_s = 6.0

    is_resting = False
    standing_rest_mode = False
    phase_rem_s = random.uniform(args.run_min * 60.0, args.run_max * 60.0)
    intra_run_change_s = random.uniform(40.0, 70.0)

    phase1 = random.uniform(0, math.pi * 2)
    phase2 = random.uniform(0, math.pi * 2)
    phase3 = random.uniform(0, math.pi * 2)
    total_dist_m = 0.0
    total_elapsed_s = 0.0
    lap_drift_m = 0.0

    def handle_sigint(sig, frame):
        print("\n\n[!] Đang dừng Spoof GPS và khôi phục GPS gốc...")
        spoofer.stop_spoofing()
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_sigint)

    tick_count = 0
    dt = args.interval

    while True:
        tick_count += 1
        total_elapsed_s += dt

        # 1. Quản lý giai đoạn từ phòng ra vỉa hè vs. chạy trên vỉa hè
        if is_in_lead_in:
            # Đi bộ từ trong phòng ra vỉa hè ở Mức 1 (3-5 km/h)
            current_tier = 1
            ultimate_target_tier = 1
        else:
            phase_rem_s -= dt
            if phase_rem_s <= 0.0:
                if is_resting:
                    # Hết khoảng nghỉ -> Chuyển sang Pha Chạy (3-5 phút random) & Random tốc độ chạy mới!
                    is_resting = False
                    phase_rem_s = random.uniform(args.run_min * 60.0, args.run_max * 60.0)
                    ultimate_target_tier = random.randint(1, 5)
                    tier_dwell_rem_s = random.uniform(6.0, 10.0)
                    intra_run_change_s = random.uniform(45.0, 75.0)
                else:
                    # Hết chặng chạy -> Chuyển sang Pha Nghỉ (1-5 phút random)
                    is_resting = True
                    phase_rem_s = random.uniform(args.rest_min * 60.0, args.rest_max * 60.0)
                    ultimate_target_tier = 0
                    standing_rest_mode = random.random() < 0.35
                    tier_dwell_rem_s = random.uniform(5.0, 9.0)
            elif not is_resting:
                intra_run_change_s -= dt
                if intra_run_change_s <= 0.0 and current_tier == ultimate_target_tier:
                    ultimate_target_tier = random.randint(1, 5)
                    tier_dwell_rem_s = random.uniform(6.0, 10.0)
                    intra_run_change_s = random.uniform(45.0, 75.0)

            # 2. Chuyển tốc độ TỪNG NẤC MỘT (Step-by-step Tier Transition)
            if current_tier != ultimate_target_tier:
                tier_dwell_rem_s -= dt
                if tier_dwell_rem_s <= 0.0:
                    if current_tier < ultimate_target_tier:
                        current_tier += 1
                    else:
                        current_tier -= 1
                    tier_dwell_rem_s = random.uniform(6.0, 10.0)

        # 3. Tính tốc độ mục tiêu biến thiên liên tục trong nấc hiện tại
        w1 = math.sin(total_elapsed_s * 0.34 + phase1)
        w2 = math.sin(total_elapsed_s * 0.15 + phase2)
        w3 = math.sin(total_elapsed_s * 0.72 + phase3)
        norm_wave = max(0.05, min(0.95, 0.5 + 0.24 * w1 + 0.16 * w2 + 0.08 * w3))

        tier_obj = SPEED_TIERS[current_tier]
        if current_tier == 0:
            target_kmh = (0.05 + 0.25 * norm_wave) if standing_rest_mode else (2.2 + norm_wave * 1.6)
        else:
            target_kmh = tier_obj.min_kmh + norm_wave * (tier_obj.max_kmh - tier_obj.min_kmh)

        # Giảm tốc nhẹ khi ôm cua ở ngã tư vỉa hè
        if not is_in_lead_in and len(active_path) >= 4:
            p0 = active_path[wp_index % len(active_path)]
            p1 = active_path[(wp_index + 1) % len(active_path)]
            p2 = active_path[(wp_index + 3) % len(active_path)]
            b1 = bearing_degrees(p0.lat, p0.lng, p1.lat, p1.lng)
            b2 = bearing_degrees(p1.lat, p1.lng, p2.lat, p2.lng)
            diff_angle = abs(b2 - b1)
            if diff_angle > 180.0:
                diff_angle = 360.0 - diff_angle
            if diff_angle > 35.0:
                target_kmh *= 1.0 - min(0.18, (diff_angle - 35.0) / 300.0)

        # 4. Bộ lọc quán tính bậc 2 (C2 Smooth Speed Filter)
        speed_err = target_kmh - curr_speed_kmh
        desired_accel = max(-0.55, min(0.55, speed_err * 0.65))
        max_accel_delta = 0.40 * dt
        accel_diff = desired_accel - curr_accel_kmh_s
        curr_accel_kmh_s += max(-max_accel_delta, min(max_accel_delta, accel_diff))
        curr_speed_kmh = max(0.0, curr_speed_kmh + curr_accel_kmh_s * dt)

        # 5. Di chuyển dọc theo lộ trình
        step_dist_m = (curr_speed_kmh / 3.6) * dt
        total_dist_m += step_dist_m
        rem = step_dist_m

        while rem > 0.0:
            if wp_index >= len(active_path) - 1:
                if is_in_lead_in:
                    # Đã bước từ trong phòng ra tới vỉa hè! Chuyển sang mạng lưới vỉa hè Random
                    is_in_lead_in = False
                    active_path = sidewalk_pts
                    wp_index = 0
                    seg_progress_m = 0.0
                    is_resting = False
                    phase_rem_s = random.uniform(args.run_min * 60.0, args.run_max * 60.0)
                    ultimate_target_tier = random.randint(1, 5)
                    tier_dwell_rem_s = random.uniform(6.0, 9.0)
                    continue
                else:
                    # Tạo tiếp luồng vỉa hè Random mới nối tiếp điểm hiện tại
                    _, sidewalk_pts = graph.build_lead_in_and_random_sidewalk_stream(num_segments=35)
                    active_path = sidewalk_pts
                    wp_index = 0
                    seg_progress_m = 0.0
                    lap_drift_m = random.uniform(-0.45, 0.45)
                    continue

            a = active_path[wp_index]
            b = active_path[wp_index + 1]
            seg_len = distance_meters(a.lat, a.lng, b.lat, b.lng)
            if seg_len < 0.05:
                wp_index += 1
                seg_progress_m = 0.0
                continue

            left_in_seg = seg_len - seg_progress_m
            if rem < left_in_seg:
                seg_progress_m += rem
                rem = 0.0
            else:
                rem -= left_in_seg
                seg_progress_m = 0.0
                wp_index += 1

        idx = min(wp_index, len(active_path) - 2)
        pa, pb = active_path[idx], active_path[idx + 1]
        seg_len = max(0.05, distance_meters(pa.lat, pa.lng, pb.lat, pb.lng))
        ratio = max(0.0, min(1.0, seg_progress_m / seg_len))
        base_lat = pa.lat + ratio * (pb.lat - pa.lat)
        base_lng = pa.lng + ratio * (pb.lng - pa.lng)

        target_brng = bearing_degrees(pa.lat, pa.lng, pb.lat, pb.lng)
        curr_bearing = smooth_bearing(curr_bearing, target_brng, min(1.0, dt * 6.0))

        # Dao động nhịp bước chân tự nhiên trên vỉa hè
        sway_m = 0.16 * math.sin(total_dist_m * 1.8) + lap_drift_m
        curr_lat, curr_lng = offset_by_meters(base_lat, base_lng, sway_m, (curr_bearing + 90.0) % 360.0)

        alt_m = 8.5 + 0.5 * math.sin(total_dist_m * 0.03)
        acc_m = 6.2 if is_in_lead_in else (3.4 + 1.1 * abs(math.sin(total_elapsed_s * 0.19)))

        # 6. Đẩy toạ độ giả lập xuống thiết bị
        spoofer.push_location(curr_lat, curr_lng, curr_speed_kmh, curr_bearing, alt_m, acc_m)

        # 7. Hiển thị trạng thái trực tiếp trên dòng lệnh (CLI Status Line)
        if is_in_lead_in:
            phase_str = "TỪ PHÒNG RA VỈA HÈ"
        elif is_resting:
            phase_str = f"NGHỈ ({format_mm_ss(phase_rem_s)})"
        else:
            phase_str = f"CHẠY ({format_mm_ss(phase_rem_s)})"

        if current_tier == ultimate_target_tier:
            ladder_str = f"Mức {current_tier} ({tier_obj.name})"
        else:
            arrow = "↗" if current_tier < ultimate_target_tier else "↘"
            ladder_str = f"Mức {current_tier} {arrow} Mức {ultimate_target_tier} ({tier_obj.name})"

        bar = build_speed_bar(curr_speed_kmh, width=16)
        street_label = pa.street_name[:28]

        status_line = (
            f"\r[{format_mm_ss(total_elapsed_s)}] "
            f"GPS: {curr_lat:.6f},{curr_lng:.6f} | "
            f"{curr_speed_kmh:5.2f} km/h [{bar}] | "
            f"{ladder_str:<32} | "
            f"{phase_str:<18} | "
            f"{total_dist_m:6.1f}m | {street_label:<28}"
        )
        sys.stdout.write(status_line)
        sys.stdout.flush()

        if args.max_ticks > 0 and tick_count >= args.max_ticks:
            print("\n[✓] Đã hoàn thành số bước chạy thử (--max-ticks).")
            break

        time.sleep(dt)


if __name__ == "__main__":
    main()
