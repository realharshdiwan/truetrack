#!/usr/bin/env python3
"""TrueTrack Demo — Fair comparison: DR vs Fusion during GNSS outage.

Both start from the same GNSS-corrected position at outage onset.
Only drift DURING the outage is measured.
"""

import sys
import time
from pathlib import Path

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

sys.path.insert(0, str(Path(__file__).parent))

from truetrack.iovnbd_loader import load_iovnbd_recording
from truetrack.dead_reckoning import run_dead_reckoning, ned_from_latlon, latlon_from_ned
from truetrack.fusion import run_fusion
from truetrack.outage_simulator import create_outage_mask, OutageConfig
from truetrack.evaluation import haversine_distance
from truetrack.preprocessing import lowpass_filter, remove_gravity
from truetrack.config import GRAVITY_MS2
from truetrack.dead_reckoning import gyro_heading_integration, mag_heading

DATA_DIR = Path(__file__).parent.parent / "data" / "IO-VNBD" / \
    "Synchronised V abd S datasets" / "Uncategorised IOVNB Dataset"
OUTPUT_DIR = Path(__file__).parent.parent / "data" / "demo_output"


def run_dr_from_position(imu_segment, initial_lat, initial_lon, initial_heading,
                          initial_vel, sample_rate_hz=10.0):
    """Run dead reckoning from a specific starting position."""
    N = len(imu_segment.timestamp_ms)
    dt = imu_segment.dt

    # Remove gravity
    if imu_segment.rotation is not None and len(imu_segment.rotation) == N:
        accel = remove_gravity(imu_segment.accel, imu_segment.rotation)
    else:
        accel = imu_segment.accel.copy()
        accel[:, 2] -= GRAVITY_MS2

    accel = lowpass_filter(accel, cutoff_hz=15.0, sample_rate_hz=sample_rate_hz)
    gyro = lowpass_filter(imu_segment.gyro, cutoff_hz=15.0, sample_rate_hz=sample_rate_hz)

    ref_lat, ref_lon = initial_lat, initial_lon
    pos_ned = np.zeros(2)
    vel = initial_vel.copy()
    heading = initial_heading

    positions = np.zeros((N, 2))
    positions[0] = [ref_lat, ref_lon]

    for i in range(1, N):
        d = dt[i - 1] if dt[i - 1] > 0 and dt[i - 1] < 0.5 else 1.0 / sample_rate_hz

        ax = accel[i - 1, 0]
        ay = accel[i - 1, 1]
        cos_h = np.cos(heading)
        sin_h = np.sin(heading)

        accel_ne = np.array([-ax * sin_h + ay * cos_h, ax * cos_h + ay * sin_h])
        vel = vel + accel_ne * d
        vel *= 0.9999  # light damping
        pos_ned = pos_ned + vel * d

        heading = heading + gyro[i - 1, 2] * d
        heading = (heading + np.pi) % (2 * np.pi) - np.pi

        lat, lon = latlon_from_ned(pos_ned.reshape(1, 2), ref_lat, ref_lon)
        positions[i] = [lat[0], lon[0]]

    return positions, vel, heading


def run_fusion_segment(imu_segment, gnss_segment, initial_lat, initial_lon,
                        sample_rate_hz=10.0):
    """Run fusion on a segment with initial conditions from GNSS."""
    fusion_result = run_fusion(imu_segment, gnss_segment, sample_rate_hz)
    return fusion_result


def run_demo():
    print("=" * 60)
    print("TrueTrack v0.1 — Fair Outage Comparison Demo")
    print("=" * 60)

    # Load data
    v_path = DATA_DIR / "V-Dataset/V-S1.csv"
    s_path = DATA_DIR / "S-Dataset/S-S1.csv"
    print("\n[1] Loading IO-VNBD S1...")
    recording = load_iovnbd_recording(v_path=v_path, s_path=s_path)
    print(f"    {recording.imu.sample_count} samples, {recording.imu.duration_sec:.0f}s")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # Test different outage durations
    results_table = []

    for outage_sec in [5, 10, 30, 60, 120]:
        print(f"\n[2] Testing {outage_sec}s outage...")

        duration_sec = recording.gnss.duration_sec
        outage_start_sec = duration_sec / 2
        outage_start_ms = recording.gnss.timestamp_ms[0] + outage_start_sec * 1000
        outage_end_ms = outage_start_ms + outage_sec * 1000

        # Get initial conditions from GNSS at outage start
        start_idx = np.argmin(np.abs(recording.gnss.timestamp_ms - outage_start_ms))
        initial_lat = recording.gnss.latitude[start_idx]
        initial_lon = recording.gnss.longitude[start_idx]
        initial_speed = recording.gnss.speed[start_idx]
        initial_heading = np.radians(recording.gnss.bearing[start_idx]) if recording.gnss.bearing[start_idx] > 0 else 0.0
        initial_vel = np.array([
            initial_speed * np.sin(initial_heading),
            initial_speed * np.cos(initial_heading)
        ])

        # Extract IMU segment during outage
        imu_mask = (recording.imu.timestamp_ms >= outage_start_ms) & \
                   (recording.imu.timestamp_ms <= outage_end_ms)
        imu_indices = np.where(imu_mask)[0]
        if len(imu_indices) < 2:
            continue

        # Create IMU segment
        from truetrack.data_loader import ImuData
        imu_segment = ImuData(
            timestamp_ms=recording.imu.timestamp_ms[imu_indices],
            accel=recording.imu.accel[imu_indices],
            gyro=recording.imu.gyro[imu_indices],
            mag=recording.imu.mag[imu_indices],
            rotation=recording.imu.rotation[imu_indices] if recording.imu.rotation is not None else None,
        )

        # Create GNSS segment (empty during outage)
        gnss_segment = type(recording.gnss)(
            timestamp_ms=recording.gnss.timestamp_ms[imu_indices] if len(imu_indices) <= len(recording.gnss.timestamp_ms) else recording.gnss.timestamp_ms[:len(imu_indices)],
            latitude=recording.gnss.latitude[start_idx:start_idx+len(imu_indices)] if start_idx+len(imu_indices) <= len(recording.gnss.latitude) else np.full(len(imu_indices), initial_lat),
            longitude=recording.gnss.longitude[start_idx:start_idx+len(imu_indices)] if start_idx+len(imu_indices) <= len(recording.gnss.longitude) else np.full(len(imu_indices), initial_lon),
            altitude=recording.gnss.altitude[start_idx:start_idx+len(imu_indices)] if start_idx+len(imu_indices) <= len(recording.gnss.altitude) else np.zeros(len(imu_indices)),
            speed=recording.gnss.speed[start_idx:start_idx+len(imu_indices)] if start_idx+len(imu_indices) <= len(recording.gnss.speed) else np.zeros(len(imu_indices)),
            bearing=recording.gnss.bearing[start_idx:start_idx+len(imu_indices)] if start_idx+len(imu_indices) <= len(recording.gnss.bearing) else np.zeros(len(imu_indices)),
            h_accuracy=recording.gnss.h_accuracy[start_idx:start_idx+len(imu_indices)] if start_idx+len(imu_indices) <= len(recording.gnss.h_accuracy) else np.full(len(imu_indices), 30.0),
            v_accuracy=recording.gnss.v_accuracy[start_idx:start_idx+len(imu_indices)] if start_idx+len(imu_indices) <= len(recording.gnss.v_accuracy) else np.full(len(imu_indices), 10.0),
            satellites_used=recording.gnss.satellites_used[start_idx:start_idx+len(imu_indices)] if start_idx+len(imu_indices) <= len(recording.gnss.satellites_used) else np.zeros(len(imu_indices)),
            satellites_total=recording.gnss.satellites_total[start_idx:start_idx+len(imu_indices)] if start_idx+len(imu_indices) <= len(recording.gnss.satellites_total) else np.zeros(len(imu_indices)),
            gnss_available=np.zeros(len(imu_indices), dtype=bool),  # All unavailable
        )

        # Run raw DR from same starting position
        dr_positions, _, _ = run_dr_from_position(
            imu_segment, initial_lat, initial_lon, initial_heading, initial_vel
        )

        # Run fusion on the segment (no GNSS = pure DR with bias correction from before)
        # For fair comparison, use the full recording fusion and extract the outage period
        full_gnss_outage = create_outage_mask(
            recording.gnss.timestamp_ms,
            [OutageConfig(start_sec=outage_start_sec, duration_sec=outage_sec)]
        )
        gnss_full_outage = type(recording.gnss)(
            timestamp_ms=recording.gnss.timestamp_ms,
            latitude=recording.gnss.latitude,
            longitude=recording.gnss.longitude,
            altitude=recording.gnss.altitude,
            speed=recording.gnss.speed,
            bearing=recording.gnss.bearing,
            h_accuracy=recording.gnss.h_accuracy,
            v_accuracy=recording.gnss.v_accuracy,
            satellites_used=recording.gnss.satellites_used,
            satellites_total=recording.gnss.satellites_total,
            gnss_available=full_gnss_outage,
        )
        fusion_result = run_fusion(recording.imu, gnss_full_outage, sample_rate_hz=10.0)

        # Extract fusion during outage
        fu_mask = (fusion_result.timestamps >= outage_start_ms) & \
                  (fusion_result.timestamps <= outage_end_ms)
        fu_indices = np.where(fu_mask)[0]

        # Compute errors
        gt_lat = recording.gnss.latitude
        gt_lon = recording.gnss.longitude
        gt_ts = recording.gnss.timestamp_ms

        dr_errors = np.array([haversine_distance(
            dr_positions[i, 0], dr_positions[i, 1],
            gt_lat[np.argmin(np.abs(gt_ts - imu_segment.timestamp_ms[i]))],
            gt_lon[np.argmin(np.abs(gt_ts - imu_segment.timestamp_ms[i]))]
        ) for i in range(len(imu_segment.timestamp_ms))])

        fu_errors = np.array([haversine_distance(
            fusion_result.positions[i, 0], fusion_result.positions[i, 1],
            gt_lat[np.argmin(np.abs(gt_ts - fusion_result.timestamps[i]))],
            gt_lon[np.argmin(np.abs(gt_ts - fusion_result.timestamps[i]))]
        ) for i in fu_indices]) if len(fu_indices) > 0 else np.array([0])

        dr_peak = np.max(dr_errors)
        fu_peak = np.max(fu_errors)
        dr_rate = dr_peak / outage_sec
        fu_rate = fu_peak / outage_sec
        improvement = (1 - fu_peak / dr_peak) * 100 if dr_peak > 0 else 0

        results_table.append({
            "outage": outage_sec,
            "dr_peak": dr_peak,
            "dr_rate": dr_rate,
            "fu_peak": fu_peak,
            "fu_rate": fu_rate,
            "improvement": improvement,
        })

        print(f"    DR: {dr_peak:.1f}m peak, {dr_rate:.1f}m/s")
        print(f"    TT: {fu_peak:.1f}m peak, {fu_rate:.1f}m/s ({improvement:.1f}% better)")

        # Generate plots for this outage
        if outage_sec in [30, 60]:
            _plot_comparison(
                dr_positions, fusion_result, imu_segment, fu_indices,
                gt_lat, gt_lon, gt_ts, initial_lat, initial_lon,
                outage_start_ms, outage_end_ms, outage_sec,
                dr_errors, fu_errors,
                OUTPUT_DIR / f"outage_{outage_sec}s.png"
            )

    # Print summary table
    print(f"\n{'='*75}")
    print(f"{'TrueTrack v0.1 — Summary':^75}")
    print(f"{'='*75}")
    print(f"{'Outage':>8} {'Raw DR Peak':>14} {'Raw DR Rate':>14} {'TrueTrack Peak':>16} {'TT Rate':>10} {'Better':>10}")
    print(f"{'-'*75}")
    for r in results_table:
        print(f"{r['outage']:>5}s   {r['dr_peak']:>12.1f}m {r['dr_rate']:>12.1f}m/s "
              f"{r['fu_peak']:>14.1f}m {r['fu_rate']:>8.1f}m/s {r['improvement']:>8.1f}%")
    print(f"{'='*75}")

    # Save summary
    with open(OUTPUT_DIR / "summary.txt", "w") as f:
        f.write("TrueTrack v0.1 — Hackathon Demo Results\n")
        f.write("IO-VNBD Sequence S1, 86 min driving\n\n")
        f.write(f"{'Outage':>8} {'Raw DR Peak':>14} {'TT Peak':>12} {'Improvement':>12}\n")
        for r in results_table:
            f.write(f"{r['outage']:>5}s   {r['dr_peak']:>12.1f}m {r['fu_peak']:>10.1f}m {r['improvement']:>10.1f}%\n")

    print(f"\nResults saved to: {OUTPUT_DIR}")


def _plot_comparison(dr_pos, fu_result, imu_seg, fu_indices,
                      gt_lat, gt_lon, gt_ts, init_lat, init_lon,
                      outage_start_ms, outage_end_ms, outage_sec,
                      dr_errors, fu_errors, save_path):
    """Generate comparison plot for a single outage."""
    fig, axes = plt.subplots(1, 2, figsize=(14, 6))

    # Left: 2D trajectory during outage
    ax1 = axes[0]

    # Ground truth during outage
    gt_mask = (gt_ts >= outage_start_ms) & (gt_ts <= outage_end_ms)
    ax1.plot(gt_lon[gt_mask], gt_lat[gt_mask], "o-",
             color="#FFC107", linewidth=2, markersize=3, label="GPS Truth")

    # DR trajectory
    ax1.plot(dr_pos[:, 1], dr_pos[:, 0], "-",
             color="#E53935", linewidth=2, label="Raw DR")

    # Fusion trajectory
    fu_pos = fu_result.positions[fu_indices]
    ax1.plot(fu_pos[:, 1], fu_pos[:, 0], "-",
             color="#2196F3", linewidth=2, label="TrueTrack")

    # Start point
    ax1.plot(init_lon, init_lat, "go", markersize=10, label="Start", zorder=5)

    ax1.set_xlabel("Longitude")
    ax1.set_ylabel("Latitude")
    ax1.set_title(f"{outage_sec}s GNSS Outage — 2D Trajectory")
    ax1.legend(fontsize=9)
    ax1.grid(True, alpha=0.3)

    # Right: Error over time
    ax2 = axes[1]
    time_dr = np.linspace(0, outage_sec, len(dr_errors))
    time_fu = np.linspace(0, outage_sec, len(fu_errors))

    ax2.fill_between(time_dr, 0, dr_errors, alpha=0.2, color="#E53935")
    ax2.fill_between(time_fu, 0, fu_errors, alpha=0.2, color="#2196F3")
    ax2.plot(time_dr, dr_errors, color="#E53935", linewidth=2.5, label="Raw DR")
    ax2.plot(time_fu, fu_errors, color="#2196F3", linewidth=2.5, label="TrueTrack")

    ax2.axhline(y=10, color="gray", linestyle=":", alpha=0.5, label="10m")
    ax2.axhline(y=50, color="gray", linestyle="--", alpha=0.5, label="50m")

    if len(dr_errors) > 0:
        ax2.annotate(f"DR: {dr_errors[-1]:.0f}m",
                    xy=(time_dr[-1], dr_errors[-1]),
                    fontsize=10, fontweight="bold", color="#E53935",
                    xytext=(5, 5), textcoords="offset points")
    if len(fu_errors) > 0:
        ax2.annotate(f"TT: {fu_errors[-1]:.0f}m",
                    xy=(time_fu[-1], fu_errors[-1]),
                    fontsize=10, fontweight="bold", color="#2196F3",
                    xytext=(5, -10), textcoords="offset points")

    ax2.set_xlabel("Time during outage (s)")
    ax2.set_ylabel("Position Error (m)")
    ax2.set_title(f"Error During {outage_sec}s Outage")
    ax2.legend(fontsize=9)
    ax2.grid(True, alpha=0.3)

    plt.tight_layout()
    plt.savefig(save_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"    Plot saved: {save_path.name}")


if __name__ == "__main__":
    run_demo()
