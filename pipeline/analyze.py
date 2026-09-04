#!/usr/bin/env python3
"""Main analysis pipeline for TrueTrack v0.1.

Usage:
    python analyze.py <recording_directory> [--output <output_dir>]
"""

import argparse
import sys
from pathlib import Path

import numpy as np

from truetrack.data_loader import load_recording
from truetrack.preprocessing import (
    lowpass_filter, interpolate_gnss_to_imu, remove_gravity
)
from truetrack.dead_reckoning import run_dead_reckoning
from truetrack.outage_simulator import (
    generate_standard_scenarios, create_outage_mask
)
from truetrack.evaluation import evaluate_trajectory, print_evaluation
from truetrack.visualization import (
    plot_trajectory_comparison, plot_sensor_data, plot_gnss_quality,
    generate_demo_visualization
)


def main():
    parser = argparse.ArgumentParser(description="TrueTrack v0.1 Analysis Pipeline")
    parser.add_argument("recording_dir", type=Path,
                        help="Path to recording directory")
    parser.add_argument("--output", "-o", type=Path, default=None,
                        help="Output directory (default: recording_dir/analysis)")
    parser.add_argument("--rate", type=float, default=100.0,
                        help="IMU sample rate in Hz (default: 100)")
    parser.add_argument("--skip-viz", action="store_true",
                        help="Skip visualization generation")
    args = parser.parse_args()

    recording_dir = args.recording_dir
    if not recording_dir.exists():
        print(f"Error: Recording directory not found: {recording_dir}")
        sys.exit(1)

    output_dir = args.output or recording_dir / "analysis"
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Loading recording from: {recording_dir}")
    recording = load_recording(recording_dir)

    print(f"  IMU: {recording.imu.sample_count} samples, "
          f"{recording.imu.avg_rate_hz:.1f} Hz, "
          f"{recording.imu.duration_sec:.1f}s")
    print(f"  GNSS: {recording.gnss.sample_count} fixes, "
          f"{recording.gnss.mean_accuracy:.1f}m accuracy")

    # Step 1: Run baseline dead reckoning
    print("\n--- Running Dead Reckoning ---")
    dr_result = run_dead_reckoning(
        recording.imu,
        recording.gnss,
        sample_rate_hz=args.rate
    )
    print(f"  DR trajectory: {dr_result.sample_count} points")

    # Step 2: Evaluate against GNSS ground truth
    print("\n--- Evaluating Against GNSS Ground Truth ---")
    eval_result = evaluate_trajectory(dr_result, recording.gnss)
    print_evaluation(eval_result, "Dead Reckoning")

    # Step 3: Generate visualizations
    if not args.skip_viz:
        print("\n--- Generating Visualizations ---")

        plot_trajectory_comparison(
            dr_result,
            recording.gnss.latitude,
            recording.gnss.longitude,
            gnss_lat=recording.gnss.latitude,
            gnss_lon=recording.gnss.longitude,
            title="TrueTrack v0.1 — Dead Reckoning vs GNSS",
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
    print("\n--- Simulating GNSS Outages ---")
    scenarios = generate_standard_scenarios(recording.gnss.duration_sec)

    for scenario in scenarios:
        print(f"\n  Scenario: {scenario.name}")
        print(f"  {scenario.description}")

        # Create outage mask
        mask = create_outage_mask(recording.gnss.timestamp_ms, scenario.outages)

        # TODO: Run DR during outage and evaluate
        # This requires re-running DR with gaps in GNSS correction
        # For now, just log the scenario
        n_out = np.sum(~mask)
        print(f"  Outage points: {n_out}/{len(mask)} "
              f"({100*n_out/len(mask):.1f}%)")

    print(f"\n--- Analysis Complete ---")
    print(f"Results saved to: {output_dir}")

    # Save summary
    summary_path = output_dir / "summary.txt"
    with open(summary_path, "w") as f:
        f.write("TrueTrack v0.1 Analysis Summary\n")
        f.write("=" * 40 + "\n\n")
        f.write(f"Recording: {recording_dir}\n")
        f.write(f"IMU samples: {recording.imu.sample_count}\n")
        f.write(f"GNSS fixes: {recording.gnss.sample_count}\n")
        f.write(f"Duration: {recording.imu.duration_sec:.1f}s\n\n")
        f.write(f"Dead Reckoning Results:\n")
        f.write(f"  RMSE: {eval_result.position_rmse_m:.2f} m\n")
        f.write(f"  Final Drift: {eval_result.final_drift_m:.2f} m\n")
        f.write(f"  Max Drift: {eval_result.max_drift_m:.2f} m\n")
        f.write(f"  Drift/km: {eval_result.drift_per_km:.2f} m/km\n")

    print(f"Summary saved to: {summary_path}")


if __name__ == "__main__":
    main()
