package com.imunavigator.sensor

import com.imunavigator.model.EstimatedPosition
import com.imunavigator.model.Route
import com.imunavigator.model.RoutePoint
import kotlin.math.*

/**
 * Simplified 1D Dead Reckoning for route navigation.
 * 
 * KEY INSIGHT: We don't need full 2D dead reckoning!
 * 
 * Since the user follows the pre-calculated route, we only need:
 * 1. Estimate SPEED from IMU sensors
 * 2. Integrate speed → cumulative distance traveled
 * 3. Project distance along the route polyline → position
 * 
 * This is 1D navigation along a curve, not 2D navigation in a plane.
 * Much simpler and lower error accumulation!
 */
class DeadReckoning {
    
    companion object {
        private const val EARTH_RADIUS = 6371000.0
    }
    
    private var currentRoute: Route? = null
    private val speedEstimator = SpeedEstimator()
    
    // Pre-calculated cumulative distances for route segments
    private var segmentDistances: List<Double> = emptyList()
    
    /**
     * Initialize with a route for navigation
     */
    fun initialize(route: Route, startDistance: Double = 0.0) {
        currentRoute = route
        speedEstimator.reset()
        speedEstimator.setDistanceTraveled(startDistance)
        
        // Pre-calculate cumulative distances for each route point
        segmentDistances = calculateCumulativeDistances(route)
    }
    
    /**
     * Calculate cumulative distances along the route
     */
    private fun calculateCumulativeDistances(route: Route): List<Double> {
        if (route.points.isEmpty()) return emptyList()
        
        val distances = mutableListOf(0.0)
        var cumulative = 0.0
        
        for (i in 1 until route.points.size) {
            val p1 = route.points[i - 1]
            val p2 = route.points[i]
            cumulative += haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
            distances.add(cumulative)
        }
        
        return distances
    }
    
    /**
     * Update orientation from rotation vector sensor
     */
    fun updateOrientation(rotationVector: FloatArray) {
        speedEstimator.updateOrientation(rotationVector)
    }
    
    /**
     * Process IMU reading and get updated position on route
     * 
     * @param timestampNs Sensor timestamp in nanoseconds
     * @param accel Accelerometer reading [x, y, z] in m/s²
     * @param gyro Gyroscope reading [x, y, z] in rad/s
     * @return Estimated position on the route
     */
    fun processReading(timestampNs: Long, accel: FloatArray, gyro: FloatArray): EstimatedPosition {
        val route = currentRoute ?: return createFallbackPosition()
        
        // Update speed estimate and get cumulative distance
        val update = speedEstimator.processReading(timestampNs, accel, gyro)
        
        // Clamp distance to route bounds
        val distance = update.distanceTraveled.coerceIn(0.0, route.totalDistance)
        
        // Get position on route at this distance
        val point = getPointAtDistance(route, distance)
        
        // Calculate heading from route direction at this point
        val heading = getHeadingAtDistance(distance)
        
        return EstimatedPosition(
            latitude = point.lat,
            longitude = point.lon,
            heading = heading,
            speed = update.speedMs,
            distanceOnRoute = distance,
            confidence = calculateConfidence(update),
            timestamp = timestampNs
        )
    }
    
    /**
     * Get point on route at given distance from start
     */
    private fun getPointAtDistance(route: Route, distance: Double): RoutePoint {
        if (route.points.isEmpty()) return RoutePoint(0.0, 0.0, 0.0)
        if (route.points.size == 1 || distance <= 0) return route.points.first()
        if (distance >= route.totalDistance) return route.points.last()
        
        // Binary search for the segment containing this distance
        val segmentIndex = segmentDistances.binarySearch { it.compareTo(distance) }
        val insertionPoint = if (segmentIndex >= 0) segmentIndex else -(segmentIndex + 1)
        val index = (insertionPoint - 1).coerceIn(0, route.points.size - 2)
        
        // Interpolate within the segment
        val p1 = route.points[index]
        val p2 = route.points[index + 1]
        val segmentStart = segmentDistances[index]
        val segmentEnd = segmentDistances[index + 1]
        val segmentLength = segmentEnd - segmentStart
        
        if (segmentLength <= 0) return p1
        
        val fraction = (distance - segmentStart) / segmentLength
        val lat = p1.lat + (p2.lat - p1.lat) * fraction
        val lon = p1.lon + (p2.lon - p1.lon) * fraction
        
        return RoutePoint(lat, lon, distance)
    }
    
    /**
     * Get route heading at given distance
     */
    private fun getHeadingAtDistance(distance: Double): Double {
        val route = currentRoute ?: return 0.0
        if (route.points.size < 2) return 0.0
        
        // Find segment and calculate bearing
        val segmentIndex = segmentDistances.binarySearch { it.compareTo(distance) }
        val insertionPoint = if (segmentIndex >= 0) segmentIndex else -(segmentIndex + 1)
        val index = (insertionPoint - 1).coerceIn(0, route.points.size - 2)
        
        val p1 = route.points[index]
        val p2 = route.points[index + 1]
        
        return calculateBearing(p1.lat, p1.lon, p2.lat, p2.lon)
    }
    
    /**
     * Calculate bearing from point 1 to point 2
     */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        
        val x = sin(dLon) * cos(lat2Rad)
        val y = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        
        return atan2(x, y)
    }
    
    /**
     * Calculate confidence based on estimator state
     */
    private fun calculateConfidence(update: SpeedEstimator.SpeedUpdate): Float {
        return when {
            update.isStationary -> 0.95f  // High confidence when stopped
            update.speedMs < 5 -> 0.8f    // Good confidence at low speed
            update.speedMs < 20 -> 0.6f   // Moderate at medium speed
            else -> 0.4f                   // Lower at high speed (more drift)
        }
    }
    
    /**
     * Manually set position on route (e.g., user correction)
     */
    fun setPositionOnRoute(distanceFromStart: Double) {
        speedEstimator.setDistanceTraveled(distanceFromStart)
    }
    
    /**
     * Get current speed in km/h
     */
    fun getSpeedKmh(): Double = speedEstimator.getSpeedKmh()
    
    /**
     * Get remaining distance on route
     */
    fun getRemainingDistance(): Double {
        val route = currentRoute ?: return 0.0
        return route.totalDistance - speedEstimator.getDistanceTraveled()
    }
    
    /**
     * Check if navigation is complete
     */
    fun isNavigationComplete(): Boolean {
        val route = currentRoute ?: return true
        return speedEstimator.getDistanceTraveled() >= route.totalDistance - 20 // Within 20m
    }
    
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
}
