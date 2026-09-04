#!/usr/bin/env python3
"""Post-process Android TrueTrack recordings.

Usage:
    python process_recording.py <recording_dir> [--outage 30]
    python process_recording.py ../android/app/  # auto-find recordings
"""

import argparse
import sys
from pathlib import Path

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

sys.path.insert(0, str(Path(__file__).parent))

from truetrack.data_loader import load_recording
from truetrack.dead_reckoning import run_dead_reckoning
from truetrack.fusion import run_fusion
from truetrack.outage_simulator import create_outage_mask, OutageConfig
from truetrack.evaluation import haversine_distance, print_evaluation
from truetrack.visualization import plot_trajectory_comparison, plot_sensor_data, plot_gnss_quality


def process_recording(recording_dir: Path, outage_sec: int = 30):
    """Process a TrueTrack recording from the Android app."""

    print("=" * 60)
    print("TrueTrack v0.1 — Recording Processor")
    print("=" * 60)

    # Load recording
    print(f"\n[1] Loading recording from: {recording_dir}")
    recording = load_recording(recording_dir)

    print(f"    IMU: {recording.imu.sample_count} samples, "
          f"{recording.imu.avg_rate_hz:.1f} Hz, {recording.imu.duration_sec:.1f}s")
    print(f"    GNSS: {recording.gnss.sample_count} fixes, "
          f"{recording.gnss.mean_accuracy:.1f}m accuracy")

    # Create output directory
    output_dir = recording_dir / "analysis"
    output_dir.mkdir(exist_ok=True)

    # Generate sensor plots
    print(f"\n[2] Generating sensor plots...")
    plot_sensor_data(
        recording.imu.timestamp_ms,
        recording.imu.accel,
        recording.imu.gyro,
        save_path=output_dir / "sensors.png",
    )
    plot_gnss_quality(
        recording.gnss.timestamp_ms,
        recording.gnss.h_accuracy,
        recording.gnss.satellites_used,
        save_path=output_dir / "gnss_quality.png",
    )

    # Run dead reckoning
    print(f"\n[3] Running dead reckoning...")
    dr_result = run_dead_reckoning(recording.imu, recording.gnss, sample_rate_hz=100.0)

    # Evaluate
    print(f"\n[4] Evaluating against GNSS ground truth...")
    eval_result = evaluate_trajectory(dr_result, recording.gnss)
    print_evaluation(eval_result, "Dead Reckoning")

    # Run fusion
    print(f"\n[5] Running IMU + GNSS fusion...")
    fusion_result = run_fusion(recording.imu, recording.gnss, sample_rate_hz=100.0)

    # Generate trajectory plot
    print(f"\n[6] Generating trajectory comparison...")
    plot_trajectory_comparison(
        dr_result,
        recording.gnss.latitude,
        recording.gnss.longitude,
        ground_truth_timestamps=recording.gnss.timestamp_ms,
        gnss_lat=recording.gnss.latitude,
        gnss_lon=recording.gnss.longitude,
        title="TrueTrack — Dead Reckoning vs GPS",
        save_path=output_dir / "trajectory.png",
    )

    # Simulate outage
    print(f"\n[7] Simulating {outage_sec}s GNSS outage...")
    duration_sec = recording.gnss.duration_sec
    outage_start = duration_sec / 2

    outage_config = OutageConfig(start_sec=outage_start, duration_sec=outage_sec)
    outage_mask = create_outage_mask(recording.gnss.timestamp_ms, [outage_config])

    gnss_outage = type(recording.gnss)(
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
        gnss_available=outage_mask,
    )

    # Run both during outage
    dr_outage = run_dead_reckoning(recording.imu, gnss_outage, sample_rate_hz=100.0)
    fu_outage = run_fusion(recording.imu, gnss_outage, sample_rate_hz=100.0)

    # Compute errors
    gt_lat = recording.gnss.latitude
    gt_lon = recording.gnss.longitude
    gt_ts = recording.gnss.timestamp_ms

    outage_start_ms = recording.gnss.timestamp_ms[0] + outage_start * 1000
    outage_end_ms = outage_start_ms + outage_sec * 1000

    dr_out_mask = (dr_outage.timestamps >= outage_start_ms) & (dr_outage.timestamps <= outage_end_ms)
    fu_out_mask = (fu_outage.timestamps >= outage_start_ms) & (fu_outage.timestamps <= outage_end_ms)

    dr_indices = np.where(dr_out_mask)[0]
    fu_indices = np.where(fu_out_mask)[0]

    if len(dr_indices) > 0 and len(fu_indices) > 0:
        dr_errors = np.array([haversine_distance(
            dr_outage.positions[i, 0], dr_outage.positions[i, 1],
            gt_lat[np.argmin(np.abs(gt_ts - dr_outage.timestamps[i]))],
            gt_lon[np.argmin(np.abs(gt_ts - dr_outage.timestamps[i]))]
        ) for i in dr_indices])

        fu_errors = np.array([haversine_distance(
            fu_outage.positions[i, 0], fu_outage.positions[i, 1],
            gt_lat[np.argmin(np.abs(gt_ts - fu_outage.timestamps[i]))],
            gt_lon[np.argmin(np.abs(gt_ts - fu_outage.timestamps[i]))]
        ) for i in fu_indices])

        print(f"\n    {outage_sec}s Outage Results:")
        print(f"    {'Raw DR Peak:':<20} {np.max(dr_errors):.1f}m")
        print(f"    {'TrueTrack Peak:':<20} {np.max(fu_errors):.1f}m")
        improvement = (1 - np.max(fu_errors) / np.max(dr_errors)) * 100
        print(f"    {'Improvement:':<20} {improvement:.1f}%")

        # Plot outage comparison
        fig, ax = plt.subplots(1, 1, figsize=(10, 6))
        t_dr = np.linspace(0, outage_sec, len(dr_errors))
        t_fu = np.linspace(0, outage_sec, len(fu_errors))

        ax.plot(t_dr, dr_errors, color="#E53935", linewidth=2.5, label="Raw DR")
        ax.plot(t_fu, fu_errors, color="#2196F3", linewidth=2.5, label="TrueTrack")
        ax.fill_between(t_dr, 0, dr_errors, alpha=0.15, color="#E53935")
        ax.fill_between(t_fu, 0, fu_errors, alpha=0.15, color="#2196F3")
        ax.set_xlabel("Time during outage (s)")
        ax.set_ylabel("Position Error (m)")
        ax.set_title(f"GNSS Outage Test — {outage_sec}s")
        ax.legend()
        ax.grid(True, alpha=0.3)
        plt.tight_layout()
        plt.savefig(output_dir / f"outage_{outage_sec}s.png", dpi=150)
        plt.close()

    # Save summary
    with open(output_dir / "summary.txt", "w") as f:
        f.write("TrueTrack Recording Analysis\n")
        f.write("=" * 40 + "\n\n")
        f.write(f"Recording: {recording_dir}\n")
        f.write(f"IMU samples: {recording.imu.sample_count}\n")
        f.write(f"Duration: {recording.imu.duration_sec:.1f}s\n")
        f.write(f"GNSS accuracy: {recording.gnss.mean_accuracy:.1f}m\n\n")
        f.write(f"Dead Reckoning:\n")
        f.write(f"  RMSE: {eval_result.position_rmse_m:.2f} m\n")
        f.write(f"  Final Drift: {eval_result.final_drift_m:.2f} m\n")

    print(f"\n[8] Results saved to: {output_dir}")
    print(f"    - sensors.png")
    print(f"    - gnss_quality.png")
    print(f"    - trajectory.png")
    print(f"    - outage_{outage_sec}s.png")
    print(f"    - summary.txt")

    return output_dir


def main():
    parser = argparse.ArgumentParser(description="TrueTrack Recording Processor")
    parser.add_argument("recording_dir", type=Path, help="Path to recording directory")
    parser.add_argument("--outage", type=int, default=30, help="Outage duration to simulate")
    args = parser.parse_args()

    if not args.recording_dir.exists():
        print(f"Error: Directory not found: {args.recording_dir}")
        sys.exit(1)

    process_recording(args.recording_dir, args.outage)


if __name__ == "__main__":
    main()
