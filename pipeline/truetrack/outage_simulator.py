"""Simulated GNSS outage injection for testing."""

from dataclasses import dataclass
from typing import List

import numpy as np

from .data_loader import GnssData
from .config import OUTAGE_DURATIONS_SEC


@dataclass
class OutageConfig:
    """Configuration for a single outage."""
    start_sec: float
    duration_sec: float
    severity: str = "complete"  # "complete" or "degraded"


@dataclass
class OutageScenario:
    """A test scenario with one or more outages."""
    name: str
    outages: List[OutageConfig]
    description: str = ""


def create_outage_mask(gnss_timestamps: np.ndarray, outages: List[OutageConfig]) -> np.ndarray:
    """Create a boolean mask indicating GNSS availability.

    Args:
        gnss_timestamps: (N,) GNSS timestamps in milliseconds
        outages: list of outage configurations

    Returns:
        (N,) boolean array, True where GNSS is available
    """
    t0 = gnss_timestamps[0]
    duration_ms = (gnss_timestamps[-1] - t0)

    mask = np.ones(len(gnss_timestamps), dtype=bool)

    for outage in outages:
        start_ms = t0 + outage.start_sec * 1000
        end_ms = start_ms + outage.duration_sec * 1000

        if outage.severity == "complete":
            mask[(gnss_timestamps >= start_ms) & (gnss_timestamps <= end_ms)] = False
        elif outage.severity == "degraded":
            # Could add accuracy degradation here
            mask[(gnss_timestamps >= start_ms) & (gnss_timestamps <= end_ms)] = False

    return mask


def generate_random_outages(duration_sec: float, n_outages: int = 3,
                             min_duration: float = 5.0, max_duration: float = 120.0,
                             min_gap: float = 10.0, seed: int = 42) -> List[OutageConfig]:
    """Generate random outage positions for testing.

    Args:
        duration_sec: total recording duration in seconds
        n_outages: number of outages to generate
        min_duration: minimum outage duration in seconds
        max_duration: maximum outage duration in seconds
        min_gap: minimum gap between outages
        seed: random seed

    Returns:
        List of outage configurations
    """
    rng = np.random.default_rng(seed)

    outages = []
    current_time = 5.0  # Start after 5 seconds

    for _ in range(n_outages):
        if current_time + max_duration > duration_sec - 10:
            break

        outage_duration = rng.uniform(min_duration, max_duration)
        outages.append(OutageConfig(
            start_sec=current_time,
            duration_sec=outage_duration,
            severity="complete"
        ))
        current_time += outage_duration + rng.uniform(min_gap, min_gap * 3)

    return outages


def generate_standard_scenarios(duration_sec: float) -> List[OutageScenario]:
    """Generate standard test scenarios from the plan.

    These cover the evaluation requirements:
    - 5 sec, 10 sec, 30 sec, 60 sec, 120 sec, 300 sec outages
    - Various positions in the recording
    """
    scenarios = []

    for target_duration in OUTAGE_DURATIONS_SEC:
        if target_duration > duration_sec - 20:
            continue

        # Place outage in the middle of the recording
        start = (duration_sec - target_duration) / 2

        scenarios.append(OutageScenario(
            name=f"outage_{target_duration}s",
            outages=[OutageConfig(start_sec=start, duration_sec=target_duration)],
            description=f"Single {target_duration}s GNSS outage in middle of recording"
        ))

    # Add a scenario with multiple outages
    mid = duration_sec / 2
    scenarios.append(OutageScenario(
        name="multi_outage",
        outages=[
            OutageConfig(start_sec=mid - 60, duration_sec=15),
            OutageConfig(start_sec=mid, duration_sec=30),
            OutageConfig(start_sec=mid + 60, duration_sec=10),
        ],
        description="Three outages: 15s, 30s, 10s with gaps"
    ))

    # Add a degradation scenario (long outage)
    if duration_sec > 350:
        scenarios.append(OutageScenario(
            name="long_outage",
            outages=[OutageConfig(start_sec=60, duration_sec=300)],
            description="5-minute continuous outage (tunnel simulation)"
        ))

    return scenarios
