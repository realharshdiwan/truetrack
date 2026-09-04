# TrueTrack v0.1

IMU + GNSS sensor fusion for positioning during GNSS outages.

## Project Structure

```
TrueTrack/
├── android/                    # Android sensor logger app
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       └── java/com/truetrack/app/
│   │           ├── MainActivity.kt
│   │           ├── SensorCollector.kt
│   │           ├── GnssCollector.kt
│   │           └── SensorLoggerService.kt
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── pipeline/                   # Python analysis pipeline
│   ├── truetrack/
│   │   ├── __init__.py
│   │   ├── config.py
│   │   ├── data_loader.py
│   │   ├── preprocessing.py
│   │   ├── dead_reckoning.py
│   │   ├── outage_simulator.py
│   │   ├── evaluation.py
│   │   └── visualization.py
│   ├── tests/
│   ├── analyze.py
│   ├── requirements.txt
│   └── setup.py
├── data/recordings/            # Sensor recordings
└── docs/
```

## Development Ladder

- **v0**: Raw sensor logger ← you are here
- **v0.1**: Raw IMU dead reckoning
- **v0.2**: Orientation calibration + coordinate transformation
- **v0.3**: Classical IMU + GNSS fusion
- **v0.4**: Non-holonomic constraints
- **v0.5**: Offline road/map matching
- **v0.6**: Dataset + neural IMU error model
- **v0.7**: CNN/GRU + UKF
- **v1**: Real-time Android prototype
- **v1.1**: Real-world outage demonstrations

## Android App

### Building

```bash
cd android
./gradlew assembleDebug
```

### Installation

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Permissions Required

- `ACCESS_FINE_LOCATION` — GNSS data
- `ACCESS_COARSE_LOCATION` — location
- `HIGH_SAMPLING_RATE_SENSORS` — fast IMU access
- `FOREGROUND_SERVICE` — background recording
- `POST_NOTIFICATIONS` — notification channel

### Recording Protocol

1. Mount phone in vehicle (rigid, known position)
2. Press RECORD
3. Drive planned route with varied conditions:
   - Straight roads
   - Turns at various speeds
   - Stops and starts
   - Speed changes
4. Record 5-10 minutes
5. Press STOP

### Output Files

- `imu_YYYYMMDD_HHmmss.csv` — IMU data (100 Hz)
- `gnss_YYYYMMDD_HHmmss.csv` — GNSS data + raw measurements
- `meta_YYYYMMDD_HHmmss.txt` — recording metadata

## Python Pipeline

### Setup

```bash
cd pipeline
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### Running Analysis

```bash
python analyze.py ../data/recordings/recording_20240101_120000/
```

Options:
- `--output <dir>` — custom output directory
- `--rate <hz>` — IMU sample rate (default: 100)
- `--skip-viz` — skip visualization generation

### Running Tests

```bash
cd pipeline
python -m pytest tests/ -v
```

## Pipeline Architecture

```
Raw IMU (100 Hz)
    │
    ▼
Preprocessing
├── Remove gravity (rotation vector)
├── Lowpass filter (20 Hz cutoff)
└── Compute features (jerk, magnitude, RMS)
    │
    ▼
Dead Reckoning
├── Magnetometer heading initialization
├── Gyroscope heading integration
├── Double integration (accel → velocity → position)
└── NED coordinate conversion
    │
    ▼
Evaluation
├── Position RMSE
├── Final drift
├── Drift per km
├── Velocity error
└── Outage-specific metrics
```

## Evaluation Metrics

| Metric | Meaning |
|--------|---------|
| Position RMSE | Overall positional error |
| Final Drift | Error at end of GNSS outage |
| Drift/km | Normalized outage drift |
| Heading Error | Orientation accuracy |
| Velocity Error | Speed estimation |
| Recovery Time | Time to stabilize after GNSS returns |
| Max Drift | Peak error during outage |
| Drift Rate | Error growth rate (m/s) |

## Outage Test Scenarios

Standard test durations:
- 5 sec
- 10 sec
- 30 sec
- 60 sec
- 120 sec
- 300 sec

Plus multi-outage and long-outage scenarios.

## Next Steps

1. **Build and install** the Android app
2. **Record** a 5-10 minute drive
3. **Run** the analysis pipeline
4. **Compare** raw GNSS, raw DR, and TrueTrack
5. **Identify** failure modes from real data
6. **Iterate** on calibration and fusion

## Project Rule

**Measure → Baseline → Identify Failure → Fix → Measure Again**

Not: buzzword → neural network → powerpoint → pray.
