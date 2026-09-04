"""Baseline dead reckoning using double integration of IMU."""

from dataclasses import dataclass
from typing import Optional

import numpy as np

from .data_loader import ImuData, GnssData
from .config import GRAVITY_MS2, DEG2RAD
from .preprocessing import (
    remove_gravity, lowpass_filter, interpolate_gnss_to_imu
)


@dataclass
class DeadReckoningResult:
    """Result of dead reckoning computation."""
    timestamps: np.ndarray       # (N,)
    positions: np.ndarray        # (N, 2) latitude, longitude
    velocities: np.ndarray       # (N, 2) east, north m/s
    headings: np.ndarray         # (N,) radians
    speed: np.ndarray            # (N,) m/s
    position_ecef: np.ndarray    # (N, 2) local east/north in meters


def mag_heading(mag: np.ndarray) -> float:
    """Compute heading from magnetometer (simple atan2)."""
    return np.arctan2(mag[1], mag[0])


def gyro_heading_integration(gyro_z: np.ndarray, dt: np.ndarray,
                              initial_heading: float = 0.0) -> np.ndarray:
    """Integrate gyroscope z-axis to get heading.

    Args:
        gyro_z: (N,) yaw rate in rad/s
        dt: (N-1,) time deltas
        initial_heading: starting heading in radians

    Returns:
        (N,) heading in radians
    """
    headings = np.zeros(len(gyro_z))
    headings[0] = initial_heading

    for i in range(1, len(gyro_z)):
        delta = gyro_z[i] * dt[i-1]
        headings[i] = headings[i-1] + delta

    # Wrap to [-pi, pi]
    headings = (headings + np.pi) % (2 * np.pi) - np.pi
    return headings


def ned_from_latlon(lat: np.ndarray, lon: np.ndarray,
                     ref_lat: float, ref_lon: float) -> np.ndarray:
    """Convert lat/lon to local North-East-Down in meters.

    Args:
        lat: (N,) latitude in degrees
        lon: (N,) longitude in degrees
        ref_lat: reference latitude in degrees
        ref_lon: reference longitude in degrees

    Returns:
        (N, 2) [north, east] in meters
    """
    R = 6371000.0  # Earth radius in meters
    dlat = np.radians(lat - ref_lat)
    dlon = np.radians(lon - ref_lon)

    north = dlat * R
    east = dlon * R * np.cos(np.radians(ref_lat))

    return np.column_stack([north, east])


def latlon_from_ned(ned: np.ndarray, ref_lat: float, ref_lon: float) -> tuple:
    """Convert local NED back to lat/lon.

    Args:
        ned: (N, 2) [north, east] in meters
        ref_lat: reference latitude in degrees
        ref_lon: reference longitude in degrees

    Returns:
        (lat, lon) arrays in degrees
    """
    R = 6371000.0
    ref_lat_rad = np.radians(ref_lat)

    lat = ref_lat + np.degrees(ned[:, 0] / R)
    lon = ref_lon + np.degrees(ned[:, 1] / (R * np.cos(ref_lat_rad)))

    return lat, lon


def run_dead_reckoning(imu: ImuData, gnss: Optional[GnssData] = None,
                        sample_rate_hz: float = 100.0) -> DeadReckoningResult:
    """Run basic dead reckoning from IMU data.

    Integration approach:
    1. Remove gravity from accelerometer
    2. Lowpass filter noise
    3. Double-integrate acceleration -> velocity -> position
    4. Integrate gyro z-axis for heading
    5. Use magnetometer or initial GNSS for initial heading

    This is the BASELINE. It will drift. That's expected.

    Args:
        imu: IMU data
        gnss: Optional GNSS data for initial conditions
        sample_rate_hz: IMU sampling rate

    Returns:
        DeadReckoningResult with estimated trajectory
    """
    N = imu.sample_count
    dt = imu.dt

    # Step 1: Remove gravity if rotation vector available
    if imu.rotation is not None and len(imu.rotation) == N:
        linear_accel = remove_gravity(imu.accel, imu.rotation)
    else:
        # Fallback: assume phone is roughly level, remove gravity from z
        linear_accel = imu.accel.copy()
        linear_accel[:, 2] -= GRAVITY_MS2

    # Step 2: Lowpass filter
    linear_accel = lowpass_filter(linear_accel, cutoff_hz=20.0, sample_rate_hz=sample_rate_hz)
    gyro_filtered = lowpass_filter(imu.gyro, cutoff_hz=20.0, sample_rate_hz=sample_rate_hz)

    # Step 3: Initial heading from magnetometer or GNSS
    initial_heading = 0.0
    ref_lat, ref_lon = 0.0, 0.0

    if gnss is not None and gnss.sample_count > 0:
        ref_lat = gnss.latitude[0]
        ref_lon = gnss.longitude[0]
        # Use GNSS bearing if available and valid
        if gnss.bearing[0] > 0:
            initial_heading = gnss.bearing[0] * DEG2RAD

    # Use magnetometer heading as fallback
    if initial_heading == 0.0 and imu.mag.shape[0] > 0:
        mag = imu.mag[0]
        if np.linalg.norm(mag[:2]) > 1e-6:
            initial_heading = mag_heading(mag)

    # Step 4: Heading from gyro integration
    headings = gyro_heading_integration(gyro_filtered[:, 2], dt, initial_heading)

    # Step 5: Double integration
    velocities = np.zeros((N, 2))
    positions = np.zeros((N, 2))

    for i in range(1, N):
        # Rotation matrix from heading
        cos_h = np.cos(headings[i-1])
        sin_h = np.sin(headings[i-1])

        # Transform body-frame accel to local frame (NE)
        accel_ne = np.array([
            -linear_accel[i-1, 0] * sin_h + linear_accel[i-1, 1] * cos_h,
            linear_accel[i-1, 0] * cos_h + linear_accel[i-1, 1] * sin_h
        ])

        # Integrate acceleration to velocity
        velocities[i] = velocities[i-1] + accel_ne * dt[i-1]

        # Apply very light damping to prevent divergence
        velocities[i] *= 0.9999

        # Integrate velocity to position
        positions[i] = positions[i-1] + velocities[i] * dt[i-1]

    # Compute speed
    speed = np.linalg.norm(velocities, axis=1)

    # Convert NED positions back to lat/lon
    lat, lon = latlon_from_ned(positions, ref_lat, ref_lon)

    return DeadReckoningResult(
        timestamps=imu.timestamp_ms,
        positions=np.column_stack([lat, lon]),
        velocities=velocities,
        headings=headings,
        speed=speed,
        position_ecef=positions,
    )


def apply_gnss_correction(dr_result: DeadReckoningResult,
                           gnss: GnssData,
                           correction_window_sec: float = 2.0) -> DeadReckoningResult:
    """Apply GNSS position reset when available.

    This is the simplest fusion: just reset position to GNSS when we have it.

    Args:
        dr_result: dead reckoning result
        gnss: GNSS data
        correction_window_sec: time window for position correction

    Returns:
        Corrected dead reckoning result
    """
    corrected_positions = dr_result.positions.copy()
    corrected_ned = dr_result.position_ecef.copy()

    # For each GNSS fix, find closest DR point and reset
    for i in range(gnss.sample_count):
        gnss_time = gnss.timestamp_ms[i]
        idx = np.argmin(np.abs(dr_result.timestamps - gnss_time))

        if gnss.h_accuracy[i] > 0 and gnss.h_accuracy[i] < 30.0:
            # Reset NED position
            ref_ned = ned_from_latlon(
                np.array([gnss.latitude[i]]),
                np.array([gnss.longitude[i]]),
                dr_result.positions[0, 0],
                dr_result.positions[0, 1]
            )
            corrected_ned[idx:] += ref_ned[0] - corrected_ned[idx]
            corrected_positions[idx:] = dr_result.positions[idx:]  # Keep lat/lon

    return DeadReckoningResult(
        timestamps=dr_result.timestamps,
        positions=corrected_positions,
        velocities=dr_result.velocities,
        headings=dr_result.headings,
        speed=dr_result.speed,
        position_ecef=corrected_ned,
    )
