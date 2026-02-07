package com.imunavigator.routing

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.imunavigator.model.Route
import com.imunavigator.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.GeoPoint
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Manages route calculation using OSRM (Open Source Routing Machine) public API.
 * Falls back to simple point-to-point if routing fails.
 */
class RouteManager {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    companion object {
        // OSRM public demo server (for development - use your own for production)
        private const val OSRM_BASE_URL = "https://router.project-osrm.org"
        
        // Alternative: GraphHopper API (requires API key)
        // private const val GRAPHHOPPER_BASE_URL = "https://graphhopper.com/api/1"
        
        private const val EARTH_RADIUS = 6371000.0
    }
    
    /**
     * Calculate a driving route between two points
     */
    suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint): Result<Route> {
        return withContext(Dispatchers.IO) {
            try {
                // Try OSRM first
                val osrmResult = fetchOSRMRoute(origin, destination)
                if (osrmResult.isSuccess) {
                    return@withContext osrmResult
                }
                
                // Fallback to simple straight-line route with intermediate points
                val fallbackRoute = createFallbackRoute(origin, destination)
                Result.success(fallbackRoute)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    /**
     * Fetch route from OSRM API
     */
    private fun fetchOSRMRoute(origin: GeoPoint, destination: GeoPoint): Result<Route> {
        val url = "${OSRM_BASE_URL}/route/v1/driving/" +
                  "${origin.longitude},${origin.latitude};" +
                  "${destination.longitude},${destination.latitude}" +
                  "?overview=full&geometries=geojson&steps=false"
        
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        
        return try {
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return Result.failure(IOException("OSRM API error: ${response.code}"))
            }
            
            val body = response.body?.string() ?: return Result.failure(IOException("Empty response"))
            val json = gson.fromJson(body, JsonObject::class.java)
            
            if (json.get("code")?.asString != "Ok") {
                return Result.failure(IOException("OSRM routing failed"))
            }
            
            val routes = json.getAsJsonArray("routes")
            if (routes.size() == 0) {
                return Result.failure(IOException("No route found"))
            }
            
            val routeJson = routes[0].asJsonObject
            val distance = routeJson.get("distance").asDouble
            val duration = routeJson.get("duration").asLong
            
            // Parse geometry (GeoJSON LineString)
            val geometry = routeJson.getAsJsonObject("geometry")
            val coordinates = geometry.getAsJsonArray("coordinates")
            
            val points = mutableListOf<RoutePoint>()
            var accumulatedDistance = 0.0
            var prevLat = 0.0
            var prevLon = 0.0
            
            for (i in 0 until coordinates.size()) {
                val coord = coordinates[i].asJsonArray
                val lon = coord[0].asDouble
                val lat = coord[1].asDouble
                
                if (i > 0) {
                    accumulatedDistance += haversineDistance(prevLat, prevLon, lat, lon)
                }
                
                points.add(RoutePoint(lat, lon, accumulatedDistance))
                prevLat = lat
                prevLon = lon
            }
            
            Result.success(Route(
                points = points,
                totalDistance = distance,
                estimatedTime = duration
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Create a simple fallback route with interpolated points
     */
    private fun createFallbackRoute(origin: GeoPoint, destination: GeoPoint): Route {
        val totalDistance = haversineDistance(
            origin.latitude, origin.longitude,
            destination.latitude, destination.longitude
        )
        
        // Create intermediate points every ~100m
        val numPoints = maxOf(2, (totalDistance / 100).toInt())
        val points = mutableListOf<RoutePoint>()
        
        for (i in 0..numPoints) {
            val fraction = i.toDouble() / numPoints
            val lat = origin.latitude + (destination.latitude - origin.latitude) * fraction
            val lon = origin.longitude + (destination.longitude - origin.longitude) * fraction
            val dist = totalDistance * fraction
            points.add(RoutePoint(lat, lon, dist))
        }
        
        // Estimate time assuming 40 km/h average
        val estimatedTime = (totalDistance / 11.1).toLong() // 40 km/h = 11.1 m/s
        
        return Route(
            points = points,
            totalDistance = totalDistance,
            estimatedTime = estimatedTime
        )
    }
    
    /**
     * Haversine distance calculation
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS * c
    }
}
