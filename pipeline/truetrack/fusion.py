"""Simple IMU + GNSS sensor fusion using a complementary filter.

Architecture (from the plan):
  IMU → motion model → predicted state
  GNSS → measurement update → corrected state

When GNSS is available:
  - Use GNSS position directly
  - Reset velocity estimate to GNSS-derived velocity
  - Accumulate IMU biases

When GNSS is lost:
  - Use IMU dead reckoning with corrected biases
  - Apply non-holonomic constraints (vehicle doesn't fly or slide sideways)
"""

from dataclasses import dataclass

import numpy as np

from .data_loader import ImuData, GnssData
from .config import GRAVITY_MS2
from .dead_reckoning import (
    ned_from_latlon, latlon_from_ned, remove_gravity,
    gyro_heading_integration, mag_heading
)
from .preprocessing import lowpass_filter


@dataclass
class FusionResult:
    """Fusion result with full state."""
    timestamps: np.ndarray       # (N,)
    positions: np.ndarray        # (N, 2) lat, lon
    velocities: np.ndarray       # (N, 2) east, north m/s
    headings: np.ndarray         # (N,) radians
    speed: np.ndarray            # (N,) m/s
    gnss_available: np.ndarray   # (N,) bool
    position_ned: np.ndarray     # (N, 2) local NED in meters
    bias_ax: np.ndarray          # (N,) accelerometer x bias
    bias_gz: np.ndarray          # (N,) gyro z bias


def run_fusion(imu: ImuData, gnss: GnssData,
               sample_rate_hz: float = 10.0,
               gps_noise_m: float = 5.0,
               imu_noise_m: float = 50.0,
               bias_learning_rate: float = 0.01) -> FusionResult:
    """Run complementary filter IMU+GNSS fusion.

    The filter works as follows:
    1. Predict: Use IMU to propagate state forward
    2. Correct: When GNSS is available, blend IMU prediction with GNSS measurement
    3. The blend ratio depends on the estimated noise of each source

    During GNSS outage:
    - Pure IMU dead reckoning
    - Biases estimated during GNSS-available periods are used
    - Non-holonomic constraints applied (no lateral velocity for ground vehicles)

    Args:
        imu: IMU data
        gnss: GNSS data
        sample_rate_hz: IMU sampling rate
        gps_noise_m: estimated GPS noise (meters)
        imu_noise_m: estimated IMU drift per second (meters)
        bias_learning_rate: how fast to learn IMU biases

    Returns:
        FusionResult with fused trajectory
    """
    N = imu.sample_count
    dt_arr = imu.dt

    # Remove gravity
    if imu.rotation is not None and len(imu.rotation) == N:
        linear_accel = remove_gravity(imu.accel, imu.rotation)
    else:
        linear_accel = imu.accel.copy()
        linear_accel[:, 2] -= GRAVITY_MS2

    # Filter
    linear_accel = lowpass_filter(linear_accel, cutoff_hz=15.0, sample_rate_hz=sample_rate_hz)
    gyro = lowpass_filter(imu.gyro, cutoff_hz=15.0, sample_rate_hz=sample_rate_hz)

    # Initialize state
    ref_lat = gnss.latitude[0] if gnss.sample_count > 0 else 0.0
    ref_lon = gnss.longitude[0] if gnss.sample_count > 0 else 0.0

    positions = np.zeros((N, 2))  # lat, lon
    velocities = np.zeros((N, 2))  # east, north
    headings = np.zeros(N)
    speed = np.zeros(N)
    gnss_avail = np.zeros(N, dtype=bool)
    position_ned = np.zeros((N, 2))
    bias_ax = np.zeros(N)
    bias_gz = np.zeros(N)

    # Initial heading from magnetometer or GNSS
    if gnss.sample_count > 0 and gnss.bearing[0] > 0:
        headings[0] = gnss.bearing[0] * np.pi / 180.0
    elif imu.mag.shape[0] > 0:
        headings[0] = mag_heading(imu.mag[0])

    # Current state estimates
    vel = np.zeros(2)
    pos_ned = np.zeros(2)
    b_ax = 0.0
    b_gz = 0.0
    heading = headings[0]

    # Create GNSS lookup (timestamp -> index)
    gnss_time_to_idx = {}
    for i in range(gnss.sample_count):
        t = int(gnss.timestamp_ms[i])
        gnss_time_to_idx[t] = i

    # GNSS mask: which IMU samples have corresponding GNSS
    gnss_mask = np.zeros(N, dtype=bool)
    gnss_positions = np.zeros((N, 2))

    for i in range(N):
        t = int(imu.timestamp_ms[i])
        # Look for GNSS within 100ms window
        for dt_offset in range(-1, 2):
            if (t + dt_offset) in gnss_time_to_idx:
                gi = gnss_time_to_idx[t + dt_offset]
                if gnss.gnss_available[gi] and gnss.h_accuracy[gi] > 0 and gnss.h_accuracy[gi] < 30.0:
                    gnss_mask[i] = True
                    gnss_ned = ned_from_latlon(
                        np.array([gnss.latitude[gi]]),
                        np.array([gnss.longitude[gi]]),
                        ref_lat, ref_lon
                    )
                    gnss_positions[i] = gnss_ned[0]
                    break

    # Main fusion loop
    for i in range(1, N):
        dt = dt_arr[i - 1]
        if dt <= 0 or dt > 0.5:
            dt = 1.0 / sample_rate_hz

        # === PREDICTION (IMU) ===
        # Apply bias-corrected acceleration
        ax_corrected = linear_accel[i - 1, 0] - b_ax
        ay_corrected = linear_accel[i - 1, 1]

        cos_h = np.cos(heading)
        sin_h = np.sin(heading)

        # Transform body-frame accel to local frame (NE)
        accel_ne = np.array([
            -ax_corrected * sin_h + ay_corrected * cos_h,
            ax_corrected * cos_h + ay_corrected * sin_h
        ])

        # Integrate
        vel_new = vel + accel_ne * dt

        # Non-holonomic constraint: for ground vehicles, limit lateral velocity
        # This is a simple version - later we can make this adaptive
        cos_h_new = np.cos(heading + gyro[i - 1, 2] * dt)
        sin_h_new = np.sin(heading + gyro[i - 1, 2] * dt)
        lateral_vel = -vel_new[0] * sin_h_new + vel_new[1] * cos_h_new
        # Soft constraint: don't completely zero it, just dampen
        vel_new[0] += lateral_vel * sin_h_new * 0.3
        vel_new[1] -= lateral_vel * cos_h_new * 0.3

        pos_new = pos_ned + vel_new * dt

        heading_new = heading + (gyro[i - 1, 2] - b_gz) * dt
        heading_new = (heading_new + np.pi) % (2 * np.pi) - np.pi

        # === CORRECTION (GNSS) ===
        gnss_avail[i] = gnss_mask[i]

        if gnss_mask[i]:
            # GNSS position error
            pos_error = gnss_positions[i] - pos_new

            # Blending weight: high when GPS is good, low when noisy
            # Simple model: weight = imu_noise^2 / (imu_noise^2 + gps_noise^2)
            gps_quality = min(gnss.h_accuracy[
                np.argmin(np.abs(gnss.timestamp_ms - imu.timestamp_ms[i]))
            ], 30.0)
            w_gps = imu_noise_m**2 / (imu_noise_m**2 + gps_quality**2)
            w_imu = 1.0 - w_gps

            # Blend position
            pos_ned = w_imu * pos_new + w_gps * gnss_positions[i]

            # Correct velocity using GNSS speed and heading
            gnss_idx = np.argmin(np.abs(gnss.timestamp_ms - imu.timestamp_ms[i]))
            gnss_speed = gnss.speed[gnss_idx]
            if gnss.bearing[gnss_idx] > 0:
                gnss_heading = gnss.bearing[gnss_idx] * np.pi / 180.0
                gnss_vel = np.array([
                    gnss_speed * np.sin(gnss_heading),
                    gnss_speed * np.cos(gnss_heading)
                ])
                vel = w_imu * vel_new + w_gps * gnss_vel
            else:
                vel = vel_new

            heading = heading_new

            # Learn bias: accumulate error when GPS is available
            b_ax += bias_learning_rate * pos_error[0] * 0.01
            b_gz += bias_learning_rate * pos_error[1] * 0.001

            # Bias bounds
            b_ax = np.clip(b_ax, -2.0, 2.0)
            b_gz = np.clip(b_gz, -0.1, 0.1)
        else:
            # Pure IMU propagation
            vel = vel_new
            pos_ned = pos_new
            heading = heading_new

        # Store state
        lat, lon = latlon_from_ned(pos_ned.reshape(1, 2), ref_lat, ref_lon)
        positions[i] = [lat[0], lon[0]]
        velocities[i] = vel
        headings[i] = heading
        speed[i] = np.linalg.norm(vel)
        position_ned[i] = pos_ned
        bias_ax[i] = b_ax
        bias_gz[i] = b_gz

    positions[0] = [ref_lat, ref_lon]

    return FusionResult(
        timestamps=imu.timestamp_ms,
        positions=positions,
        velocities=velocities,
        headings=headings,
        speed=speed,
        gnss_available=gnss_avail,
        position_ned=position_ned,
        bias_ax=bias_ax,
        bias_gz=bias_gz,
    )


def run_fusion_with_outages(imu: ImuData, gnss: GnssData,
                             outage_mask: np.ndarray,
                             sample_rate_hz: float = 10.0) -> FusionResult:
    """Run fusion with simulated GNSS outage.

    Args:
        imu: IMU data
        gnss: GNSS data
        outage_mask: boolean array, True = GNSS available, False = outage
        sample_rate_hz: IMU sampling rate

    Returns:
        FusionResult with fusion during available periods, DR during outages
    """
    # Create modified GNSS data with outage periods removed
    # Keep the timestamps but mark availability
    modified_gnss = GnssData(
        timestamp_ms=gnss.timestamp_ms,
        latitude=gnss.latitude,
        longitude=gnss.longitude,
        altitude=gnss.altitude,
        speed=gnss.speed,
        bearing=gnss.bearing,
        h_accuracy=gnss.h_accuracy,
        v_accuracy=gnss.v_accuracy,
        satellites_used=gnss.satellites_used,
        satellites_total=gnss.satellites_total,
        gnss_available=outage_mask,
    )

    return run_fusion(imu, modified_gnss, sample_rate_hz)
