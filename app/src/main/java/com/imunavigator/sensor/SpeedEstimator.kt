package com.imunavigator.sensor

import android.hardware.SensorManager
import kotlin.math.*

/**
 * Estimates vehicle speed from IMU sensors.
 * 
 * Key insight: We only need SPEED, not direction. The user is assumed
 * to follow the route, so we just integrate speed along the 1D route curve.
 * 
 * Speed estimation approaches:
 * 1. Forward acceleration integration with gravity removal
 * 2. Vibration pattern analysis (road noise correlates with speed)
 * 3. Kalman filter to smooth estimates and handle drift
 */
class SpeedEstimator {
    
    companion object {
        // Physics constants
        private const val GRAVITY = SensorManager.GRAVITY_EARTH
        
        // Speed estimation parameters
        private const val MAX_SPEED_MS = 50.0 // ~180 km/h max
        private const val MIN_SPEED_MS = 0.0
        
        // Zero velocity detection thresholds
        private const val ZVU_ACCEL_THRESHOLD = 0.4 // m/s² - stationary if below
        private const val ZVU_GYRO_THRESHOLD = 0.08 // rad/s - and rotation low
        private const val ZVU_SAMPLES_REQUIRED = 50 // ~0.5s at 100Hz
        
        // Kalman filter parameters for speed
        private const val PROCESS_NOISE = 0.5 // How much we expect speed to change
        private const val MEASUREMENT_NOISE = 2.0 // How noisy our acceleration is
        
        // Vibration-based speed correlation (experimental)
        private const val VIBRATION_SPEED_FACTOR = 0.15 // Tunable
    }
    
    // Kalman filter state
    private var speedEstimate = 0.0 // Current speed estimate (m/s)
    private var speedVariance = 10.0 // Uncertainty in estimate
    
    // Zero velocity detection
    private var lowMotionSamples = 0
    private var isStationary = true
    
    // Acceleration history for vibration analysis
    private val accelHistory = ArrayDeque<Double>(100)
    
    // Orientation tracking (for gravity removal)
    private val rotationMatrix = FloatArray(9)
    private var hasOrientation = false
    
    // Last timestamp for dt calculation
    private var lastTimestampNs: Long = 0
    
    // Cumulative distance for 1D navigation
    private var distanceTraveled = 0.0
    
    /**
     * Reset estimator for new navigation session
     */
    fun reset() {
        speedEstimate = 0.0
        speedVariance = 10.0
        lowMotionSamples = 0
        isStationary = true
        accelHistory.clear()
        lastTimestampNs = 0
        distanceTraveled = 0.0
    }
    
    /**
     * Set starting distance on route (e.g., if user starts mid-route)
     */
    fun setDistanceTraveled(distance: Double) {
        distanceTraveled = distance
    }
    
    /**
     * Update orientation from rotation vector sensor
     */
    fun updateOrientation(rotationVector: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
        hasOrientation = true
    }
    
    /**
     * Process IMU reading and update speed/distance estimates
     * 
     * @param timestampNs Sensor timestamp in nanoseconds
     * @param accel Accelerometer reading [x, y, z] in m/s²
     * @param gyro Gyroscope reading [x, y, z] in rad/s
     * @return Current speed estimate in m/s
     */
    fun processReading(timestampNs: Long, accel: FloatArray, gyro: FloatArray): SpeedUpdate {
        // Calculate time delta
        val dt = if (lastTimestampNs > 0) {
            (timestampNs - lastTimestampNs) / 1_000_000_000.0
        } else {
            0.0
        }
        lastTimestampNs = timestampNs
        
        // Skip if dt is invalid
        if (dt <= 0 || dt > 0.5) {
            return SpeedUpdate(speedEstimate, distanceTraveled, isStationary)
        }
        
        // Remove gravity and get forward acceleration
        val forwardAccel = extractForwardAcceleration(accel)
        
        // Calculate acceleration and gyro magnitudes for ZVU detection
        val accelMag = sqrt(forwardAccel.pow(2))
        val gyroMag = sqrt(gyro[0].pow(2) + gyro[1].pow(2) + gyro[2].pow(2))
        
        // Zero Velocity Update detection
        if (accelMag < ZVU_ACCEL_THRESHOLD && gyroMag < ZVU_GYRO_THRESHOLD) {
            lowMotionSamples++
            if (lowMotionSamples >= ZVU_SAMPLES_REQUIRED) {
                // Vehicle is stationary - reset speed
                isStationary = true
                speedEstimate = 0.0
                speedVariance = 0.1 // High confidence we're stopped
                return SpeedUpdate(0.0, distanceTraveled, true)
            }
        } else {
            lowMotionSamples = 0
            isStationary = false
        }
        
        // Store acceleration for vibration analysis
        accelHistory.addLast(accelMag)
        if (accelHistory.size > 100) {
            accelHistory.removeFirst()
        }
        
        // === Speed estimation using Kalman filter ===
        
        // Predict step: speed might change due to acceleration
        val predictedSpeed = speedEstimate + forwardAccel * dt
        val predictedVariance = speedVariance + PROCESS_NOISE * dt
        
        // Get vibration-based speed hint (road noise correlates with speed)
        val vibrationSpeed = estimateSpeedFromVibration()
        
        // Update step: fuse acceleration-integrated speed with vibration hint
        if (vibrationSpeed > 0 && !isStationary) {
            // Use vibration as a weak measurement
            val kalmanGain = predictedVariance / (predictedVariance + MEASUREMENT_NOISE)
            speedEstimate = predictedSpeed + kalmanGain * (vibrationSpeed - predictedSpeed)
            speedVariance = (1 - kalmanGain) * predictedVariance
        } else {
            speedEstimate = predictedSpeed
            speedVariance = predictedVariance
        }
        
        // Clamp speed to valid range
        speedEstimate = speedEstimate.coerceIn(MIN_SPEED_MS, MAX_SPEED_MS)
        
        // Can't go backwards on route
        if (speedEstimate < 0) speedEstimate = 0.0
        
        // Update cumulative distance
        if (!isStationary) {
            distanceTraveled += speedEstimate * dt
        }
        
        return SpeedUpdate(speedEstimate, distanceTraveled, isStationary)
    }
    
    /**
     * Extract forward acceleration by removing gravity.
     * 
     * If we have orientation, transform to world frame.
     * Otherwise, use simple high-pass filter approach.
     */
    private fun extractForwardAcceleration(accel: FloatArray): Double {
        return if (hasOrientation) {
            // Transform acceleration to world frame using rotation matrix
            val worldAccelX = rotationMatrix[0] * accel[0] + 
                             rotationMatrix[1] * accel[1] + 
                             rotationMatrix[2] * accel[2]
            val worldAccelY = rotationMatrix[3] * accel[0] + 
                             rotationMatrix[4] * accel[1] + 
                             rotationMatrix[5] * accel[2]
            
            // Horizontal acceleration magnitude (forward motion)
            // We don't know which direction is "forward", so use horizontal magnitude
            sqrt(worldAccelX.pow(2) + worldAccelY.pow(2)).toDouble()
        } else {
            // Fallback: Remove approximate gravity and take magnitude
            // Assuming phone is roughly upright, Z has gravity
            val accelNoGravity = sqrt(
                accel[0].pow(2) + 
                accel[1].pow(2) + 
                (accel[2] - GRAVITY).pow(2)
            )
            accelNoGravity.toDouble()
        }
    }
    
    /**
     * Estimate speed from vibration patterns.
     * 
     * Road vibrations increase with vehicle speed. This provides
     * a rough speed hint that helps prevent drift in pure integration.
     */
    private fun estimateSpeedFromVibration(): Double {
        if (accelHistory.size < 20) return 0.0
        
        // Calculate variance of recent accelerations (vibration energy)
        val recent = accelHistory.takeLast(20)
        val mean = recent.average()
        val variance = recent.map { (it - mean).pow(2) }.average()
        val stdDev = sqrt(variance)
        
        // Map vibration to approximate speed
        // This is a rough heuristic - would need calibration per vehicle
        return (stdDev * VIBRATION_SPEED_FACTOR * 10).coerceIn(0.0, MAX_SPEED_MS)
    }
    
    /**
     * Get current speed in km/h for display
     */
    fun getSpeedKmh(): Double = speedEstimate * 3.6
    
    /**
     * Get total distance traveled in meters
     */
    fun getDistanceTraveled(): Double = distanceTraveled
    
    /**
     * Data class for speed update results
     */
    data class SpeedUpdate(
        val speedMs: Double,
        val distanceTraveled: Double,
        val isStationary: Boolean
    )
}

private fun Float.pow(n: Int): Float = this.toDouble().pow(n).toFloat()
private fun Double.pow(n: Int): Double = kotlin.math.pow(this, n.toDouble())
