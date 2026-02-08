package com.imunavigator.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Collects and processes raw IMU sensor data.
 * Provides accelerometer, gyroscope, and rotation vector data.
 */
class IMUProcessor(context: Context) {
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    
    /**
     * Check if required sensors are available
     */
    fun hasRequiredSensors(): Boolean {
        return accelerometer != null && gyroscope != null
    }
    
    /**
     * Get sensor information for debugging
     */
    fun getSensorInfo(): String {
        val sb = StringBuilder()
        accelerometer?.let {
            sb.append("Accel: ${it.name}\n")
        }
        gyroscope?.let {
            sb.append("Gyro: ${it.name}\n")
        }
        rotationVector?.let {
            sb.append("RotVec: ✓\n")
        }
        linearAcceleration?.let {
            sb.append("LinAccel: ✓")
        }
        return sb.toString()
    }
    
    /**
     * Combined sensor data from all IMU sensors
     */
    data class SensorData(
        val timestamp: Long,
        val accelerometer: FloatArray,
        val gyroscope: FloatArray,
        val rotationVector: FloatArray?, // May be null if not available
        val linearAcceleration: FloatArray? // May be null if not available
    )
    
    /**
     * Start streaming combined sensor data
     */
    fun startSensorStream(): Flow<SensorData> = callbackFlow {
        // Storage for latest readings
        var lastAccel: FloatArray? = null
        var lastAccelTime: Long = 0
        var lastGyro: FloatArray? = null
        var lastGyroTime: Long = 0
        var lastRotVec: FloatArray? = null
        var lastLinAccel: FloatArray? = null
        
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAccel = event.values.copyOf()
                        lastAccelTime = event.timestamp
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        lastGyro = event.values.copyOf()
                        lastGyroTime = event.timestamp
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        lastRotVec = event.values.copyOf()
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        lastLinAccel = event.values.copyOf()
                    }
                }
                
                // Emit when we have both accel and gyro
                val accel = lastAccel
                val gyro = lastGyro
                if (accel != null && gyro != null) {
                    val data = SensorData(
                        timestamp = maxOf(lastAccelTime, lastGyroTime),
                        accelerometer = accel,
                        gyroscope = gyro,
                        rotationVector = lastRotVec,
                        linearAcceleration = lastLinAccel
                    )
                    trySend(data)
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        
        // Register all sensors at fastest rate
        val rate = SensorManager.SENSOR_DELAY_FASTEST
        sensorManager.registerListener(listener, accelerometer, rate)
        sensorManager.registerListener(listener, gyroscope, rate)
        rotationVector?.let { sensorManager.registerListener(listener, it, rate) }
        linearAcceleration?.let { sensorManager.registerListener(listener, it, rate) }
        
        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
