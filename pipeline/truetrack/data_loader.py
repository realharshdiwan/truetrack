"""Load and validate TrueTrack sensor recordings."""

from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd


@dataclass
class ImuData:
    """IMU sensor data."""
    timestamp_ms: np.ndarray  # (N,)
    accel: np.ndarray         # (N, 3) ax, ay, az
    gyro: np.ndarray          # (N, 3) gx, gy, gz
    mag: np.ndarray           # (N, 3) mx, my, mz
    rotation: Optional[np.ndarray] = None  # (N, 5) rotation vector

    @property
    def dt(self) -> np.ndarray:
        """Time deltas in seconds between consecutive samples."""
        return np.diff(self.timestamp_ms) / 1000.0

    @property
    def duration_sec(self) -> float:
        return (self.timestamp_ms[-1] - self.timestamp_ms[0]) / 1000.0

    @property
    def sample_count(self) -> int:
        return len(self.timestamp_ms)

    @property
    def avg_rate_hz(self) -> float:
        return self.sample_count / self.duration_sec if self.duration_sec > 0 else 0.0


@dataclass
class GnssData:
    """GNSS location data."""
    timestamp_ms: np.ndarray  # (N,)
    latitude: np.ndarray      # (N,)
    longitude: np.ndarray     # (N,)
    altitude: np.ndarray      # (N,)
    speed: np.ndarray         # (N,)
    bearing: np.ndarray       # (N,)
    h_accuracy: np.ndarray    # (N,)
    v_accuracy: np.ndarray    # (N,)
    satellites_used: np.ndarray  # (N,)
    satellites_total: np.ndarray  # (N,)
    gnss_available: np.ndarray    # (N,)

    @property
    def dt(self) -> np.ndarray:
        return np.diff(self.timestamp_ms) / 1000.0

    @property
    def duration_sec(self) -> float:
        return (self.timestamp_ms[-1] - self.timestamp_ms[0]) / 1000.0

    @property
    def sample_count(self) -> int:
        return len(self.timestamp_ms)

    @property
    def avg_rate_hz(self) -> float:
        return self.sample_count / self.duration_sec if self.duration_sec > 0 else 0.0

    @property
    def mean_accuracy(self) -> float:
        valid = self.h_accuracy[self.h_accuracy > 0]
        return float(np.mean(valid)) if len(valid) > 0 else -1.0


@dataclass
class RecordingData:
    """Complete recording session."""
    imu: ImuData
    gnss: GnssData
    metadata: dict = field(default_factory=dict)
    path: Path = field(default_factory=Path)


def load_imu_csv(filepath: Path) -> ImuData:
    """Load IMU CSV from TrueTrack Android logger.

    Expected columns:
    timestamp_ms,ax,ay,az,gx,gy,gz,mx,my,mz,rw0,rw1,rw2,rw3,rw4
    """
    df = pd.read_csv(filepath)

    rotation = None
    if "rw0" in df.columns and df["rw0"].notna().any():
        rotation_cols = [c for c in df.columns if c.startswith("rw")]
        rotation = df[rotation_cols].to_numpy(dtype=np.float64)

    return ImuData(
        timestamp_ms=df["timestamp_ms"].to_numpy(dtype=np.float64),
        accel=df[["ax", "ay", "az"]].to_numpy(dtype=np.float64),
        gyro=df[["gx", "gy", "gz"]].to_numpy(dtype=np.float64),
        mag=df[["mx", "my", "mz"]].to_numpy(dtype=np.float64),
        rotation=rotation,
    )


def load_gnss_csv(filepath: Path) -> GnssData:
    """Load GNSS CSV from TrueTrack Android logger."""
    df = pd.read_csv(filepath)

    # Take only location-level rows (first row per timestamp with location data)
    loc_mask = df["latitude"].notna()
    loc_df = df[loc_mask].copy()

    if len(loc_df) == 0:
        raise ValueError(f"No valid GNSS locations in {filepath}")

    return GnssData(
        timestamp_ms=loc_df["timestamp_ms"].to_numpy(dtype=np.float64),
        latitude=loc_df["latitude"].to_numpy(dtype=np.float64),
        longitude=loc_df["longitude"].to_numpy(dtype=np.float64),
        altitude=loc_df["altitude"].to_numpy(dtype=np.float64),
        speed=loc_df["speed"].to_numpy(dtype=np.float64),
        bearing=loc_df["bearing"].to_numpy(dtype=np.float64),
        h_accuracy=loc_df["h_accuracy"].to_numpy(dtype=np.float64),
        v_accuracy=loc_df["v_accuracy"].to_numpy(dtype=np.float64),
        satellites_used=loc_df["sat_used"].to_numpy(dtype=np.float64),
        satellites_total=loc_df["sat_total"].to_numpy(dtype=np.float64),
        gnss_available=loc_df["gnss_available"].to_numpy(dtype=bool),
    )


def load_metadata(filepath: Path) -> dict:
    """Load metadata text file."""
    meta = {}
    with open(filepath) as f:
        for line in f:
            line = line.strip()
            if "=" in line:
                key, value = line.split("=", 1)
                try:
                    meta[key] = int(value)
                except ValueError:
                    try:
                        meta[key] = float(value)
                    except ValueError:
                        meta[key] = value
    return meta


def load_recording(directory: Path) -> RecordingData:
    """Load a complete recording session from a directory.

    Expects files matching:
    - imu_*.csv
    - gnss_*.csv
    - meta_*.txt
    """
    imu_files = sorted(directory.glob("imu_*.csv"))
    gnss_files = sorted(directory.glob("gnss_*.csv"))
    meta_files = sorted(directory.glob("meta_*.txt"))

    if not imu_files:
        raise FileNotFoundError(f"No IMU files found in {directory}")
    if not gnss_files:
        raise FileNotFoundError(f"No GNSS files found in {directory}")

    # Use the most recent recording
    imu_data = load_imu_csv(imu_files[-1])
    gnss_data = load_gnss_csv(gnss_files[-1])

    metadata = {}
    if meta_files:
        metadata = load_metadata(meta_files[-1])

    return RecordingData(
        imu=imu_data,
        gnss=gnss_data,
        metadata=metadata,
        path=directory,
    )
