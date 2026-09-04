"""Tests for data loader."""

import tempfile
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from truetrack.data_loader import load_imu_csv, load_gnss_csv, ImuData, GnssData


class TestImuLoader:
    def test_load_imu_csv(self, tmp_path):
        """Test loading a basic IMU CSV."""
        csv_content = """timestamp_ms,ax,ay,az,gx,gy,gz,mx,my,mz,rw0,rw1,rw2,rw3,rw4
1000,0.1,0.2,9.8,0.01,0.02,0.03,10,20,30,0.1,0.2,0.3,0.9,0
1010,0.15,0.25,9.7,0.02,0.03,0.04,11,21,31,0.1,0.2,0.3,0.9,0
1020,0.2,0.3,9.6,0.03,0.04,0.05,12,22,32,0.1,0.2,0.3,0.9,0"""
        filepath = tmp_path / "imu_test.csv"
        filepath.write_text(csv_content)

        data = load_imu_csv(filepath)

        assert isinstance(data, ImuData)
        assert data.sample_count == 3
        assert data.accel.shape == (3, 3)
        assert data.gyro.shape == (3, 3)
        assert data.mag.shape == (3, 3)
        assert data.rotation is not None
        assert data.rotation.shape == (3, 5)

    def test_imu_dt(self, tmp_path):
        """Test time delta computation."""
        csv_content = """timestamp_ms,ax,ay,az,gx,gy,gz,mx,my,mz
1000,0,0,9.8,0,0,0,0,0,0
1010,0,0,9.8,0,0,0,0,0,0
1030,0,0,9.8,0,0,0,0,0,0"""
        filepath = tmp_path / "imu_test.csv"
        filepath.write_text(csv_content)

        data = load_imu_csv(filepath)

        assert len(data.dt) == 2
        assert data.dt[0] == pytest.approx(0.01)
        assert data.dt[1] == pytest.approx(0.02)


class TestGnssLoader:
    def test_load_gnss_csv(self, tmp_path):
        """Test loading a basic GNSS CSV."""
        csv_content = """timestamp_ms,latitude,longitude,altitude,speed,bearing,h_accuracy,v_accuracy,speed_accuracy,bearing_accuracy,sat_used,sat_total,gnss_available
1000,37.7749,-122.4194,10.0,5.0,45.0,3.0,5.0,0.5,2.0,8,12,True
1010,37.7750,-122.4195,10.1,5.1,46.0,3.1,5.1,0.5,2.1,9,13,True
1020,37.7751,-122.4196,10.2,5.2,47.0,3.2,5.2,0.5,2.2,8,12,True"""
        filepath = tmp_path / "gnss_test.csv"
        filepath.write_text(csv_content)

        data = load_gnss_csv(filepath)

        assert isinstance(data, GnssData)
        assert data.sample_count == 3
        assert data.latitude[0] == pytest.approx(37.7749)
        assert data.longitude[0] == pytest.approx(-122.4194)

    def test_empty_gnss_raises(self, tmp_path):
        """Test that empty GNSS data raises error."""
        csv_content = """timestamp_ms,latitude,longitude,altitude,speed,bearing,h_accuracy,v_accuracy,speed_accuracy,bearing_accuracy,sat_used,sat_total,gnss_available"""
        filepath = tmp_path / "gnss_empty.csv"
        filepath.write_text(csv_content)

        with pytest.raises(ValueError):
            load_gnss_csv(filepath)
