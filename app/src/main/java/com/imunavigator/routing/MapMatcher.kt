package com.imunavigator.routing

import com.imunavigator.model.Route
import com.imunavigator.model.RoutePoint
import kotlin.math.*

/**
 * Map matching utilities for projecting positions onto a route.
 */
class MapMatcher {
    
    companion object {
        private const val EARTH_RADIUS = 6371000.0
    }
    
    /**
     * Find the closest point on the route to a given position
     * Returns the distance along the route to that point
     */
    fun snapToRoute(lat: Double, lon: Double, route: Route): SnapResult {
        if (route.points.isEmpty()) {
            return SnapResult(0.0, 0.0, 0.0, Double.MAX_VALUE)
        }
        
        if (route.points.size == 1) {
            val p = route.points[0]
            val dist = haversineDistance(lat, lon, p.lat, p.lon)
            return SnapResult(p.lat, p.lon, 0.0, dist)
        }
        
        var bestDistance = Double.MAX_VALUE
        var bestLat = route.points[0].lat
        var bestLon = route.points[0].lon
        var bestDistanceOnRoute = 0.0
        
        var accumulatedDistance = 0.0
        
        for (i in 0 until route.points.size - 1) {
            val p1 = route.points[i]
            val p2 = route.points[i + 1]
            
            // Find closest point on segment
            val result = closestPointOnSegment(lat, lon, p1.lat, p1.lon, p2.lat, p2.lon)
            
            if (result.distance < bestDistance) {
                bestDistance = result.distance
                bestLat = result.lat
                bestLon = result.lon
                bestDistanceOnRoute = accumulatedDistance + result.segmentFraction * 
                    haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
            }
            
            accumulatedDistance += haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
        }
        
        return SnapResult(bestLat, bestLon, bestDistanceOnRoute, bestDistance)
    }
    
    /**
     * Find the heading at a given point on the route
     * Returns heading in radians (0 = North, clockwise)
     */
    fun getHeadingAtDistance(route: Route, distance: Double): Double {
        if (route.points.size < 2) return 0.0
        
        var accumulatedDistance = 0.0
        
        for (i in 0 until route.points.size - 1) {
            val p1 = route.points[i]
            val p2 = route.points[i + 1]
            val segmentLength = haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
            
            if (accumulatedDistance + segmentLength >= distance || i == route.points.size - 2) {
                // Calculate bearing from p1 to p2
                return calculateBearing(p1.lat, p1.lon, p2.lat, p2.lon)
            }
            
            accumulatedDistance += segmentLength
        }
        
        return 0.0
    }
    
    /**
     * Find closest point on a line segment to a given point
     */
    private fun closestPointOnSegment(
        lat: Double, lon: Double,
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): SegmentProjection {
        // Convert to local Cartesian for simplicity
        val x = lon
        val y = lat
        val x1 = lon1
        val y1 = lat1
        val x2 = lon2
        val y2 = lat2
        
        val dx = x2 - x1
        val dy = y2 - y1
        
        if (dx == 0.0 && dy == 0.0) {
            // Segment is a point
            return SegmentProjection(lat1, lon1, 0.0, haversineDistance(lat, lon, lat1, lon1))
        }
        
        // Parameter t for line equation P = P1 + t*(P2-P1)
        var t = ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)
        t = t.coerceIn(0.0, 1.0) // Clamp to segment
        
        val closestLon = x1 + t * dx
        val closestLat = y1 + t * dy
        
        val distance = haversineDistance(lat, lon, closestLat, closestLon)
        
        return SegmentProjection(closestLat, closestLon, t, distance)
    }
    
    /**
     * Calculate bearing from point 1 to point 2
     * Returns bearing in radians (0 = North, clockwise)
     */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        
        val x = sin(dLon) * cos(lat2Rad)
        val y = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        
        return atan2(x, y)
    }
    
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS * c
    }
    
    data class SnapResult(
        val lat: Double,
        val lon: Double,
        val distanceOnRoute: Double,
        val perpendicularDistance: Double
    )
    
    private data class SegmentProjection(
        val lat: Double,
        val lon: Double,
        val segmentFraction: Double,
        val distance: Double
    )
}
