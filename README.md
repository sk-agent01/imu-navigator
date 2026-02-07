# IMU Navigator

Android navigation app that uses ONLY accelerometer + gyroscope for position tracking (no GPS).

[![Build APK](https://github.com/sk-agent01/imu-navigator/actions/workflows/build.yml/badge.svg)](https://github.com/sk-agent01/imu-navigator/actions/workflows/build.yml)

## Features

- **OpenStreetMap** map display using osmdroid
- **Route calculation** via OSRM (Open Source Routing Machine) API
- **IMU-based dead reckoning** for position estimation during navigation
- **Map matching** to project estimated position onto the route
- **Zero Velocity Updates (ZVU)** for drift correction when stopped
- Works completely **without GPS**

## How It Works

### Dead Reckoning Algorithm

The app uses smartphone IMU sensors (accelerometer + gyroscope) to estimate movement:

1. **Accelerometer** - Detects acceleration in device frame, transformed to world frame
2. **Gyroscope** - Tracks device orientation/heading changes
3. **Sensor Fusion** - Combines both to estimate velocity and heading
4. **Route Constraint** - Projects estimated position onto the route polyline

### Drift Mitigation

Pure IMU integration drifts badly (~1m/s² error accumulating over time). We mitigate this by:

- **Route Projection** - Position is always snapped to the route polyline
- **Zero Velocity Updates (ZVU)** - Detecting stops resets velocity to zero
- **Speed Clamping** - Maximum speed limits prevent runaway integration
- **Low-pass Filtering** - Reduces sensor noise before integration

### Limitations

- **Starting Point** - User must manually set the starting position (no GPS)
- **Drift Accumulation** - Position estimate becomes less accurate over time
- **Phone Orientation** - Best results when phone is mounted stably in vehicle
- **No Off-Route Detection** - App assumes you follow the calculated route

## Screenshots

*Coming soon*

## Requirements

- Android 7.0+ (API 24)
- Device with accelerometer and gyroscope sensors
- Internet connection (for map tiles and routing)

## Download

Get the latest APK from [GitHub Releases](https://github.com/sk-agent01/imu-navigator/releases) or build from source.

## Build Instructions

### Prerequisites

- Android Studio Arctic Fox or later
- JDK 17
- Android SDK with API level 34

### Build Steps

```bash
# Clone the repository
git clone https://github.com/sk-agent01/imu-navigator.git
cd imu-navigator

# Build debug APK
./gradlew assembleDebug

# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

### Using GitHub Actions

The repository includes a GitHub Actions workflow that automatically builds APKs on every push. Download artifacts from the [Actions tab](https://github.com/sk-agent01/imu-navigator/actions).

## Project Structure

```
app/src/main/java/com/imunavigator/
├── MainActivity.kt              # Main UI and map interaction
├── model/
│   └── NavigationState.kt       # Data classes and state
├── sensor/
│   ├── IMUProcessor.kt          # Sensor data collection
│   └── DeadReckoning.kt         # Position estimation engine
└── routing/
    ├── RouteManager.kt          # OSRM API integration
    └── MapMatcher.kt            # Route projection utilities
```

## Usage

1. **Set Origin** - Tap the map to set your starting point
2. **Set Destination** - Tap the map to set where you want to go
3. **Calculate Route** - Press to fetch a route from OSRM
4. **Start Navigation** - Begin IMU-based position tracking along the route

## Technical Details

### Sensor Processing

- Accelerometer and gyroscope data at maximum sample rate
- Complementary filter for orientation estimation
- Low-pass filter on accelerometer to reduce noise
- Rotation matrix from Android's rotation vector sensor (if available)

### Position Integration

```
velocity += acceleration * dt  (with gravity removed)
position += velocity * dt
distance_on_route += speed * dt
```

### Route Projection

The estimated position is continuously projected onto the nearest point of the route polyline, constraining all drift to forward/backward movement along the known path.

## Contributing

Contributions welcome! Please open an issue or PR.

## License

MIT License - see [LICENSE](LICENSE)

## Acknowledgments

- [osmdroid](https://github.com/osmdroid/osmdroid) - OpenStreetMap for Android
- [OSRM](http://project-osrm.org/) - Routing engine
- [nisargnp/DeadReckoning](https://github.com/nisargnp/DeadReckoning) - Inspiration
