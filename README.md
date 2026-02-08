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

### Simplified 1D Navigation

**Key Insight:** Since the user follows a pre-calculated route, we only need 1D navigation along the route curve — not full 2D dead reckoning!

**Algorithm:**
```
distance_traveled = 0
while navigating:
    speed = estimate_speed_from_imu()      # m/s
    distance_traveled += speed * dt         # Integrate speed
    position = route.get_point_at(distance) # Project onto route
    update_map(position)
```

### Speed Estimation from IMU

1. **Forward Acceleration** - Remove gravity, integrate acceleration
2. **Vibration Analysis** - Road noise correlates with vehicle speed
3. **Kalman Filter** - Fuses acceleration and vibration estimates
4. **Zero Velocity Updates** - Detects stops to reset drift

### Why This Works Better

- **1D vs 2D** - Only need to track distance along route, not position in plane
- **No Heading Drift** - Route direction is known, no gyro integration needed
- **Constrained Error** - Position error only accumulates along route, not perpendicular
- **Self-Correcting** - Speed estimation errors average out over distance

### Limitations

- **Route Following Required** - User must stay on the calculated route
- **Starting Point** - User sets starting position manually (no GPS)
- **Speed Drift** - Accumulated distance may drift over long distances
- **Phone Mounting** - Best with phone mounted stably in vehicle

## Screenshots

*Coming soon*

## Requirements

- Android 8.0+ (API 26)
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

### Speed Estimation Pipeline

```kotlin
// 1. Get acceleration without gravity
forwardAccel = transformToWorldFrame(accelerometer) 

// 2. Zero Velocity Detection (ZVU)
if (accelMagnitude < 0.4 && gyroMagnitude < 0.08):
    speed = 0  // Vehicle is stopped

// 3. Kalman Filter Update
predictedSpeed = speed + forwardAccel * dt
vibrationSpeed = estimateFromRoadNoise(accelHistory)
speed = kalmanFuse(predictedSpeed, vibrationSpeed)

// 4. Integrate to distance
distanceTraveled += speed * dt
```

### Sensors Used

| Sensor | Purpose |
|--------|---------|
| Accelerometer | Forward acceleration for speed integration |
| Gyroscope | Motion detection for ZVU |
| Rotation Vector | Gravity direction for acceleration transform |
| Linear Acceleration | Pre-filtered acceleration (if available) |

### Route Projection

Position is simply the point at `distanceTraveled` meters along the route polyline. Binary search finds the correct segment, then linear interpolation within the segment.

## Contributing

Contributions welcome! Please open an issue or PR.

## License

MIT License - see [LICENSE](LICENSE)

## Acknowledgments

- [osmdroid](https://github.com/osmdroid/osmdroid) - OpenStreetMap for Android
- [OSRM](http://project-osrm.org/) - Routing engine
- [nisargnp/DeadReckoning](https://github.com/nisargnp/DeadReckoning) - Inspiration
