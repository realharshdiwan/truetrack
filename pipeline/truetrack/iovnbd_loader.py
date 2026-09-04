"""Loader for IO-VNBD dataset (Inertial and Odometry Benchmark Dataset).

Reference: Onyekpe et al., "IO-VNBD: Inertial and Odometry benchmark dataset
for ground vehicle positioning", Data in Brief 35, 2021.

Two data streams at 10Hz:
- V-Dataset: Vehicle ECU via CAN bus (Racelogic VBOX Video HD2)
- S-Dataset: Smartphone sensors via AndroSensor (Huawei P20 Pro, etc.)

Download from: https://github.com/onyekpeu/IO-VNBD
Note: Dataset uses Git LFS. Clone with: git lfs pull
"""

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd

from .data_loader import ImuData, GnssData, RecordingData


def _parse_satellites(val) -> float:
    """Parse satellite count from format like '27 / 28' or just '27'."""
    if isinstance(val, str):
        match = re.search(r"(\d+)", val)
        return float(match.group(1)) if match else 0.0
    return float(val) if not np.isnan(val) else 0.0


@dataclass
class VehicleData:
    """Vehicle ECU data from V-Dataset."""
    timestamp_sec: np.ndarray
    gps_lat: np.ndarray
    gps_lon: np.ndarray
    gps_velocity_kmh: np.ndarray
    gps_heading_deg: np.ndarray
    gps_height_km: np.ndarray
    gps_satellites: np.ndarray
    wheel_speed_fl: np.ndarray
    wheel_speed_fr: np.ndarray
    wheel_speed_rl: np.ndarray
    wheel_speed_rr: np.ndarray
    yaw_rate_dps: np.ndarray
    vehicle_speed_kmh: np.ndarray
    longitudinal_accel_g: np.ndarray
    lateral_accel_g: np.ndarray
    steering_angle_deg: np.ndarray
    brake_pressure_psi: np.ndarray
    sample_period_sec: np.ndarray

    @property
    def sample_count(self) -> int:
        return len(self.timestamp_sec)

    @property
    def duration_sec(self) -> float:
        return self.timestamp_sec[-1] - self.timestamp_sec[0]

    @property
    def avg_rate_hz(self) -> float:
        return self.sample_count / self.duration_sec if self.duration_sec > 0 else 0.0

    def to_imu_data(self) -> ImuData:
        """Convert vehicle data to TrueTrack ImuData format.

        Uses vehicle accelerations and yaw rate as IMU proxy.
        """
        n = self.sample_count
        timestamps = self.timestamp_sec * 1000  # convert to ms

        accel = np.zeros((n, 3))
        accel[:, 0] = self.longitudinal_accel_g * 9.80665
        accel[:, 1] = self.lateral_accel_g * 9.80665
        accel[:, 2] = 0.0

        gyro = np.zeros((n, 3))
        gyro[:, 2] = np.radians(self.yaw_rate_dps)

        mag = np.zeros((n, 3))

        return ImuData(
            timestamp_ms=timestamps,
            accel=accel,
            gyro=gyro,
            mag=mag,
            rotation=None,
        )

    def to_gnss_data(self) -> GnssData:
        """Convert vehicle GPS to TrueTrack GnssData format."""
        speed_ms = self.gps_velocity_kmh / 3.6

        return GnssData(
            timestamp_ms=self.timestamp_sec * 1000,
            latitude=self.gps_lat,
            longitude=self.gps_lon,
            altitude=self.gps_height_km * 1000,
            speed=speed_ms,
            bearing=self.gps_heading_deg,
            h_accuracy=np.full(self.sample_count, 3.0),
            v_accuracy=np.full(self.sample_count, 5.0),
            satellites_used=self.gps_satellites.astype(int),
            satellites_total=np.full(self.sample_count, 12),
            gnss_available=self.gps_satellites > 0,
        )


@dataclass
class SmartphoneData:
    """Smartphone sensor data from S-Dataset."""
    timestamp_ms: np.ndarray
    gps_lat: np.ndarray
    gps_lon: np.ndarray
    gps_altitude_m: np.ndarray
    gps_speed_kmh: np.ndarray
    gps_accuracy_m: np.ndarray
    gps_orientation_deg: np.ndarray
    gps_satellites: np.ndarray
    accel: np.ndarray       # (N, 3) m/s²
    gravity: np.ndarray     # (N, 3) m/s²
    gyro: np.ndarray        # (N, 3) rad/s
    mag: np.ndarray         # (N, 3) µT
    orientation: np.ndarray # (N, 3) degrees

    @property
    def sample_count(self) -> int:
        return len(self.timestamp_ms)

    @property
    def duration_sec(self) -> float:
        return (self.timestamp_ms[-1] - self.timestamp_ms[0]) / 1000.0

    @property
    def avg_rate_hz(self) -> float:
        return self.sample_count / self.duration_sec if self.duration_sec > 0 else 0.0

    def to_imu_data(self) -> ImuData:
        """Convert smartphone data to TrueTrack ImuData format."""
        linear_accel = self.accel - self.gravity

        return ImuData(
            timestamp_ms=self.timestamp_ms,
            accel=linear_accel,
            gyro=self.gyro,
            mag=self.mag,
            rotation=None,
        )

    def to_gnss_data(self) -> GnssData:
        """Convert smartphone GPS to TrueTrack GnssData format."""
        speed_ms = self.gps_speed_kmh / 3.6

        return GnssData(
            timestamp_ms=self.timestamp_ms,
            latitude=self.gps_lat,
            longitude=self.gps_lon,
            altitude=self.gps_altitude_m,
            speed=speed_ms,
            bearing=self.gps_orientation_deg,
            h_accuracy=self.gps_accuracy_m,
            v_accuracy=np.full(self.sample_count, 10.0),
            satellites_used=self.gps_satellites.astype(int),
            satellites_total=np.full(self.sample_count, 12),
            gnss_available=self.gps_accuracy_m > 0,
        )


def load_v_dataset(filepath: Path) -> VehicleData:
    """Load a V-Dataset CSV file.

    Actual format (with header row):
    No of GPS Satellites Available, Time Since Start of Day (seconds), Latitude (degrees), ...
    """
    df = pd.read_csv(filepath, encoding="latin-1")

    # Normalize column names: strip whitespace, lowercase
    def normalize_col(c):
        c = c.strip().lower()
        c = c.replace(" ", "_")
        c = c.replace("(", "").replace(")", "")
        c = c.replace("/", "_")
        c = c.replace("-", "_")
        c = c.replace("°", "deg")
        return c

    df.columns = [normalize_col(c) for c in df.columns]

    # Map normalized column names to our fields
    col_map = {
        "no_of_gps_satellites_available": "gps_satellites",
        "time_since_start_of_day_seconds": "timestamp_sec",
        "latitude_degrees": "gps_lat",
        "longitude_degrees": "gps_lon",
        "velocity_km_hr": "gps_velocity_kmh",
        "heading_degrees": "gps_heading_deg",
        "height_km": "gps_height_km",
        "vertical_velocity_km_hr": "gps_vertical_velocity_kmh",
        "sample_period_seconds": "sample_period_sec",
        "steering_angle_degrees": "steering_angle_deg",
        "wheel_speed_front_left_rad_sec": "wheel_speed_fl",
        "wheel_speed_front_right_rad_sec": "wheel_speed_fr",
        "wheel_speed_rear_left_rad_sec": "wheel_speed_rl",
        "wheel_speed_rear_right_rad_sec": "wheel_speed_rr",
        "yaw_rate_deg_sec": "yaw_rate_dps",
        "indicated_vehicle_speed_km_hr": "vehicle_speed_kmh",
        "indicated_longitudinal_acceleration_g": "longitudinal_accel_g",
        "indicated_lateral_acceleration_g": "lateral_accel_g",
        "brake_pressure_psi": "brake_pressure_psi",
    }

    # Rename columns that exist
    rename = {}
    for orig, target in col_map.items():
        if orig in df.columns:
            rename[orig] = target
    df = df.rename(columns=rename)

    # Extract arrays with defaults for missing columns
    def get_col(name, default=0.0):
        if name in df.columns:
            return pd.to_numeric(df[name], errors="coerce").to_numpy(dtype=np.float64)
        return np.full(len(df), default, dtype=np.float64)

    return VehicleData(
        timestamp_sec=get_col("timestamp_sec"),
        gps_lat=get_col("gps_lat"),
        gps_lon=get_col("gps_lon"),
        gps_velocity_kmh=get_col("gps_velocity_kmh"),
        gps_heading_deg=get_col("gps_heading_deg"),
        gps_height_km=get_col("gps_height_km"),
        gps_satellites=get_col("gps_satellites"),
        wheel_speed_fl=get_col("wheel_speed_fl"),
        wheel_speed_fr=get_col("wheel_speed_fr"),
        wheel_speed_rl=get_col("wheel_speed_rl"),
        wheel_speed_rr=get_col("wheel_speed_rr"),
        yaw_rate_dps=get_col("yaw_rate_dps"),
        vehicle_speed_kmh=get_col("vehicle_speed_kmh"),
        longitudinal_accel_g=get_col("longitudinal_accel_g"),
        lateral_accel_g=get_col("lateral_accel_g"),
        steering_angle_deg=get_col("steering_angle_deg"),
        brake_pressure_psi=get_col("brake_pressure_psi"),
        sample_period_sec=get_col("sample_period_sec"),
    )


def load_s_dataset(filepath: Path) -> SmartphoneData:
    """Load an S-Dataset CSV file.

    Actual format (with header row):
    GPS LATITUDE (degrees), GPS LONGITUDE (degrees), ...
    """
    df = pd.read_csv(filepath, encoding="latin-1")

    # Normalize column names: strip whitespace, lowercase, fix encoding
    def normalize_col(c):
        c = c.strip().lower()
        # Fix encoding artifacts
        c = c.replace("â°", "deg").replace("Â°", "deg").replace("°", "deg")
        c = c.replace("î¼t", "ut").replace("Î¼T", "ut").replace("μt", "ut")
        c = c.replace("µt", "ut")
        c = c.replace("m/s²", "ms2").replace("m/s\xb2", "ms2")
        c = c.replace("m/s\u00b2", "ms2")
        c = c.replace(" ", "_")
        c = c.replace("(", "").replace(")", "")
        c = c.replace("/", "_")
        c = c.replace("-", "_")
        return c

    df.columns = [normalize_col(c) for c in df.columns]

    # Map normalized column names
    col_map = {
        "gps_latitude_degrees": "gps_lat",
        "gps_longitude_degrees": "gps_lon",
        "gps_altitude_m": "gps_altitude_m",
        "gps_speed_kmh": "gps_speed_kmh",
        "gps_accuracy_m": "gps_accuracy_m",
        "gps_orientation_deg": "gps_orientation_deg",
        "gps_satellites_in_range": "gps_satellites_raw",
        "time_since_start_ms": "timestamp_ms",
        "accelerometer_x_ms2": "accel_x",
        "accelerometer_y_ms2": "accel_y",
        "accelerometer_z_ms2": "accel_z",
        "gravity_x_ms2": "gravity_x",
        "gravity_y_ms2": "gravity_y",
        "gravity_z_ms2": "gravity_z",
        "gyroscope_x_rad_s": "gyro_x",
        "gyroscope_y_rad_s": "gyro_y",
        "gyroscope_z_rad_s": "gyro_z",
        "magnetic_field_x_ut": "mag_x",
        "magnetic_field_y_ut": "mag_y",
        "magnetic_field_z_ut": "mag_z",
        "orientation_azimuth_deg": "orient_yaw",
        "orientation_pitch_deg": "orient_pitch",
        "orientation_roll_deg": "orient_roll",
    }

    rename = {}
    for orig, target in col_map.items():
        if orig in df.columns:
            rename[orig] = target
    df = df.rename(columns=rename)

    def get_col(name, default=0.0):
        if name in df.columns:
            return pd.to_numeric(df[name], errors="coerce").to_numpy(dtype=np.float64)
        return np.full(len(df), default, dtype=np.float64)

    # Parse satellite count (format: "27 / 28")
    if "gps_satellites_raw" in df.columns:
        gps_sat = df["gps_satellites_raw"].apply(_parse_satellites).to_numpy(dtype=np.float64)
    else:
        gps_sat = np.full(len(df), 0.0)

    timestamp_ms = get_col("timestamp_ms")

    accel = np.column_stack([
        get_col("accel_x"),
        get_col("accel_y"),
        get_col("accel_z"),
    ])

    gravity = np.column_stack([
        get_col("gravity_x"),
        get_col("gravity_y"),
        get_col("gravity_z"),
    ])

    gyro = np.column_stack([
        get_col("gyro_x"),
        get_col("gyro_y"),
        get_col("gyro_z"),
    ])

    mag = np.column_stack([
        get_col("mag_x"),
        get_col("mag_y"),
        get_col("mag_z"),
    ])

    orientation = np.column_stack([
        get_col("orient_yaw"),
        get_col("orient_pitch"),
        get_col("orient_roll"),
    ])

    return SmartphoneData(
        timestamp_ms=timestamp_ms,
        gps_lat=get_col("gps_lat"),
        gps_lon=get_col("gps_lon"),
        gps_altitude_m=get_col("gps_altitude_m"),
        gps_speed_kmh=get_col("gps_speed_kmh"),
        gps_accuracy_m=get_col("gps_accuracy_m"),
        gps_orientation_deg=get_col("gps_orientation_deg"),
        gps_satellites=gps_sat,
        accel=accel,
        gravity=gravity,
        gyro=gyro,
        mag=mag,
        orientation=orientation,
    )


def load_iovnbd_recording(v_path: Optional[Path] = None,
                           s_path: Optional[Path] = None) -> RecordingData:
    """Load IO-VNBD recording and convert to TrueTrack format.

    Args:
        v_path: Path to V-Dataset CSV (vehicle ECU data)
        s_path: Path to S-Dataset CSV (smartphone data)

    Returns:
        RecordingData in TrueTrack format
    """
    if v_path is None and s_path is None:
        raise ValueError("At least one of v_path or s_path must be provided")

    vehicle = None
    smartphone = None

    # Load available data
    if v_path is not None:
        vehicle = load_v_dataset(v_path)
    if s_path is not None:
        smartphone = load_s_dataset(s_path)

    # Use smartphone for both IMU and GNSS when available
    # (they share the same timestamp base from AndroSensor)
    # Vehicle data is on a different time base (time-of-day from ECU)
    if smartphone is not None:
        imu = smartphone.to_imu_data()
        gnss = smartphone.to_gnss_data()
    elif vehicle is not None:
        imu = vehicle.to_imu_data()
        gnss = vehicle.to_gnss_data()
    else:
        raise ValueError("No data loaded")

    metadata = {
        "source": "IO-VNBD",
        "v_path": str(v_path) if v_path else None,
        "s_path": str(s_path) if s_path else None,
        "vehicle_sample_count": vehicle.sample_count if vehicle else 0,
        "smartphone_sample_count": smartphone.sample_count if smartphone else 0,
    }

    return RecordingData(
        imu=imu,
        gnss=gnss,
        metadata=metadata,
        path=v_path or s_path,
    )


def list_iovnbd_recordings(data_dir: Path) -> list:
    """List available IO-VNBD recordings in a directory."""
    v_files = sorted(data_dir.glob("V-*.csv"))
    s_files = sorted(data_dir.glob("S-*.csv"))

    recordings = []
    v_basenames = {f.stem[2:]: f for f in v_files}
    s_basenames = {f.stem[2:]: f for f in s_files}

    all_basenames = set(v_basenames.keys()) | set(s_basenames.keys())

    for name in sorted(all_basenames):
        recordings.append({
            "name": name,
            "v_path": v_basenames.get(name),
            "s_path": s_basenames.get(name),
        })

    return recordings
