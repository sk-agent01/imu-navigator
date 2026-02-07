package com.imunavigator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.imunavigator.databinding.ActivityMainBinding
import com.imunavigator.model.*
import com.imunavigator.routing.MapMatcher
import com.imunavigator.routing.RouteManager
import com.imunavigator.sensor.DeadReckoning
import com.imunavigator.sensor.IMUProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var map: MapView
    
    // Components
    private lateinit var imuProcessor: IMUProcessor
    private lateinit var deadReckoning: DeadReckoning
    private lateinit var routeManager: RouteManager
    private lateinit var mapMatcher: MapMatcher
    
    // State
    private var originPoint: GeoPoint? = null
    private var destinationPoint: GeoPoint? = null
    private var currentRoute: Route? = null
    private var isNavigating = false
    private var sensorJob: Job? = null
    
    // Map overlays
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var positionMarker: Marker? = null
    
    // Selection mode
    private enum class SelectionMode { ORIGIN, DESTINATION, NONE }
    private var selectionMode = SelectionMode.NONE
    
    companion object {
        private const val REQUEST_PERMISSIONS = 1
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize osmdroid configuration
        Configuration.getInstance().load(applicationContext, 
            getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize components
        imuProcessor = IMUProcessor(this)
        deadReckoning = DeadReckoning()
        routeManager = RouteManager()
        mapMatcher = MapMatcher()
        
        // Check sensors
        if (!imuProcessor.hasRequiredSensors()) {
            AlertDialog.Builder(this)
                .setTitle("Sensors Not Available")
                .setMessage("This device does not have the required accelerometer and/or gyroscope sensors.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .show()
            return
        }
        
        setupMap()
        setupButtons()
        requestPermissions()
        
        // Show sensor info
        binding.tvSensorInfo.text = imuProcessor.getSensorInfo()
    }
    
    private fun setupMap() {
        map = binding.mapView
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        
        val mapController = map.controller
        mapController.setZoom(15.0)
        
        // Default to Moscow center
        val startPoint = GeoPoint(55.7558, 37.6173)
        mapController.setCenter(startPoint)
        
        // Handle map taps for point selection
        map.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent?, mapView: MapView?): Boolean {
                if (e == null || mapView == null) return false
                
                val projection = mapView.projection
                val geoPoint = projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                
                when (selectionMode) {
                    SelectionMode.ORIGIN -> {
                        setOrigin(geoPoint)
                        selectionMode = SelectionMode.NONE
                        updateButtonStates()
                    }
                    SelectionMode.DESTINATION -> {
                        setDestination(geoPoint)
                        selectionMode = SelectionMode.NONE
                        updateButtonStates()
                    }
                    SelectionMode.NONE -> {
                        // Do nothing
                    }
                }
                return true
            }
        })
    }
    
    private fun setupButtons() {
        binding.btnSetOrigin.setOnClickListener {
            selectionMode = SelectionMode.ORIGIN
            Toast.makeText(this, "Tap on the map to set origin", Toast.LENGTH_SHORT).show()
            updateButtonStates()
        }
        
        binding.btnSetDestination.setOnClickListener {
            selectionMode = SelectionMode.DESTINATION
            Toast.makeText(this, "Tap on the map to set destination", Toast.LENGTH_SHORT).show()
            updateButtonStates()
        }
        
        binding.btnCalculateRoute.setOnClickListener {
            calculateRoute()
        }
        
        binding.btnStartNavigation.setOnClickListener {
            if (isNavigating) {
                stopNavigation()
            } else {
                startNavigation()
            }
        }
        
        binding.btnReset.setOnClickListener {
            resetAll()
        }
        
        updateButtonStates()
    }
    
    private fun setOrigin(point: GeoPoint) {
        originPoint = point
        
        // Remove old marker
        originMarker?.let { map.overlays.remove(it) }
        
        // Add new marker
        originMarker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Origin"
            snippet = String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)
        }
        map.overlays.add(originMarker)
        map.invalidate()
        
        binding.tvOrigin.text = String.format(Locale.US, "Origin: %.4f, %.4f", 
            point.latitude, point.longitude)
        
        updateButtonStates()
    }
    
    private fun setDestination(point: GeoPoint) {
        destinationPoint = point
        
        // Remove old marker
        destinationMarker?.let { map.overlays.remove(it) }
        
        // Add new marker
        destinationMarker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Destination"
            snippet = String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)
        }
        map.overlays.add(destinationMarker)
        map.invalidate()
        
        binding.tvDestination.text = String.format(Locale.US, "Destination: %.4f, %.4f", 
            point.latitude, point.longitude)
        
        updateButtonStates()
    }
    
    private fun calculateRoute() {
        val origin = originPoint ?: return
        val destination = destinationPoint ?: return
        
        binding.tvStatus.text = "Calculating route..."
        binding.btnCalculateRoute.isEnabled = false
        
        lifecycleScope.launch {
            val result = routeManager.calculateRoute(origin, destination)
            
            result.onSuccess { route ->
                currentRoute = route
                displayRoute(route)
                
                val distanceKm = route.totalDistance / 1000
                val timeMin = route.estimatedTime / 60
                binding.tvStatus.text = String.format(Locale.US, 
                    "Route: %.1f km, ~%d min", distanceKm, timeMin)
                
                updateButtonStates()
            }
            
            result.onFailure { error ->
                binding.tvStatus.text = "Route error: ${error.message}"
                Toast.makeText(this@MainActivity, 
                    "Failed to calculate route: ${error.message}", 
                    Toast.LENGTH_LONG).show()
            }
            
            binding.btnCalculateRoute.isEnabled = true
        }
    }
    
    private fun displayRoute(route: Route) {
        // Remove old route
        routePolyline?.let { map.overlays.remove(it) }
        
        // Draw new route
        routePolyline = Polyline().apply {
            setPoints(route.points.map { it.toGeoPoint() })
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, 
                android.R.color.holo_blue_dark)
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(routePolyline)
        
        // Zoom to fit route
        val boundingBox = routePolyline!!.bounds
        map.zoomToBoundingBox(boundingBox, true, 100)
        
        map.invalidate()
    }
    
    private fun startNavigation() {
        val route = currentRoute ?: return
        
        isNavigating = true
        binding.btnStartNavigation.text = "Stop Navigation"
        binding.tvStatus.text = "Navigation active - IMU tracking"
        
        // Initialize dead reckoning
        val initialHeading = mapMatcher.getHeadingAtDistance(route, 0.0)
        deadReckoning.initialize(route, initialHeading)
        
        // Create position marker
        positionMarker?.let { map.overlays.remove(it) }
        positionMarker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Current Position"
            // Use a blue circle as position indicator
            setTextIcon("📍")
        }
        map.overlays.add(positionMarker)
        
        // Start collecting sensor data
        sensorJob = lifecycleScope.launch {
            imuProcessor.startSensorStream().collectLatest { reading ->
                val position = deadReckoning.processReading(reading, imuProcessor)
                updatePositionDisplay(position)
                
                // Check if navigation is complete
                if (deadReckoning.isNavigationComplete()) {
                    binding.tvStatus.text = "Destination reached!"
                }
            }
        }
        
        updateButtonStates()
    }
    
    private fun stopNavigation() {
        isNavigating = false
        sensorJob?.cancel()
        sensorJob = null
        
        binding.btnStartNavigation.text = "Start Navigation"
        binding.tvStatus.text = "Navigation stopped"
        
        updateButtonStates()
    }
    
    private fun updatePositionDisplay(position: EstimatedPosition) {
        runOnUiThread {
            // Update marker position
            positionMarker?.position = position.toGeoPoint()
            
            // Update info display
            val speedKmh = position.speed * 3.6
            val remainingM = deadReckoning.getRemainingDistance()
            val remainingKm = remainingM / 1000
            
            binding.tvSpeed.text = String.format(Locale.US, "Speed: %.1f km/h", speedKmh)
            binding.tvDistance.text = String.format(Locale.US, "Remaining: %.2f km", remainingKm)
            binding.tvConfidence.text = String.format(Locale.US, "Confidence: %.0f%%", 
                position.confidence * 100)
            
            // Optionally center map on position
            if (isNavigating) {
                map.controller.animateTo(position.toGeoPoint())
            }
            
            map.invalidate()
        }
    }
    
    private fun resetAll() {
        stopNavigation()
        
        originPoint = null
        destinationPoint = null
        currentRoute = null
        
        // Clear overlays
        originMarker?.let { map.overlays.remove(it) }
        destinationMarker?.let { map.overlays.remove(it) }
        routePolyline?.let { map.overlays.remove(it) }
        positionMarker?.let { map.overlays.remove(it) }
        
        originMarker = null
        destinationMarker = null
        routePolyline = null
        positionMarker = null
        
        // Reset UI
        binding.tvOrigin.text = "Origin: Not set"
        binding.tvDestination.text = "Destination: Not set"
        binding.tvStatus.text = "Tap 'Set Origin' to begin"
        binding.tvSpeed.text = "Speed: --"
        binding.tvDistance.text = "Remaining: --"
        binding.tvConfidence.text = "Confidence: --"
        
        map.invalidate()
        updateButtonStates()
    }
    
    private fun updateButtonStates() {
        val hasOrigin = originPoint != null
        val hasDestination = destinationPoint != null
        val hasRoute = currentRoute != null
        
        binding.btnSetOrigin.isEnabled = !isNavigating
        binding.btnSetDestination.isEnabled = !isNavigating && hasOrigin
        binding.btnCalculateRoute.isEnabled = !isNavigating && hasOrigin && hasDestination
        binding.btnStartNavigation.isEnabled = hasRoute
        binding.btnStartNavigation.text = if (isNavigating) "Stop Navigation" else "Start Navigation"
        
        // Highlight active selection mode
        binding.btnSetOrigin.alpha = if (selectionMode == SelectionMode.ORIGIN) 0.5f else 1.0f
        binding.btnSetDestination.alpha = if (selectionMode == SelectionMode.DESTINATION) 0.5f else 1.0f
    }
    
    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 
                REQUEST_PERMISSIONS)
        }
    }
    
    override fun onResume() {
        super.onResume()
        map.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        map.onPause()
        
        // Stop navigation when app goes to background
        if (isNavigating) {
            stopNavigation()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        sensorJob?.cancel()
    }
}
