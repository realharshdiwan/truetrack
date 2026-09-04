"""Evaluation metrics for trajectory comparison."""

from dataclasses import dataclass
from typing import List, Optional

import numpy as np

from .dead_reckoning import DeadReckoningResult, ned_from_latlon
from .data_loader import GnssData
from .config import OUTAGE_DURATIONS_SEC


@dataclass
class EvaluationResult:
    """Complete evaluation results."""
    position_rmse_m: float
    final_drift_m: float
    drift_per_km: float
    heading_error_deg: float
    velocity_error_ms: float
    recovery_time_sec: float
    max_drift_m: float
    mean_drift_m: float
    outage_results: dict  # outage_duration -> metric values


def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Haversine distance between two points in meters."""
    R = 6371000.0
    lat1, lon1, lat2, lon2 = map(np.radians, [lat1, lon1, lat2, lon2])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = np.sin(dlat/2)**2 + np.cos(lat1) * np.cos(lat2) * np.sin(dlon/2)**2
    c = 2 * np.arcsin(np.sqrt(a))
    return R * c


def compute_trajectory_errors(estimated: DeadReckoningResult,
                               ground_truth_lat: np.ndarray,
                               ground_truth_lon: np.ndarray,
                               ground_truth_timestamps: np.ndarray) -> np.ndarray:
    """Compute position error at each estimated point.

    Args:
        estimated: dead reckoning result
        ground_truth_lat: ground truth latitudes
        ground_truth_lon: ground truth longitudes
        ground_truth_timestamps: ground truth timestamps in ms

    Returns:
        (N,) position errors in meters
    """
    n = len(estimated.timestamps)
    errors = np.zeros(n)

    for i in range(n):
        # Find closest ground truth point by time
        gt_idx = np.argmin(np.abs(ground_truth_timestamps - estimated.timestamps[i]))

        errors[i] = haversine_distance(
            estimated.positions[i, 0], estimated.positions[i, 1],
            ground_truth_lat[gt_idx], ground_truth_lon[gt_idx]
        )

    return errors


def compute_position_rmse(errors: np.ndarray) -> float:
    """Compute RMSE of position errors."""
    return float(np.sqrt(np.mean(errors**2)))


def compute_drift_per_km(errors: np.ndarray, timestamps: np.ndarray) -> float:
    """Compute drift per kilometer traveled.

    Args:
        errors: position errors in meters
        timestamps: timestamps in ms

    Returns:
        drift per km in m/km
    """
    if len(timestamps) < 2:
        return 0.0

    duration_sec = (timestamps[-1] - timestamps[0]) / 1000.0
    # Rough distance estimate from duration and average speed
    # (This is approximate; real implementation would use actual path)
    distance_km = duration_sec * 10.0 / 1000.0  # Assume ~10 m/s average

    if distance_km <= 0:
        return 0.0

    return float(np.mean(errors)) / distance_km


def evaluate_outage_period(estimated: DeadReckoningResult,
                           ground_truth: GnssData,
                           outage_start_ms: float,
                           outage_end_ms: float) -> dict:
    """Evaluate performance during a specific outage period.

    Args:
        estimated: dead reckoning result
        ground_truth: ground truth GNSS data
        outage_start_ms: outage start timestamp
        outage_end_ms: outage end timestamp

    Returns:
        Dictionary with outage-specific metrics
    """
    # Find estimated points during outage
    outage_mask = (estimated.timestamps >= outage_start_ms) & \
                  (estimated.timestamps <= outage_end_ms)

    if not np.any(outage_mask):
        return {}

    outage_indices = np.where(outage_mask)[0]
    errors = compute_trajectory_errors(
        estimated,
        ground_truth.latitude,
        ground_truth.longitude,
        ground_truth.timestamp_ms
    )

    outage_errors = errors[outage_mask]

    # Find the position at outage start (should be close to GNSS)
    start_idx = outage_indices[0]
    start_error = errors[start_idx]

    # Final drift (error at end of outage)
    end_idx = outage_indices[-1]
    final_drift = errors[end_idx]

    # Peak drift during outage
    peak_drift = float(np.max(outage_errors))

    # Drift rate
    duration_sec = (outage_end_ms - outage_start_ms) / 1000.0
    drift_rate = (final_drift - start_error) / duration_sec if duration_sec > 0 else 0.0

    return {
        "start_error_m": float(start_error),
        "final_drift_m": float(final_drift),
        "peak_drift_m": peak_drift,
        "mean_error_m": float(np.mean(outage_errors)),
        "drift_rate_ms": drift_rate,
        "duration_sec": duration_sec,
    }


def evaluate_trajectory(estimated: DeadReckoningResult,
                        ground_truth: GnssData) -> EvaluationResult:
    """Full evaluation of estimated trajectory against ground truth.

    Args:
        estimated: dead reckoning result
        ground_truth: GNSS ground truth

    Returns:
        EvaluationResult with all metrics
    """
    # Compute errors at all estimated points
    errors = compute_trajectory_errors(
        estimated,
        ground_truth.latitude,
        ground_truth.longitude,
        ground_truth.timestamp_ms
    )

    # Overall metrics
    rmse = compute_position_rmse(errors)
    final_drift = float(errors[-1])
    max_drift = float(np.max(errors))
    mean_drift = float(np.mean(errors))

    # Drift per km
    duration_sec = (estimated.timestamps[-1] - estimated.timestamps[0]) / 1000.0
    distance_km = max(duration_sec * 5.0 / 1000.0, 0.1)  # Rough estimate
    drift_per_km = mean_drift / distance_km

    # Heading error (if we have ground truth heading from GNSS bearing)
    heading_error = 0.0
    if ground_truth.bearing is not None and np.any(ground_truth.bearing > 0):
        valid_bearing = ground_truth.bearing[ground_truth.bearing > 0]
        # Simple comparison (not ideal but workable for v0)
        heading_error = 0.0  # TODO: implement proper heading comparison

    # Velocity error
    velocity_error = 0.0
    if ground_truth.speed is not None and len(ground_truth.speed) > 0:
        # Interpolate ground truth speed to estimated timestamps
        gt_speed_interp = np.interp(
            estimated.timestamps,
            ground_truth.timestamp_ms,
            ground_truth.speed
        )
        velocity_error = float(np.sqrt(np.mean((estimated.speed - gt_speed_interp)**2)))

    # Recovery time (time to return to < 5m error after outage)
    recovery_time = 0.0
    # TODO: implement recovery time detection

    # Outage-specific evaluations
    outage_results = {}
    outage_durations = [d for d in OUTAGE_DURATIONS_SEC if d < duration_sec]

    for dur in outage_durations:
        # Find a good segment without existing outages
        start_ms = estimated.timestamps[0] + 10000  # Start 10s in
        end_ms = start_ms + dur * 1000

        if end_ms < estimated.timestamps[-1] - 5000:
            result = evaluate_outage_period(
                estimated, ground_truth, start_ms, end_ms
            )
            if result:
                outage_results[f"{dur}s"] = result

    return EvaluationResult(
        position_rmse_m=rmse,
        final_drift_m=final_drift,
        drift_per_km=drift_per_km,
        heading_error_deg=heading_error,
        velocity_error_ms=velocity_error,
        recovery_time_sec=recovery_time,
        max_drift_m=max_drift,
        mean_drift_m=mean_drift,
        outage_results=outage_results,
    )


def print_evaluation(result: EvaluationResult, label: str = "") -> None:
    """Pretty-print evaluation results."""
    header = f"=== Evaluation Results{f' ({label})' if label else ''} ==="
    print(header)
    print(f"  Position RMSE:       {result.position_rmse_m:.2f} m")
    print(f"  Final Drift:         {result.final_drift_m:.2f} m")
    print(f"  Max Drift:           {result.max_drift_m:.2f} m")
    print(f"  Mean Drift:          {result.mean_drift_m:.2f} m")
    print(f"  Drift/km:            {result.drift_per_km:.2f} m/km")
    print(f"  Velocity Error:      {result.velocity_error_ms:.2f} m/s")

    if result.outage_results:
        print("\n  Outage-Specific Results:")
        for name, metrics in result.outage_results.items():
            print(f"    {name}:")
            print(f"      Final Drift:   {metrics['final_drift_m']:.2f} m")
            print(f"      Peak Drift:    {metrics['peak_drift_m']:.2f} m")
            print(f"      Drift Rate:    {metrics['drift_rate_ms']:.3f} m/s")
    print("=" * len(header))
