#!/usr/bin/env python3
"""Analyze IO-VNBD dataset with TrueTrack pipeline.

Usage:
    python analyze_iovnbd.py <iovnbd_directory> [--output <output_dir>]
    python analyze_iovnbd.py --v <v_csv> --s <s_csv> [--output <output_dir>]

This script loads IO-VNBD data, runs dead reckoning, simulates GNSS outages,
and evaluates performance against ground truth GPS.
"""

import argparse
import sys
from pathlib import Path

import numpy as np

from truetrack.iovnbd_loader import (
    load_v_dataset, load_s_dataset, load_iovnbd_recording, list_iovnbd_recordings
)
from truetrack.dead_reckoning import run_dead_reckoning
from truetrack.outage_simulator import generate_standard_scenarios, create_outage_mask
from truetrack.evaluation import evaluate_trajectory, print_evaluation
from truetrack.visualization import (
    plot_trajectory_comparison, plot_sensor_data, plot_gnss_quality,
    generate_demo_visualization, plot_outage_comparison
)


def analyze_single(v_path: Path = None, s_path: Path = None, output_dir: Path = None):
    """Analyze a single IO-VNBD recording."""
    print(f"\n{'='*60}")
    print(f"Analyzing: {v_path or s_path}")
    print(f"{'='*60}")

    recording = load_iovnbd_recording(v_path=v_path, s_path=s_path)

    print(f"\nData loaded:")
    print(f"  IMU: {recording.imu.sample_count} samples, "
          f"{recording.imu.avg_rate_hz:.1f} Hz, "
          f"{recording.imu.duration_sec:.1f}s")
    print(f"  GNSS: {recording.gnss.sample_count} fixes, "
          f"{recording.gnss.mean_accuracy:.1f}m accuracy")

    # Step 1: Run dead reckoning
    print(f"\n--- Running Dead Reckoning ---")
    dr_result = run_dead_reckoning(
        recording.imu,
        recording.gnss,
        sample_rate_hz=10.0  # IO-VNBD is 10Hz
    )
    print(f"  DR trajectory: {dr_result.sample_count} points")

    # Step 2: Evaluate
    print(f"\n--- Evaluating Against GPS Ground Truth ---")
    eval_result = evaluate_trajectory(dr_result, recording.gnss)
    print_evaluation(eval_result, "Dead Reckoning (IO-VNBD)")

    # Step 3: Generate visualizations
    print(f"\n--- Generating Visualizations ---")
    output_dir.mkdir(parents=True, exist_ok=True)

    plot_trajectory_comparison(
        dr_result,
        recording.gnss.latitude,
        recording.gnss.longitude,
        gnss_lat=recording.gnss.latitude,
        gnss_lon=recording.gnss.longitude,
        title="IO-VNBD — Dead Reckoning vs GPS",
        save_path=output_dir / "trajectory_comparison.png",
    )

    plot_sensor_data(
        recording.imu.timestamp_ms,
        recording.imu.accel,
        recording.imu.gyro,
        save_path=output_dir / "sensor_data.png",
    )

    plot_gnss_quality(
        recording.gnss.timestamp_ms,
        recording.gnss.h_accuracy,
        recording.gnss.satellites_used,
        save_path=output_dir / "gnss_quality.png",
    )

    generate_demo_visualization(
        dr_result,
        recording.gnss.latitude,
        recording.gnss.longitude,
        output_dir,
        gnss_lat=recording.gnss.latitude,
        gnss_lon=recording.gnss.longitude,
    )

    # Step 4: Outage simulation
    print(f"\n--- Simulating GNSS Outages ---")
    scenarios = generate_standard_scenarios(recording.gnss.duration_sec)

    outage_results = {}
    for scenario in scenarios:
        print(f"\n  Scenario: {scenario.name}")
        print(f"  {scenario.description}")

        mask = create_outage_mask(recording.gnss.timestamp_ms, scenario.outages)
        n_out = np.sum(~mask)
        print(f"  Outage points: {n_out}/{len(mask)} ({100*n_out/len(mask):.1f}%)")

        # TODO: Run DR during outage and evaluate
        # For now, we just log the scenarios

    # Save summary
    summary_path = output_dir / "summary.txt"
    with open(summary_path, "w") as f:
        f.write("IO-VNBD Analysis Summary (TrueTrack v0.1)\n")
        f.write("=" * 50 + "\n\n")
        f.write(f"Source: {v_path or s_path}\n")
        f.write(f"IMU samples: {recording.imu.sample_count}\n")
        f.write(f"GNSS fixes: {recording.gnss.sample_count}\n")
        f.write(f"Duration: {recording.imu.duration_sec:.1f}s\n\n")
        f.write(f"Dead Reckoning Results:\n")
        f.write(f"  RMSE: {eval_result.position_rmse_m:.2f} m\n")
        f.write(f"  Final Drift: {eval_result.final_drift_m:.2f} m\n")
        f.write(f"  Max Drift: {eval_result.max_drift_m:.2f} m\n")
        f.write(f"  Drift/km: {eval_result.drift_per_km:.2f} m/km\n")

    print(f"\nSummary saved to: {summary_path}")

    return eval_result


def main():
    parser = argparse.ArgumentParser(
        description="Analyze IO-VNBD dataset with TrueTrack pipeline"
    )
    parser.add_argument("data_dir", nargs="?", type=Path,
                        help="Directory containing V-*.csv and S-*.csv files")
    parser.add_argument("--v", type=Path, help="Path to specific V-Dataset CSV")
    parser.add_argument("--s", type=Path, help="Path to specific S-Dataset CSV")
    parser.add_argument("--output", "-o", type=Path, default=None,
                        help="Output directory")
    parser.add_argument("--list", action="store_true",
                        help="List available recordings and exit")
    args = parser.parse_args()

    if args.list:
        if not args.data_dir:
            print("Error: data_dir required with --list")
            sys.exit(1)
        recordings = list_iovnbd_recordings(args.data_dir)
        print(f"\nFound {len(recordings)} recordings in {args.data_dir}:\n")
        for rec in recordings:
            v_status = "V" if rec["v_path"] else " "
            s_status = "S" if rec["s_path"] else " "
            print(f"  [{v_status}{s_status}] {rec['name']}")
        return

    if args.v or args.s:
        # Analyze specific files
        output_dir = args.output or Path("iovnbd_analysis")
        analyze_single(v_path=args.v, s_path=args.s, output_dir=output_dir)

    elif args.data_dir:
        # Analyze all recordings in directory
        recordings = list_iovnbd_recordings(args.data_dir)
        print(f"Found {len(recordings)} recordings")

        for rec in recordings[:5]:  # Limit to first 5 for initial analysis
            output_dir = (args.output or Path("iovnbd_analysis")) / rec["name"]
            analyze_single(
                v_path=rec["v_path"],
                s_path=rec["s_path"],
                output_dir=output_dir
            )
    else:
        print("Error: Provide data_dir or --v/--s paths")
        sys.exit(1)


if __name__ == "__main__":
    main()
