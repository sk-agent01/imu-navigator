package com.imunavigator.sensor

import com.imunavigator.model.EstimatedPosition
import com.imunavigator.model.IMUReading
import com.imunavigator.model.Route
import com.imunavigator.model.RoutePoint
import kotlin.math.*

/**
 * Dead reckoning engine for vehicle navigation.
 * 
 * Key insight: Pure IMU integration drifts badly. We mitigate by:
 * 1. Constraining position to the route polyline
 * 2. Detecting stops (Zero Velocity Updates - ZVU)
 * 3. Using forward acceleration patterns typical of vehicles
 */
class DeadReckoning {
    
    companion object {
        // Earth radius in meters
        private const val EARTH_RADIUS = 6371000.0
        
        // Thresholds for zero velocity detection
        private const val ZVU_ACCEL_THRESHOLD = 0.3f // m/s² - vehicle is stopped if accel is below this
        private const val ZVU_GYRO_THRESHOLD = 0.05f // rad/s - and rotation is also low
        private const val ZVU_DURATION_MS = 500L // Need to be still for this long
        
        // Speed estimation parameters
        private const val MAX_VEHICLE_SPEED = 50.0 // m/s (~180 km/h)
        private const val MAX_ACCELERATION = 5.0 // m/s² (reasonable for cars)
        
        // Confidence decay rate per second
        private const val CONFIDENCE_DECAY_RATE = 0.01f
    }
    
    // Current state
    private var currentRoute: Route? = null
    private var distanceOnRoute: Double = 0.0 // meters from start
    private var currentSpeed: Double = 0.0 // m/s
    private var currentHeading: Double = 0.0 // radians, 0 = North
    private var confidence: Float = 1.0f
    
    // Integration state
    private var lastTimestamp: Long = 0
    private var velocityX: Double = 0.0 // m/s in world frame (East)
    private var velocityY: Double = 0.0 // m/s in world frame (North)
    
    // ZVU detection
    private var lowMotionStartTime: Long = 0
    private var isStationary: Boolean = true
    
    // Kalman-like velocity smoothing
    private var smoothedSpeed: Double = 0.0
    private val SPEED_SMOOTHING = 0.3
    
    /**
     * Initialize dead reckoning with a route
     */
    fun initialize(route: Route, startHeading: Double = 0.0) {
        currentRoute = route
        distanceOnRoute = 0.0
        currentSpeed = 0.0
        currentHeading = startHeading
        confidence = 1.0f
        lastTimestamp = 0
        velocityX = 0.0
        velocityY = 0.0
        isStationary = true
        smoothedSpeed = 0.0
    }
    
    /**
     * Process an IMU reading and update estimated position
     */
    fun processReading(reading: IMUReading, imuProcessor: IMUProcessor): EstimatedPosition {
        val route = currentRoute ?: return createFallbackPosition()
        
        // Calculate time delta
        val timestamp = reading.timestamp
        val dt = if (lastTimestamp > 0) {
            (timestamp - lastTimestamp) / 1_000_000_000.0 // Convert ns to seconds
        } else {
            0.0
        }
        lastTimestamp = timestamp
        
        // Skip if time delta is too large (sensor gap) or too small
        if (dt <= 0 || dt > 1.0) {
            return getCurrentPosition()
        }
        
        // Get acceleration in world frame
        val worldAccel = imuProcessor.transformToWorldFrame(reading.accelerometer)
        val accelMagnitude = sqrt(worldAccel[0] * worldAccel[0] + 
                                  worldAccel[1] * worldAccel[1]).toDouble()
        
        // Get rotation rate magnitude
        val gyroMagnitude = sqrt(reading.gyroscope[0] * reading.gyroscope[0] + 
                                 reading.gyroscope[1] * reading.gyroscope[1] + 
                                 reading.gyroscope[2] * reading.gyroscope[2])
        
        // Zero Velocity Update detection
        if (accelMagnitude < ZVU_ACCEL_THRESHOLD && gyroMagnitude < ZVU_GYRO_THRESHOLD) {
            if (lowMotionStartTime == 0L) {
                lowMotionStartTime = timestamp
            } else if ((timestamp - lowMotionStartTime) / 1_000_000 > ZVU_DURATION_MS) {
                // Apply ZVU - reset velocities
                velocityX = 0.0
                velocityY = 0.0
                currentSpeed = 0.0
                isStationary = true
                // Boost confidence when we detect a stop
                confidence = minOf(1.0f, confidence + 0.1f)
            }
        } else {
            lowMotionStartTime = 0
            isStationary = false
        }
        
        // Update heading from gyroscope (Z axis rotation)
        // Assuming phone is mounted roughly upright
        val yawRate = reading.gyroscope[2].toDouble()
        currentHeading += yawRate * dt
        // Normalize heading to [0, 2π]
        currentHeading = ((currentHeading % (2 * PI)) + 2 * PI) % (2 * PI)
        
        // Integrate acceleration to get velocity
        // Only integrate horizontal acceleration (ignore vertical)
        if (!isStationary) {
            // Clamp acceleration to reasonable bounds
            val clampedAccelX = worldAccel[0].toDouble().coerceIn(-MAX_ACCELERATION, MAX_ACCELERATION)
            val clampedAccelY = worldAccel[1].toDouble().coerceIn(-MAX_ACCELERATION, MAX_ACCELERATION)
            
            velocityX += clampedAccelX * dt
            velocityY += clampedAccelY * dt
            
            // Calculate speed from velocity components
            val rawSpeed = sqrt(velocityX * velocityX + velocityY * velocityY)
            
            // Clamp to maximum vehicle speed
            if (rawSpeed > MAX_VEHICLE_SPEED) {
                val scale = MAX_VEHICLE_SPEED / rawSpeed
                velocityX *= scale
                velocityY *= scale
            }
            
            currentSpeed = sqrt(velocityX * velocityX + velocityY * velocityY)
        }
        
        // Smooth the speed estimate
        smoothedSpeed = SPEED_SMOOTHING * currentSpeed + (1 - SPEED_SMOOTHING) * smoothedSpeed
        
        // Update distance along route
        distanceOnRoute += smoothedSpeed * dt
        distanceOnRoute = distanceOnRoute.coerceIn(0.0, route.totalDistance)
        
        // Decay confidence over time
        confidence = maxOf(0.1f, confidence - (CONFIDENCE_DECAY_RATE * dt.toFloat()))
        
        return getCurrentPosition()
    }
    
    /**
     * Get current estimated position projected onto route
     */
    fun getCurrentPosition(): EstimatedPosition {
        val route = currentRoute ?: return createFallbackPosition()
        val point = getPointAtDistance(route, distanceOnRoute)
        
        return EstimatedPosition(
            latitude = point.lat,
            longitude = point.lon,
            heading = currentHeading,
            speed = smoothedSpeed,
            distanceOnRoute = distanceOnRoute,
            confidence = confidence,
            timestamp = lastTimestamp
        )
    }
    
    /**
     * Set initial position on route (e.g., from user tap or known start point)
     */
    fun setPositionOnRoute(distanceFromStart: Double) {
        val route = currentRoute ?: return
        distanceOnRoute = distanceFromStart.coerceIn(0.0, route.totalDistance)
        confidence = 1.0f // Reset confidence when position is manually set
    }
    
    /**
     * Get the point at a given distance along the route
     */
    private fun getPointAtDistance(route: Route, distance: Double): RoutePoint {
        if (route.points.isEmpty()) {
            return RoutePoint(0.0, 0.0, 0.0)
        }
        if (route.points.size == 1 || distance <= 0) {
            return route.points.first()
        }
        if (distance >= route.totalDistance) {
            return route.points.last()
        }
        
        // Find the segment containing this distance
        var accumulatedDistance = 0.0
        for (i in 0 until route.points.size - 1) {
            val p1 = route.points[i]
            val p2 = route.points[i + 1]
            val segmentLength = haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
            
            if (accumulatedDistance + segmentLength >= distance) {
                // Interpolate within this segment
                val segmentProgress = (distance - accumulatedDistance) / segmentLength
                val lat = p1.lat + (p2.lat - p1.lat) * segmentProgress
                val lon = p1.lon + (p2.lon - p1.lon) * segmentProgress
                return RoutePoint(lat, lon, distance)
            }
            
            accumulatedDistance += segmentLength
        }
        
        return route.points.last()
    }
    
    /**
     * Calculate distance between two points using Haversine formula
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + 
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS * c
    }
    
    private fun createFallbackPosition(): EstimatedPosition {
        return EstimatedPosition(
            latitude = 0.0,
            longitude = 0.0,
            heading = 0.0,
            speed = 0.0,
            distanceOnRoute = 0.0,
            confidence = 0.0f,
            timestamp = System.nanoTime()
        )
    }
    
    /**
     * Get current speed in km/h for display
     */
    fun getSpeedKmh(): Double = smoothedSpeed * 3.6
    
    /**
     * Get remaining distance on route
     */
    fun getRemainingDistance(): Double {
        return (currentRoute?.totalDistance ?: 0.0) - distanceOnRoute
    }
    
    /**
     * Check if navigation is complete
     */
    fun isNavigationComplete(): Boolean {
        val route = currentRoute ?: return true
        return distanceOnRoute >= route.totalDistance - 10 // Within 10m of destination
    }
}
