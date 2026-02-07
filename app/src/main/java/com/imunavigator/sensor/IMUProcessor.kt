package com.imunavigator.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.imunavigator.model.IMUReading

/**
 * Processes raw IMU sensor data from accelerometer and gyroscope.
 * Uses sensor fusion to estimate device orientation and acceleration in world frame.
 */
class IMUProcessor(context: Context) {
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    
    // Complementary filter parameters
    private val ALPHA = 0.98f // Weight for gyroscope in complementary filter
    
    // Current orientation (rotation matrix)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3) // azimuth, pitch, roll
    
    // Last readings for interpolation
    private var lastAccelReading: FloatArray? = null
    private var lastGyroReading: FloatArray? = null
    private var lastAccelTimestamp: Long = 0
    private var lastGyroTimestamp: Long = 0
    
    // Low-pass filter for accelerometer (remove noise)
    private val accelFiltered = FloatArray(3)
    private val ACCEL_FILTER_ALPHA = 0.8f
    
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
            sb.append("Accelerometer: ${it.name}, Resolution: ${it.resolution} m/s²\n")
        }
        gyroscope?.let {
            sb.append("Gyroscope: ${it.name}, Resolution: ${it.resolution} rad/s\n")
        }
        rotationVector?.let {
            sb.append("Rotation Vector: ${it.name}\n")
        }
        return sb.toString()
    }
    
    /**
     * Start streaming IMU readings as a Flow
     */
    fun startSensorStream(): Flow<IMUReading> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        // Low-pass filter to reduce noise
                        for (i in 0..2) {
                            accelFiltered[i] = ACCEL_FILTER_ALPHA * accelFiltered[i] + 
                                              (1 - ACCEL_FILTER_ALPHA) * event.values[i]
                        }
                        lastAccelReading = accelFiltered.copyOf()
                        lastAccelTimestamp = event.timestamp
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        lastGyroReading = event.values.copyOf()
                        lastGyroTimestamp = event.timestamp
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    }
                }
                
                // Emit combined reading when we have both sensors
                val accel = lastAccelReading
                val gyro = lastGyroReading
                if (accel != null && gyro != null) {
                    val reading = IMUReading(
                        timestamp = maxOf(lastAccelTimestamp, lastGyroTimestamp),
                        accelerometer = accel,
                        gyroscope = gyro
                    )
                    trySend(reading)
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Could log accuracy changes for debugging
            }
        }
        
        // Register sensors at maximum rate for best accuracy
        val samplingPeriod = SensorManager.SENSOR_DELAY_FASTEST
        sensorManager.registerListener(listener, accelerometer, samplingPeriod)
        sensorManager.registerListener(listener, gyroscope, samplingPeriod)
        rotationVector?.let {
            sensorManager.registerListener(listener, it, samplingPeriod)
        }
        
        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
    
    /**
     * Get current device heading (azimuth) in radians
     * 0 = North, positive = clockwise
     */
    fun getCurrentHeading(): Float = orientationAngles[0]
    
    /**
     * Transform acceleration from device frame to world frame
     * Removes gravity and returns acceleration in North-East-Down frame
     */
    fun transformToWorldFrame(deviceAccel: FloatArray): FloatArray {
        // If we have rotation matrix, use it to transform
        if (rotationMatrix[0] != 0f || rotationMatrix[4] != 0f || rotationMatrix[8] != 0f) {
            val worldAccel = FloatArray(3)
            // Multiply rotation matrix by acceleration vector
            worldAccel[0] = rotationMatrix[0] * deviceAccel[0] + 
                           rotationMatrix[1] * deviceAccel[1] + 
                           rotationMatrix[2] * deviceAccel[2]
            worldAccel[1] = rotationMatrix[3] * deviceAccel[0] + 
                           rotationMatrix[4] * deviceAccel[1] + 
                           rotationMatrix[5] * deviceAccel[2]
            worldAccel[2] = rotationMatrix[6] * deviceAccel[0] + 
                           rotationMatrix[7] * deviceAccel[1] + 
                           rotationMatrix[8] * deviceAccel[2]
            
            // Remove gravity (Z axis in world frame is down)
            worldAccel[2] -= SensorManager.GRAVITY_EARTH
            
            return worldAccel
        }
        
        // Fallback: just remove gravity from Z
        return floatArrayOf(deviceAccel[0], deviceAccel[1], deviceAccel[2] - SensorManager.GRAVITY_EARTH)
    }
}
