"""Visualization tools for trajectory comparison."""

from pathlib import Path
from typing import Optional, List

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

from .dead_reckoning import DeadReckoningResult
from .evaluation import EvaluationResult
from .config import COLOR_GNSS, COLOR_DR, COLOR_TRUETRACK, COLOR_GROUND_TRUTH


def plot_trajectory_comparison(
    estimated: DeadReckoningResult,
    ground_truth_lat: np.ndarray,
    ground_truth_lon: np.ndarray,
    ground_truth_timestamps: Optional[np.ndarray] = None,
    gnss_lat: Optional[np.ndarray] = None,
    gnss_lon: Optional[np.ndarray] = None,
    title: str = "Trajectory Comparison",
    save_path: Optional[Path] = None,
    show: bool = False,
) -> None:
    """Plot estimated vs ground truth trajectory.

    Args:
        estimated: dead reckoning result
        ground_truth_lat/lon: ground truth positions
        gnss_lat/lon: raw GNSS positions (if different from ground truth)
        title: plot title
        save_path: path to save figure
        show: whether to display
    """
    fig, axes = plt.subplots(1, 2, figsize=(16, 8))

    # Left panel: 2D trajectory
    ax1 = axes[0]
    ax1.plot(ground_truth_lon, ground_truth_lat, "o-",
             color=COLOR_GROUND_TRUTH, linewidth=1, markersize=2,
             label="Ground Truth", alpha=0.7)

    if gnss_lon is not None and gnss_lat is not None:
        ax1.plot(gnss_lon, gnss_lat, ".",
                 color=COLOR_GNSS, markersize=3,
                 label="Raw GNSS", alpha=0.5)

    ax1.plot(estimated.positions[:, 1], estimated.positions[:, 0], "-",
             color=COLOR_DR, linewidth=1.5,
             label="Dead Reckoning")

    ax1.set_xlabel("Longitude")
    ax1.set_ylabel("Latitude")
    ax1.set_title("2D Trajectory")
    ax1.legend()
    ax1.grid(True, alpha=0.3)
    ax1.set_aspect("equal")

    # Right panel: Error over time
    ax2 = axes[1]

    # Compute errors
    from .evaluation import haversine_distance
    n_est = len(estimated.timestamps)
    gt_ts = ground_truth_timestamps if ground_truth_timestamps is not None else estimated.timestamps
    errors = np.zeros(n_est)
    for i in range(n_est):
        gt_idx = np.argmin(np.abs(gt_ts - estimated.timestamps[i]))
        errors[i] = haversine_distance(
            estimated.positions[i, 0], estimated.positions[i, 1],
            ground_truth_lat[gt_idx], ground_truth_lon[gt_idx]
        )

    time_sec = (estimated.timestamps - estimated.timestamps[0]) / 1000.0
    ax2.plot(time_sec, errors, color=COLOR_DR, linewidth=1.5, label="Position Error")
    ax2.axhline(y=5.0, color="gray", linestyle="--", alpha=0.5, label="5m threshold")
    ax2.axhline(y=10.0, color="gray", linestyle=":", alpha=0.5, label="10m threshold")
    ax2.set_xlabel("Time (s)")
    ax2.set_ylabel("Position Error (m)")
    ax2.set_title("Error Over Time")
    ax2.legend()
    ax2.grid(True, alpha=0.3)

    plt.suptitle(title, fontsize=14, fontweight="bold")
    plt.tight_layout()

    if save_path:
        plt.savefig(save_path, dpi=150, bbox_inches="tight")
        print(f"Saved: {save_path}")

    if show:
        plt.show()
    plt.close()


def plot_outage_comparison(
    results: dict,  # outage_name -> DeadReckoningResult
    ground_truth_lat: np.ndarray,
    ground_truth_lon: np.ndarray,
    ground_truth_timestamps: np.ndarray,
    save_dir: Path,
) -> None:
    """Plot comparison across different outage durations.

    Args:
        results: dict mapping outage name to DeadReckoningResult
        ground_truth: ground truth GNSS data
        save_dir: directory to save plots
    """
    n_results = len(results)
    fig, axes = plt.subplots(1, n_results, figsize=(6 * n_results, 6))

    if n_results == 1:
        axes = [axes]

    for ax, (name, result) in zip(axes, results.items()):
        from .evaluation import haversine_distance

        n_res = len(result.timestamps)
        errors = np.zeros(n_res)
        for i in range(n_res):
            gt_idx = np.argmin(np.abs(ground_truth_timestamps - result.timestamps[i]))
            errors[i] = haversine_distance(
                result.positions[i, 0], result.positions[i, 1],
                ground_truth_lat[gt_idx], ground_truth_lon[gt_idx]
            )

        time_sec = (result.timestamps - result.timestamps[0]) / 1000.0
        ax.plot(time_sec, errors, color=COLOR_DR, linewidth=1.5)
        ax.axhline(y=5.0, color="gray", linestyle="--", alpha=0.5)
        ax.set_xlabel("Time (s)")
        ax.set_ylabel("Error (m)")
        ax.set_title(f"Outage: {name}")
        ax.grid(True, alpha=0.3)

        # Annotate final drift
        ax.annotate(f"Final: {errors[-1]:.1f}m",
                    xy=(time_sec[-1], errors[-1]),
                    fontsize=10, fontweight="bold",
                    xytext=(10, 10), textcoords="offset points")

    plt.suptitle("Drift During GNSS Outages", fontsize=14, fontweight="bold")
    plt.tight_layout()

    save_path = save_dir / "outage_comparison.png"
    plt.savefig(save_path, dpi=150, bbox_inches="tight")
    print(f"Saved: {save_path}")
    plt.close()


def plot_sensor_data(imu_timestamps: np.ndarray,
                     accel: np.ndarray,
                     gyro: np.ndarray,
                     save_path: Optional[Path] = None) -> None:
    """Plot raw IMU sensor data."""
    fig, axes = plt.subplots(2, 1, figsize=(14, 8))

    time_sec = (imu_timestamps - imu_timestamps[0]) / 1000.0

    # Accelerometer
    ax1 = axes[0]
    ax1.plot(time_sec, accel[:, 0], label="ax", alpha=0.7)
    ax1.plot(time_sec, accel[:, 1], label="ay", alpha=0.7)
    ax1.plot(time_sec, accel[:, 2], label="az", alpha=0.7)
    ax1.set_ylabel("Acceleration (m/s²)")
    ax1.set_title("Accelerometer")
    ax1.legend()
    ax1.grid(True, alpha=0.3)

    # Gyroscope
    ax2 = axes[1]
    ax2.plot(time_sec, gyro[:, 0], label="gx", alpha=0.7)
    ax2.plot(time_sec, gyro[:, 1], label="gy", alpha=0.7)
    ax2.plot(time_sec, gyro[:, 2], label="gz", alpha=0.7)
    ax2.set_xlabel("Time (s)")
    ax2.set_ylabel("Angular Velocity (rad/s)")
    ax2.set_title("Gyroscope")
    ax2.legend()
    ax2.grid(True, alpha=0.3)

    plt.tight_layout()

    if save_path:
        plt.savefig(save_path, dpi=150, bbox_inches="tight")
        print(f"Saved: {save_path}")
    plt.close()


def plot_gnss_quality(gnss_timestamps: np.ndarray,
                       h_accuracy: np.ndarray,
                       satellites_used: np.ndarray,
                       save_path: Optional[Path] = None) -> None:
    """Plot GNSS quality metrics."""
    fig, axes = plt.subplots(2, 1, figsize=(14, 6))

    time_sec = (gnss_timestamps - gnss_timestamps[0]) / 1000.0

    ax1 = axes[0]
    ax1.plot(time_sec, h_accuracy, color=COLOR_GNSS, linewidth=1)
    ax1.axhline(y=5.0, color="gray", linestyle="--", alpha=0.5, label="5m")
    ax1.axhline(y=15.0, color="gray", linestyle=":", alpha=0.5, label="15m")
    ax1.set_ylabel("Horizontal Accuracy (m)")
    ax1.set_title("GNSS Accuracy")
    ax1.legend()
    ax1.grid(True, alpha=0.3)

    ax2 = axes[1]
    ax2.bar(time_sec, satellites_used, width=1.0, color=COLOR_GNSS, alpha=0.7)
    ax2.set_xlabel("Time (s)")
    ax2.set_ylabel("Satellites Used")
    ax2.set_title("Satellite Count")
    ax2.grid(True, alpha=0.3)

    plt.tight_layout()

    if save_path:
        plt.savefig(save_path, dpi=150, bbox_inches="tight")
        print(f"Saved: {save_path}")
    plt.close()


def generate_demo_visualization(
    estimated: DeadReckoningResult,
    ground_truth_lat: np.ndarray,
    ground_truth_lon: np.ndarray,
    save_dir: Path,
    gnss_lat: Optional[np.ndarray] = None,
    gnss_lon: Optional[np.ndarray] = None,
) -> None:
    """Generate the "brutally obvious" demo visualization from the plan.

    This creates the single-panel comparison showing:
    - Ground truth
    - Raw dead reckoning
    - Estimated position with error overlay
    """
    from .evaluation import haversine_distance, compute_trajectory_errors

    fig, ax = plt.subplots(1, 1, figsize=(12, 8))

    # Ground truth
    ax.plot(ground_truth_lon, ground_truth_lat, "-",
            color=COLOR_GROUND_TRUTH, linewidth=2, label="Ground Truth")

    # Raw GNSS
    if gnss_lon is not None and gnss_lat is not None:
        ax.plot(gnss_lon, gnss_lat, ".",
                color=COLOR_GNSS, markersize=4, alpha=0.5, label="Raw GNSS")

    # Estimated trajectory
    ax.plot(estimated.positions[:, 1], estimated.positions[:, 0], "-",
            color=COLOR_DR, linewidth=2, label="Dead Reckoning")

    # Mark start and end
    ax.plot(ground_truth_lon[0], ground_truth_lat[0], "go", markersize=10, label="Start")
    ax.plot(ground_truth_lon[-1], ground_truth_lat[-1], "rs", markersize=10, label="End")

    # Error annotations
    errors = compute_trajectory_errors(
        estimated,
        ground_truth_lat,
        ground_truth_lon,
        estimated.timestamps  # Approximate
    )

    # Annotate max error point
    max_err_idx = np.argmax(errors)
    ax.annotate(f"Max: {errors[max_err_idx]:.1f}m",
                xy=(estimated.positions[max_err_idx, 1], estimated.positions[max_err_idx, 0]),
                fontsize=10, fontweight="bold",
                xytext=(20, 20), textcoords="offset points",
                arrowprops=dict(arrowstyle="->", color="red"),
                color="red")

    ax.set_xlabel("Longitude", fontsize=12)
    ax.set_ylabel("Latitude", fontsize=12)
    ax.set_title("TrueTrack v0.1 — Dead Reckoning Performance", fontsize=14, fontweight="bold")
    ax.legend(loc="upper left")
    ax.grid(True, alpha=0.3)

    plt.tight_layout()

    save_path = save_dir / "demo_trajectory.png"
    plt.savefig(save_path, dpi=150, bbox_inches="tight")
    print(f"Saved: {save_path}")
    plt.close()
