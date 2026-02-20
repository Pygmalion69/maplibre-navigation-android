package org.maplibre.navigation.android.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.geojson.model.Point
import org.maplibre.geojson.turf.TurfMeasurement
import org.maplibre.geojson.turf.TurfUnit
import org.maplibre.navigation.android.example.databinding.ActivityNavigationUiBinding
import org.maplibre.navigation.android.example.ors.OrsRouteAdapter
import org.maplibre.navigation.android.navigation.ui.v5.NavigationLauncher
import org.maplibre.navigation.android.navigation.ui.v5.NavigationLauncherOptions
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationMapRoute
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.RouteOptions
import org.nitri.ors.Ors
import org.nitri.ors.OrsClient
import org.nitri.ors.Profile
import org.nitri.ors.domain.route.RouteRequest
import timber.log.Timber
import java.util.Locale
import java.util.UUID

class OpenRouteServiceNavigationActivity :
    AppCompatActivity(),
    OnMapReadyCallback,
    MapLibreMap.OnMapClickListener {
    private lateinit var mapLibreMap: MapLibreMap

    private var language = Locale.getDefault().language
    private var route: DirectionsRoute? = null
    private var navigationMapRoute: NavigationMapRoute? = null
    private var destination: Point? = null
    private var locationComponent: LocationComponent? = null
    private lateinit var orsClient: OrsClient

    private lateinit var binding: ActivityNavigationUiBinding

    private var simulateRoute = false
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        binding = ActivityNavigationUiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mapView.apply {
            onCreate(savedInstanceState)
            getMapAsync(this@OpenRouteServiceNavigationActivity)
        }

        val apiKey = getString(R.string.ors_api_key)
        if (apiKey.contains("YOUR_ORS_API_KEY")) {
            Snackbar.make(
                findViewById(R.id.container),
                "Set ORS_API_KEY in your environment to enable routing.",
                Snackbar.LENGTH_LONG,
            ).show()
        }
        orsClient = Ors.create(apiKey, this)

        binding.startRouteButton.setOnClickListener {
            route?.let { route ->
                val userLocation = mapLibreMap.locationComponent.lastKnownLocation ?: return@let
                val options = NavigationLauncherOptions.builder()
                    .directionsRoute(route)
                    .shouldSimulateRoute(simulateRoute)
                    .initialMapCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(userLocation.latitude, userLocation.longitude)).build()
                    )
                    .lightThemeResId(R.style.TestNavigationViewLight)
                    .darkThemeResId(R.style.TestNavigationViewDark)
                    .build()
                NavigationLauncher.startNavigation(this@OpenRouteServiceNavigationActivity, options)
            }
        }

        binding.simulateRouteSwitch.setOnCheckedChangeListener { _, checked ->
            simulateRoute = checked
        }

        binding.clearPoints.setOnClickListener {
            if (::mapLibreMap.isInitialized) {
                mapLibreMap.markers.forEach {
                    mapLibreMap.removeMarker(it)
                }
            }
            destination = null
            it.visibility = View.GONE
            binding.startRouteLayout.visibility = View.GONE

            navigationMapRoute?.removeRoute()
        }
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        this.mapLibreMap = mapLibreMap
        mapLibreMap.setStyle(
            Style.Builder().fromUri(getString(R.string.map_style_light))
        ) { style ->
            enableLocationComponent(style)
            navigationMapRoute = NavigationMapRoute(binding.mapView, mapLibreMap)
            mapLibreMap.addOnMapClickListener(this)

            Snackbar.make(
                findViewById(R.id.container),
                "Tap map to place destination",
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        locationComponent = mapLibreMap.locationComponent
        locationComponent?.let {
            it.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build(),
            )
            it.isLocationComponentEnabled = true
            it.cameraMode = CameraMode.TRACKING_GPS_NORTH
            it.renderMode = RenderMode.NORMAL
        }
    }

    override fun onMapClick(point: LatLng): Boolean {
        destination = Point(point.longitude, point.latitude)

        mapLibreMap.addMarker(MarkerOptions().position(point))
        binding.clearPoints.visibility = View.VISIBLE
        calculateRoute()
        return true
    }

    private fun calculateRoute() {
        binding.startRouteLayout.visibility = View.GONE
        val userLocation = mapLibreMap.locationComponent.lastKnownLocation
        val destination = destination
        if (userLocation == null) {
            Timber.d("calculateRoute: User location is null, therefore, origin can't be set.")
            return
        }

        if (destination == null) {
            Timber.d("calculateRoute: destination is null, therefore, destination can't be set.")
            return
        }

        val origin = Point(userLocation.longitude, userLocation.latitude)
        if (TurfMeasurement.distance(origin, destination, TurfUnit.METRES) < 50) {
            Timber.d("calculateRoute: distance < 50 m")
            binding.startRouteButton.visibility = View.GONE
            return
        }

        val request = RouteRequest(
            coordinates = listOf(
                listOf(origin.longitude, origin.latitude),
                listOf(destination.longitude, destination.latitude),
            ),
        )

        ioScope.launch {
            try {
                val directionsRoute = OrsRouteAdapter.fetchDirectionsRoute(
                    ors = orsClient,
                    profile = Profile.DRIVING_CAR,
                    request = request,
                )
                val routeWithOptions = directionsRoute.copy(
                    routeOptions = RouteOptions(
                        baseUrl = "https://api.openrouteservice.org",
                        profile = "driving",
                        user = "openrouteservice",
                        accessToken = "openrouteservice",
                        voiceInstructions = true,
                        bannerInstructions = true,
                        steps = true,
                        geometries = "polyline6",
                        language = language,
                        coordinates = listOf(origin, destination),
                        requestUuid = UUID.randomUUID().toString(),
                    ),
                )

                withContext(Dispatchers.Main) {
                    route = routeWithOptions
                    navigationMapRoute?.addRoutes(listOf(routeWithOptions))
                    binding.startRouteLayout.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Timber.e(e, "calculateRoute: ORS route failed")
                withContext(Dispatchers.Main) {
                    Snackbar.make(
                        findViewById(R.id.container),
                        "Route error: ${e.message}",
                        Snackbar.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mapLibreMap.isInitialized) {
            mapLibreMap.removeOnMapClickListener(this)
        }
        ioScope.cancel()
        binding.mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}
