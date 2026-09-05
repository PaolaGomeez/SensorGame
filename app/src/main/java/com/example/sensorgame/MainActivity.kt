package com.example.sensorgame

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

private const val BALL_SIZE = 60

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SensorGameScreen()
                }
            }
        }
    }
}

@Composable
fun SensorGameScreen() {
    val density = LocalDensity.current
    val ballPx = with(density) { BALL_SIZE.dp.toPx() }

    var ballX by remember { mutableFloatStateOf(0f) }
    var ballY by remember { mutableFloatStateOf(0f) }

    var areaWidthPx by remember { mutableFloatStateOf(0f) }
    var areaHeightPx by remember { mutableFloatStateOf(0f) }
    var score by remember { mutableIntStateOf(0) }
    var gameOver by remember { mutableStateOf(false) }
    var gpsText by remember { mutableStateOf("") }

    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val gyroscope = remember { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    val hasGyroscope = gyroscope != null

    var gyroX by remember { mutableFloatStateOf(0f) }
    var gyroY by remember { mutableFloatStateOf(0f) }

    val baseSpeed = 5f

    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    var lastShakeTime by remember { mutableStateOf(0L) }
    val shakeThreshold = 12f
    val shakeCooldownMs = 1500L

    var colorIndex by remember { mutableIntStateOf(0) }
    val ballColors = remember {
        listOf(Color.Blue, Color.Red, Color.Green, Color.Magenta, Color.Yellow)
    }

    val starPx = with(density) { 32.dp.toPx() }
    val starCount = 5
    val stars = remember { mutableStateListOf<Offset>() }

    val fusedLocation = remember { LocationServices.getFusedLocationProviderClient(context) }
    var lastLocation by remember { mutableStateOf<Location?>(null) }
    val rewardDistanceM = 10f
    val scope = rememberCoroutineScope()
    var gpsButtonEnable by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (!hasGyroscope) Toast.makeText(context, "No gyroscope — use touch controls", Toast.LENGTH_LONG).show()
    }

    fun checkWin() {
        if (score >= 50 && !gameOver) {
            gameOver = true
        }
    }

    fun spawnStars() {
        stars.clear()
        val bottomMarginPX = with(density) { 120.dp.toPx() }
        val safeHeight = (areaHeightPx - bottomMarginPX).coerceAtLeast(starPx * 2)
        repeat(starCount) {
            val x = Random.nextFloat() * (areaWidthPx - starPx)
            val y = Random.nextFloat() * (safeHeight - starPx)
            stars.add(Offset(x, y))
        }
    }

    fun resetGame() {
        score = 0
        gameOver = false
        ballX = (areaWidthPx - ballPx) / 2f
        ballY = (areaHeightPx - ballPx) / 2f
        spawnStars()
    }

    fun moveBallWithGyro() {
        if (!hasGyroscope || gameOver) return
        ballX = (ballX + gyroY * baseSpeed).coerceIn(0f, areaWidthPx - ballPx)
        ballY = (ballY + gyroX * baseSpeed).coerceIn(0f, areaHeightPx - ballPx)
    }
    fun checkStarCollisions() {
        if (gameOver) return
        val ballRight = ballX + ballPx
        val ballBottom = ballY + ballPx
        val collected = mutableListOf<Offset>()
        for (star in stars) {
            if (ballX < star.x + starPx && ballRight > star.x &&
                ballY < star.y + starPx && ballBottom > star.y) {
                collected.add(star)
            }
        }
        for (star in collected) {
            stars.remove(star)
            score += 10
            checkWin()
        }
        if (stars.isEmpty() && areaWidthPx > 0f) spawnStars()
    }

    fun onNewLocation(location: Location) {
        if (gameOver) return
        gpsText = "Lat: ${"%.5f".format(location.latitude)}, Lon: ${"%.5f".format(location.longitude)}"

        if (lastLocation != null) {
            val distanceMoved = lastLocation!!.distanceTo(location)
            if (distanceMoved >= rewardDistanceM) {
                score += 10
                checkWin()
                Toast.makeText(context, "Moved ${distanceMoved.toInt()}m! +10 pts", Toast.LENGTH_SHORT).show()
                lastLocation = location
            }
        } else {
            lastLocation = location
        }
    }

    val locationCallback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onNewLocation(it) }
            }
        }
    }

    fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        try {
            fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            gpsText = "Location Unavailable"
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLocationUpdates() else gpsText = "Location Permission Denied"
    }

    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) startLocationUpdates() else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun onShakeDetected() {
        if (gameOver) return
        colorIndex = (colorIndex + 1) % ballColors.size
        Toast.makeText(context, "Shake detected!", Toast.LENGTH_SHORT).show()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> {
                        gyroX = event.values[0]
                        gyroY = event.values[1]
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val ax = event.values[0]
                        val ay = event.values[1]
                        val az = event.values[2]
                        val gVector = sqrt(ax * ax + ay * ay + az * az)
                        val netAcceleration = gVector - SensorManager.GRAVITY_EARTH

                        val now = System.currentTimeMillis()
                        if (netAcceleration > shakeThreshold && now - lastShakeTime > shakeCooldownMs) {
                            lastShakeTime = now
                            onShakeDetected()
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    gyroscope?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
                    accelerometer?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    sensorManager.unregisterListener(listener)
                    fusedLocation.removeLocationUpdates(locationCallback)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            sensorManager.unregisterListener(listener)
            fusedLocation.removeLocationUpdates(locationCallback)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(33L)
            moveBallWithGyro()
            checkStarCollisions()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                if (areaWidthPx == 0f) {
                    areaWidthPx = coords.size.width.toFloat()
                    areaHeightPx = coords.size.height.toFloat()
                    ballX = (areaWidthPx - ballPx) / 2f
                    ballY = (areaHeightPx - ballPx) / 2f
                    spawnStars()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    if (!gameOver) {
                        change.consume()
                        ballX = (ballX + dragAmount.x).coerceIn(0f, areaWidthPx - ballPx)
                        ballY = (ballY + dragAmount.y).coerceIn(0f, areaHeightPx - ballPx)
                    }
                }
            }
    ) {
        Text(
            text = gpsText,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
        )

        Text(
            text = "Score: $score / 50",
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(ballX.roundToInt(), ballY.roundToInt()) }
                .size(BALL_SIZE.dp)
                .background(ballColors[colorIndex], CircleShape)
        )

        stars.forEach { starPos ->
            Image(
                painter = painterResource(R.drawable.star_shape),
                contentDescription = null,
                modifier = Modifier
                    .offset { IntOffset(starPos.x.roundToInt(), starPos.y.roundToInt()) }
                    .size(32.dp)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = { onShakeDetected() }, enabled = !gameOver) {
                Text("Shake")
            }
            Button(
                onClick = {
                    gpsButtonEnable = false
                    val base = Location("test").apply { latitude = 34.0522; longitude = -118.2437 }
                    val current = Location("test").apply { latitude = 34.0523; longitude = -118.2437 }
                    lastLocation = base
                    onNewLocation(current)
                    scope.launch { delay(5000L); gpsButtonEnable = true }
                },
                enabled = gpsButtonEnable && !gameOver
            ) {
                Text("GPS + 10")
            }
        }

        if (gameOver) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("You Win!") },
                text = { Text("You reached 50 points. Congratulations!") },
                confirmButton = {
                    Button(onClick = { resetGame() }) {
                        Text("Play Again")
                    }
                }
            )
        }
    }
}