package com.imunavigator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.imunavigator.databinding.ActivityMainBinding
import com.imunavigator.model.*
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
        
        // Initialize osmdroid
        Configuration.getInstance().load(applicationContext, 
            getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize components
        imuProcessor = IMUProcessor(this)
        deadReckoning = DeadReckoning()
        routeManager = RouteManager()
        
        // Check sensors
        if (!imuProcessor.hasRequiredSensors()) {
            AlertDialog.Builder(this)
                .setTitle("Sensors Not Available")
                .setMessage("This device needs accelerometer and gyroscope sensors.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .show()
            return
        }
        
        setupMap()
        setupButtons()
        requestPermissions()
        
        binding.tvSensorInfo.text = imuProcessor.getSensorInfo()
    }
    
    private fun setupMap() {
        map = binding.mapView
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        
        val mapController = map.controller
        mapController.setZoom(15.0)
        
        // Default to Moscow
        val startPoint = GeoPoint(55.7558, 37.6173)
        mapController.setCenter(startPoint)
        
        // Handle map taps
        map.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent?, mapView: MapView?): Boolean {
                if (e == null || mapView == null) return false
                
                val geoPoint = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                
                when (selectionMode) {
                    SelectionMode.ORIGIN -> {
                        setOrigin(geoPoint)
                        selectionMode = SelectionMode.NONE
                    }
                    SelectionMode.DESTINATION -> {
                        setDestination(geoPoint)
                        selectionMode = SelectionMode.NONE
                    }
                    SelectionMode.NONE -> {}
                }
                updateButtonStates()
                return true
            }
        })
    }
    
    private fun setupButtons() {
        binding.btnSetOrigin.setOnClickListener {
            selectionMode = SelectionMode.ORIGIN
            Toast.makeText(this, "Tap map to set origin", Toast.LENGTH_SHORT).show()
            updateButtonStates()
        }
        
        binding.btnSetDestination.setOnClickListener {
            selectionMode = SelectionMode.DESTINATION
            Toast.makeText(this, "Tap map to set destination", Toast.LENGTH_SHORT).show()
            updateButtonStates()
        }
        
        binding.btnCalculateRoute.setOnClickListener { calculateRoute() }
        
        binding.btnStartNavigation.setOnClickListener {
            if (isNavigating) stopNavigation() else startNavigation()
        }
        
        binding.btnReset.setOnClickListener { resetAll() }
        
        updateButtonStates()
    }
    
    private fun setOrigin(point: GeoPoint) {
        originPoint = point
        originMarker?.let { map.overlays.remove(it) }
        originMarker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Start"
        }
        map.overlays.add(originMarker)
        map.invalidate()
        binding.tvOrigin.text = String.format(Locale.US, "Start: %.4f, %.4f", point.latitude, point.longitude)
    }
    
    private fun setDestination(point: GeoPoint) {
        destinationPoint = point
        destinationMarker?.let { map.overlays.remove(it) }
        destinationMarker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "End"
        }
        map.overlays.add(destinationMarker)
        map.invalidate()
        binding.tvDestination.text = String.format(Locale.US, "End: %.4f, %.4f", point.latitude, point.longitude)
    }
    
    private fun calculateRoute() {
        val origin = originPoint ?: return
        val destination = destinationPoint ?: return
        
        binding.tvStatus.text = "Calculating route..."
        binding.btnCalculateRoute.isEnabled = false
        
        lifecycleScope.launch {
            routeManager.calculateRoute(origin, destination).onSuccess { route ->
                currentRoute = route
                displayRoute(route)
                binding.tvStatus.text = String.format(Locale.US, 
                    "Route: %.1f km", route.totalDistance / 1000)
                updateButtonStates()
            }.onFailure { error ->
                binding.tvStatus.text = "Route error: ${error.message}"
                Toast.makeText(this@MainActivity, "Failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
            binding.btnCalculateRoute.isEnabled = true
        }
    }
    
    private fun displayRoute(route: Route) {
        routePolyline?.let { map.overlays.remove(it) }
        routePolyline = Polyline().apply {
            setPoints(route.points.map { it.toGeoPoint() })
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_dark)
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(routePolyline)
        map.zoomToBoundingBox(routePolyline!!.bounds, true, 100)
        map.invalidate()
    }
    
    private fun startNavigation() {
        val route = currentRoute ?: return
        
        isNavigating = true
        binding.btnStartNavigation.text = "Stop"
        binding.tvStatus.text = "Navigating - IMU speed tracking"
        
        // Initialize dead reckoning (1D along route)
        deadReckoning.initialize(route, 0.0)
        
        // Create position marker
        positionMarker?.let { map.overlays.remove(it) }
        positionMarker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "You"
            setTextIcon("📍")
        }
        map.overlays.add(positionMarker)
        
        // Start sensor collection
        sensorJob = lifecycleScope.launch {
            imuProcessor.startSensorStream().collectLatest { data ->
                // Update orientation if available
                data.rotationVector?.let { deadReckoning.updateOrientation(it) }
                
                // Use linear acceleration if available, otherwise raw accelerometer
                val accel = data.linearAcceleration ?: data.accelerometer
                
                // Process IMU and get position
                val position = deadReckoning.processReading(
                    data.timestamp,
                    accel,
                    data.gyroscope
                )
                
                updatePositionDisplay(position)
                
                if (deadReckoning.isNavigationComplete()) {
                    binding.tvStatus.text = "🎉 Destination reached!"
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
            positionMarker?.position = position.toGeoPoint()
            
            val speedKmh = position.speed * 3.6
            val remainingKm = deadReckoning.getRemainingDistance() / 1000
            
            binding.tvSpeed.text = String.format(Locale.US, "%.0f km/h", speedKmh)
            binding.tvDistance.text = String.format(Locale.US, "%.2f km left", remainingKm)
            binding.tvConfidence.text = String.format(Locale.US, "%.0f%%", position.confidence * 100)
            
            // Follow position on map
            map.controller.animateTo(position.toGeoPoint())
            map.invalidate()
        }
    }
    
    private fun resetAll() {
        stopNavigation()
        originPoint = null
        destinationPoint = null
        currentRoute = null
        
        listOf(originMarker, destinationMarker, routePolyline, positionMarker).forEach {
            it?.let { overlay -> map.overlays.remove(overlay) }
        }
        originMarker = null
        destinationMarker = null
        routePolyline = null
        positionMarker = null
        
        binding.tvOrigin.text = "Start: Not set"
        binding.tvDestination.text = "End: Not set"
        binding.tvStatus.text = "Tap 'Set Origin' to begin"
        binding.tvSpeed.text = "--"
        binding.tvDistance.text = "--"
        binding.tvConfidence.text = "--"
        
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
        binding.btnStartNavigation.text = if (isNavigating) "Stop" else "Start Navigation"
    }
    
    private fun requestPermissions() {
        val permissions = arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE)
        val missing = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }
    
    override fun onResume() {
        super.onResume()
        map.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        map.onPause()
        if (isNavigating) stopNavigation()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        sensorJob?.cancel()
    }
}
