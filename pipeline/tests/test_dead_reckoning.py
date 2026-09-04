"""Tests for dead reckoning."""

import numpy as np
import pytest

from truetrack.dead_reckoning import (
    mag_heading, gyro_heading_integration,
    ned_from_latlon, latlon_from_ned
)


class TestHeadingComputation:
    def test_mag_heading_north(self):
        """Test heading computation pointing north."""
        mag = np.array([0.0, 1.0, 0.0])
        heading = mag_heading(mag)
        assert heading == pytest.approx(np.pi / 2)

    def test_mag_heading_east(self):
        """Test heading computation pointing east."""
        mag = np.array([1.0, 0.0, 0.0])
        heading = mag_heading(mag)
        assert heading == pytest.approx(0.0)

    def test_gyro_integration_constant(self):
        """Test gyro integration with constant rotation."""
        gyro_z = np.full(100, 0.1)  # 0.1 rad/s constant
        dt = np.full(99, 0.01)  # 10ms intervals
        headings = gyro_heading_integration(gyro_z, dt)

        assert len(headings) == 100
        # After 1 second: 0.1 * 1.0 = 0.1 rad
        assert headings[-1] == pytest.approx(0.1, abs=0.01)


class TestCoordinateConversion:
    def test_ned_roundtrip(self):
        """Test NED to lat/lon roundtrip."""
        ref_lat, ref_lon = 37.7749, -122.4194
        ned = np.array([[100.0, 200.0], [300.0, 400.0]])

        lat, lon = latlon_from_ned(ned, ref_lat, ref_lon)
        ned_back = ned_from_latlon(lat, lon, ref_lat, ref_lon)

        assert np.allclose(ned, ned_back, atol=0.1)

    def test_ned_small_displacement(self):
        """Test NED for small displacements."""
        ref_lat, ref_lon = 37.7749, -122.4194
        ned = np.array([[100.0, 0.0]])  # 100m north

        lat, lon = latlon_from_ned(ned, ref_lat, ref_lon)

        # 100m north should change latitude by ~0.0009 degrees
        assert lat[0] > ref_lat
        assert abs(lat[0] - ref_lat) == pytest.approx(0.0009, abs=0.0001)
        assert abs(lon[0] - ref_lon) < 1e-6
