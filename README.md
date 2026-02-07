# IMU Navigator

Android navigation app that uses ONLY accelerometer + gyroscope for position tracking (no GPS).

## Features

- OpenStreetMap-based map display (osmdroid)
- Route building between two points (GraphHopper)
- IMU-based dead reckoning for position estimation
- Map matching to project position onto route
- Works completely without GPS

## Technical Approach

### Dead Reckoning
The app uses smartphone IMU sensors (accelerometer + gyroscope) to estimate movement:

1. **Accelerometer** - Detects acceleration in device frame
2. **Gyroscope** - Tracks device orientation changes
3. **Sensor Fusion** - Combines both to estimate velocity and heading
4. **Route Constraint** - Projects estimated position onto the route to reduce drift

### Drift Mitigation
Pure IMU integration drifts badly (~1m/s² error). We mitigate this by:
- Constraining position to the known route polyline
- Detecting stops (zero velocity updates)
- Using road network topology (can't jump between roads)

## Requirements

- Android 7.0+ (API 24)
- Device with accelerometer and gyroscope

## Build Instructions

```bash
# Clone the repository
git clone https://github.com/YOUR_USERNAME/imu-navigator.git
cd imu-navigator

# Build with Gradle
./gradlew assembleDebug

# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

## Project Structure

```
app/
├── src/main/java/com/imunavigator/
│   ├── MainActivity.kt           # Main UI
│   ├── MapFragment.kt            # Map display
│   ├── sensor/
│   │   ├── IMUProcessor.kt       # Sensor fusion
│   │   └── DeadReckoning.kt      # Position estimation
│   ├── routing/
│   │   ├── RouteManager.kt       # Route building
│   │   └── MapMatcher.kt         # Project to route
│   └── model/
│       └── NavigationState.kt    # State classes
└── src/main/res/
    └── layout/                   # UI layouts
```

## License

MIT License
