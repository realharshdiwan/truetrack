"""Preprocessing utilities for IMU and GNSS data."""

import numpy as np
from scipy.signal import butter, filtfilt
from .config import GRAVITY_MS2

# Constants
DEG2RAD = np.pi / 180.0


def remove_gravity(accel: np.ndarray, rotation: np.ndarray) -> np.ndarray:
    """Remove gravity from accelerometer readings using rotation vector.

    Args:
        accel: (N, 3) raw accelerometer readings (m/s^2)
        rotation: (N, 5) rotation vector from Android (x, y, z, scalar, accuracy)

    Returns:
        (N, 3) linear acceleration (gravity removed)
    """
    linear_accel = np.zeros_like(accel)

    for i in range(len(accel)):
        # Android rotation vector -> rotation matrix
        q = rotation[i, :4]  # x, y, z, w
        qw = q[3]
        qx, qy, qz = q[0], q[1], q[2]

        # Rotation matrix from quaternion
        R = np.array([
            [1 - 2*(qy**2 + qz**2), 2*(qx*qy - qz*qw), 2*(qx*qz + qy*qw)],
            [2*(qx*qy + qz*qw), 1 - 2*(qx**2 + qz**2), 2*(qy*qz - qx*qw)],
            [2*(qx*qz - qy*qw), 2*(qy*qz + qx*qw), 1 - 2*(qx**2 + qy**2)]
        ])

        # Gravity in world frame is [0, 0, g]
        # Transform to sensor frame and subtract
        gravity_sensor = R.T @ np.array([0, 0, GRAVITY_MS2])
        linear_accel[i] = accel[i] - gravity_sensor

    return linear_accel


def lowpass_filter(signal: np.ndarray, cutoff_hz: float, sample_rate_hz: float, order: int = 4) -> np.ndarray:
    """Apply Butterworth lowpass filter.

    Args:
        signal: (N,) or (N, D) signal to filter
        cutoff_hz: cutoff frequency in Hz
        sample_rate_hz: sampling rate in Hz
        order: filter order

    Returns:
        Filtered signal
    """
    nyquist = sample_rate_hz / 2.0
    normalized_cutoff = cutoff_hz / nyquist

    if normalized_cutoff >= 1.0:
        return signal  # Cutoff too high, no filtering

    b, a = butter(order, normalized_cutoff, btype="low")

    if signal.ndim == 1:
        return filtfilt(b, a, signal)
    else:
        return np.apply_along_axis(lambda x: filtfilt(b, a, x), 0, signal)


def highpass_filter(signal: np.ndarray, cutoff_hz: float, sample_rate_hz: float, order: int = 2) -> np.ndarray:
    """Apply Butterworth highpass filter."""
    nyquist = sample_rate_hz / 2.0
    normalized_cutoff = cutoff_hz / nyquist

    if normalized_cutoff <= 0.0:
        return signal

    b, a = butter(order, normalized_cutoff, btype="high")

    if signal.ndim == 1:
        return filtfilt(b, a, signal)
    else:
        return np.apply_along_axis(lambda x: filtfilt(b, a, x), 0, signal)


def bandpass_filter(signal: np.ndarray, low_hz: float, high_hz: float, sample_rate_hz: float, order: int = 2) -> np.ndarray:
    """Apply Butterworth bandpass filter."""
    nyquist = sample_rate_hz / 2.0
    low_norm = low_hz / nyquist
    high_norm = high_hz / nyquist

    if low_norm <= 0.0 or high_norm >= 1.0:
        return signal

    b, a = butter(order, [low_norm, high_norm], btype="band")

    if signal.ndim == 1:
        return filtfilt(b, a, signal)
    else:
        return np.apply_along_axis(lambda x: filtfilt(b, a, x), 0, signal)


def interpolate_gnss_to_imu(imu_timestamps: np.ndarray, gnss_timestamps: np.ndarray,
                              gnss_values: np.ndarray) -> np.ndarray:
    """Linearly interpolate GNSS data to match IMU timestamps.

    Args:
        imu_timestamps: (M,) IMU timestamps in ms
        gnss_timestamps: (N,) GNSS timestamps in ms
        gnss_values: (N,) or (N, D) GNSS values to interpolate

    Returns:
        (M,) or (M, D) interpolated values
    """
    if gnss_values.ndim == 1:
        return np.interp(imu_timestamps, gnss_timestamps, gnss_values)
    else:
        result = np.zeros((len(imu_timestamps), gnss_values.shape[1]))
        for d in range(gnss_values.shape[1]):
            result[:, d] = np.interp(imu_timestamps, gnss_timestamps, gnss_values[:, d])
        return result


def resample_imu(timestamps: np.ndarray, values: np.ndarray, target_hz: float) -> tuple:
    """Resample IMU data to a fixed rate.

    Args:
        timestamps: (N,) timestamps in ms
        values: (N, D) sensor values
        target_hz: target sampling rate in Hz

    Returns:
        (new_timestamps, new_values) resampled arrays
    """
    duration_sec = (timestamps[-1] - timestamps[0]) / 1000.0
    target_dt = 1.0 / target_hz
    n_samples = int(duration_sec * target_dt * 1000 / target_dt) + 1

    new_timestamps = np.linspace(timestamps[0], timestamps[-1], n_samples)
    new_values = np.zeros((n_samples, values.shape[1]))

    for d in range(values.shape[1]):
        new_values[:, d] = np.interp(new_timestamps, timestamps, values[:, d])

    return new_timestamps, new_values


def compute_jerk(accel: np.ndarray, dt: np.ndarray) -> np.ndarray:
    """Compute jerk (derivative of acceleration).

    Args:
        accel: (N, 3) acceleration
        dt: (N-1,) time deltas

    Returns:
        (N-1, 3) jerk
    """
    jerk = np.diff(accel, axis=0) / dt[:, np.newaxis]
    return jerk


def compute_angular_velocity_magnitude(gyro: np.ndarray) -> np.ndarray:
    """Compute angular velocity magnitude."""
    return np.linalg.norm(gyro, axis=1)


def compute_acceleration_magnitude(accel: np.ndarray) -> np.ndarray:
    """Compute acceleration magnitude."""
    return np.linalg.norm(accel, axis=1)


def compute_rolling_stats(values: np.ndarray, window: int) -> tuple:
    """Compute rolling mean and std.

    Args:
        values: (N,) or (N, D) values
        window: rolling window size

    Returns:
        (rolling_mean, rolling_std) arrays
    """
    if values.ndim == 1:
        mean = np.convolve(values, np.ones(window)/window, mode="same")
        std = np.array([np.std(values[max(0,i-window):i+1]) for i in range(len(values))])
        return mean, std
    else:
        mean = np.apply_along_axis(lambda x: np.convolve(x, np.ones(window)/window, mode="same"), 0, values)
        std = np.zeros_like(values)
        for d in range(values.shape[1]):
            std[:, d] = np.array([np.std(values[max(0,i-window):i+1, d]) for i in range(len(values))])
        return mean, std
