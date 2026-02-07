package com.imunavigator.model

import org.osmdroid.util.GeoPoint

/**
 * Represents a point on the route
 */
data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val distanceFromStart: Double = 0.0 // meters from route start
) {
    fun toGeoPoint(): GeoPoint = GeoPoint(lat, lon)
}

/**
 * Represents a calculated route
 */
data class Route(
    val points: List<RoutePoint>,
    val totalDistance: Double, // meters
    val estimatedTime: Long // seconds
) {
    fun isEmpty() = points.isEmpty()
}

/**
 * IMU sensor readings
 */
data class IMUReading(
    val timestamp: Long, // nanoseconds
    val accelerometer: FloatArray, // x, y, z in m/s²
    val gyroscope: FloatArray // x, y, z in rad/s
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IMUReading
        return timestamp == other.timestamp
    }

    override fun hashCode(): Int = timestamp.hashCode()
}

/**
 * Estimated position from dead reckoning
 */
data class EstimatedPosition(
    val latitude: Double,
    val longitude: Double,
    val heading: Double, // radians, 0 = North, clockwise
    val speed: Double, // m/s
    val distanceOnRoute: Double, // meters from route start
    val confidence: Float, // 0-1, decreases over time without corrections
    val timestamp: Long
) {
    fun toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)
}

/**
 * Navigation state
 */
sealed class NavigationState {
    object Idle : NavigationState()
    data class RouteSelected(val origin: GeoPoint, val destination: GeoPoint) : NavigationState()
    data class RouteLoaded(val route: Route) : NavigationState()
    data class Navigating(
        val route: Route,
        val currentPosition: EstimatedPosition,
        val nextWaypointIndex: Int
    ) : NavigationState()
    data class Error(val message: String) : NavigationState()
}

/**
 * UI events
 */
sealed class NavigationEvent {
    data class SelectOrigin(val point: GeoPoint) : NavigationEvent()
    data class SelectDestination(val point: GeoPoint) : NavigationEvent()
    object CalculateRoute : NavigationEvent()
    object StartNavigation : NavigationEvent()
    object StopNavigation : NavigationEvent()
    object Reset : NavigationEvent()
}
