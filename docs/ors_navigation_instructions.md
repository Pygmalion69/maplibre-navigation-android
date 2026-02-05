# Extending MapLibre Navigation Android with OpenRouteService

The MapLibre **maplibre‑navigation‑android** example app includes `NavigationUiActivity` (which uses Mapbox/Valhalla), `GraphHopperNavigationActivity` and `ValhallaNavigationActivity`.  Both GraphHopper and Valhalla offer endpoints that return routes in the OSRM/Mapbox JSON format, so the example activities convert the response to `DirectionsRoute` with `DirectionsResponse.fromJson()` and can feed that into MapLibre’s `NavigationLauncher`【141666587210485†L171-L228】.  

OpenRouteService (ORS) does **not** currently provide routes in the Mapbox/OSRM format.  ORS returns its own JSON structure with a `routes` list containing route `segments`, `steps` and an encoded `geometry` polyline【931019807304375†L37-L46】.  A post on ORS’s forum explains that there is no built‑in conversion to Mapbox’s format—you would have to implement the conversion yourself【931019807304375†L37-L46】.  Therefore, an ORS‑based navigation activity cannot directly use the MapLibre `NavigationLauncher`.  Instead, you can calculate a route using the **ors‑android‑client** library and draw the returned polyline on the map, then display the step‑by‑step instructions yourself.

## 1 Add the ORS client dependency

The [`ors‑android‑client`](https://github.com/Pygmalion69/ors-android-client) library wraps the ORS API using Retrofit.  To use it, add the Maven repository and dependency in your `app/build.gradle` (Kotlin DSL is shown, but the snippets can be adapted to Groovy):

```kotlin
repositories {
    // GitHub Packages repository.  Requires a PAT via GPR_USER and GPR_TOKEN
    maven {
        url = uri("https://maven.pkg.github.com/Pygmalion69/ors-android-client")
        credentials {
            username = System.getenv("GPR_USER")  // GitHub username
            password = System.getenv("GPR_TOKEN") // Personal access token
        }
    }
    // or use JitPack (no PAT required)
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("org.nitri.ors:ors-android-client:<latest-version>")
    // If using JitPack:
    // implementation("com.github.Pygmalion69:ors-android-client:<latest-version>")
}
```

The library’s README shows how to create a client and request a route:

- Create the client with your ORS API key:
  ```kotlin
  val ors = Ors.create("<your_api_key>", context)
  ```【151126906048440†L44-L50】

- Build a `RouteRequest` with coordinates, then call `ors.getRoute(Profile.DRIVING_CAR, request)`【151126906048440†L53-L62】.  Alternatively, use the DSL helpers or `RouteHelper`【151126906048440†L66-L85】.

Store your API key securely (e.g., in `local.properties` or via encrypted secrets) and never hard‑code it in the activity.

## 2 Create `OpenRouteServiceNavigationActivity`

1. **Add a new activity file** (e.g., `OpenRouteServiceNavigationActivity.kt`) under `app/src/main/java/com/example/…`.  Use `GraphHopperNavigationActivity` as a template for MapLibre setup and user‑interaction code.

2. **Extend `AppCompatActivity` and implement `OnMapReadyCallback` and `MapClickListener`**.  In `onCreate()` inflate the layout (e.g., reuse `activity_navigation_map`), obtain the `MapView`, set its `Lifecycle` listeners and call `getMapAsync(this)`.

3. **Initialize the map and location component** inside `onMapReady()`:

   ```kotlin
   override fun onMapReady(map: MapLibreMap) {
       mapView = binding.mapView
       this.map = map
       map.setStyle(Style.MAPBOX_STREETS) {
           enableLocationComponent(style)
       }
       // register map click listener
       map.addOnMapClickListener(this)
       // create a PolylineAnnotationManager to draw the route later
       val annotationApi = mapView.annotations
       polylineAnnotationManager = annotationApi.createPolylineAnnotationManager()
   }
   ```

   Use MapLibre’s location component similar to the GraphHopper example to show the user’s current position【141666587210485†L171-L228】.

4. **Handle map clicks** in `onMapClick(Point)`.  The first click defines the start of the route; the second click defines the destination.  When both are set, call a `calculateRoute()` method.

## 3 Calculate a route using the ORS client

Inside `calculateRoute()`:

1. **Build a `RouteRequest`:** The ORS API expects a list of coordinate pairs in the order [longitude, latitude].  For example:
   ```kotlin
   val coordinates = listOf(
       listOf(start.longitude, start.latitude),
       listOf(destination.longitude, destination.latitude)
   )
   val request = RouteRequest(coordinates = coordinates)
   ```

2. **Call the ORS service on a background thread/coroutine**:

   ```kotlin
   CoroutineScope(Dispatchers.IO).launch {
       try {
           val routeResponse = ors.getRoute(Profile.DRIVING_CAR, request)
           withContext(Dispatchers.Main) {
               onRouteRetrieved(routeResponse)
           }
       } catch (e: Exception) {
           withContext(Dispatchers.Main) {
               Toast.makeText(context, "Routing failed: ${'$'}{e.message}", Toast.LENGTH_LONG).show()
           }
       }
   }
   ```

   The `getRoute()` function returns a `RouteResponse` whose `routes` list contains segments and an encoded `geometry` polyline【931019807304375†L37-L46】.  The ORS client performs the HTTP request internally; you do **not** need to craft your own OkHttp call.

## 4 Decode the geometry and draw the route

The ORS route geometry is an [encoded polyline](https://developers.google.com/maps/documentation/utilities/polylinealgorithm) with 5‑decimal precision (OSRMv5).  MapLibre provides a utility class `PolylineUtils` that can decode a polyline into a list of `Point` objects:

```kotlin
val points: List<Point> = PolylineUtils.decode(geometry, 5) // precision 5【700924146225747†L103-L115】
```

Once decoded, convert each `Point` to a `PolylineAnnotationOptions` and add it via the `PolylineAnnotationManager` created earlier:

```kotlin
val polylineOptions = PolylineAnnotationOptions()
    .withPoints(points)
    .withLineColor(Color.parseColor("#ff6200ee"))
    .withLineWidth(6.0)
polylineAnnotationManager.create(polylineOptions)
```

Before drawing a new route, remember to clear existing annotations with `polylineAnnotationManager.deleteAll()`.  This mimics how the GraphHopper activity clears previous routes before drawing a new one【141666587210485†L171-L228】.

## 5 Show step‑by‑step navigation instructions

The ORS `RouteResponse` includes detailed instructions inside `segments[0].steps`.  Each step has properties such as `instruction` (human‑readable text), `way_points` (indices into the geometry where the step begins and ends), `distance` and `duration`.  Since MapLibre’s navigation UI expects a `DirectionsRoute`, you must present the instructions yourself.  A simple approach is:

1. Extract the first route and its first segment:

   ```kotlin
   val segment = routeResponse.routes[0].segments[0]
   val steps = segment.steps
   ```

2. Populate a `RecyclerView` or dialog with the step list.  For example:

   ```kotlin
   class StepAdapter(private val steps: List<Step>) : RecyclerView.Adapter<StepViewHolder>() {
       override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
           val view = LayoutInflater.from(parent.context)
               .inflate(R.layout.item_step, parent, false)
           return StepViewHolder(view)
       }
       override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
           val step = steps[position]
           holder.instructionView.text = step.instruction
           holder.distanceView.text = String.format("%.0f m", step.distance)
       }
       override fun getItemCount() = steps.size
   }
   ```

You may also integrate Android’s Text‑to‑Speech API to speak the instructions when the user reaches each step.


### ORS maneuver type mapping (ORS → Mapbox)

ORS steps provide an integer `type` (instruction type). MapLibre Navigation (via Mapbox Java models) expects a `StepManeuver` with:

- `type`: e.g. `"turn"`, `"depart"`, `"arrive"`, `"roundabout"`, `"continue"`, `"fork"`
- `modifier`: e.g. `"left"`, `"right"`, `"sharp left"`, `"slight right"`, `"straight"`, `"uturn"`

You can map ORS instruction types to Mapbox’s `type`/`modifier` like this:

| ORS code | ORS meaning | Mapbox `type` | Mapbox `modifier` |
|---:|---|---|---|
| 0 | Left | `turn` | `left` |
| 1 | Right | `turn` | `right` |
| 2 | Sharp left | `turn` | `sharp left` |
| 3 | Sharp right | `turn` | `sharp right` |
| 4 | Slight left | `turn` | `slight left` |
| 5 | Slight right | `turn` | `slight right` |
| 6 | Straight | `continue` | `straight` |
| 7 | Enter roundabout | `roundabout` | *(omit)* |
| 8 | Exit roundabout | `roundabout` | *(omit)* |
| 9 | U-turn | `turn` | `uturn` |
| 10 | Goal | `arrive` | *(omit)* |
| 11 | Depart | `depart` | *(omit or straight)* |
| 12 | Keep left | `fork` | `slight left` |
| 13 | Keep right | `fork` | `slight right` |

> Tip: If your MapLibre Navigation version does not accept `"continue"` or `"fork"` as maneuver types, fall back to `"turn"`.

### Adapter: ORS `RouteResponse` → Mapbox `DirectionsRoute`

Below is a copy‑paste ready adapter (single Kotlin file) that:
1) calls ORS via the **ors-android-client** (no direct HTTP in the app),
2) converts the ORS response into a Mapbox `DirectionsRoute` (`legs[]`, `steps[]`, `maneuver`),
3) feeds the route into the same launch path used by the other example activities.

Create:

- `app/src/main/java/org/maplibre/navigation/android/example/ors/OrsRouteAdapter.kt`

```kotlin
package org.maplibre.navigation.android.example.ors

import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import org.nitri.ors.OrsClient
import org.nitri.ors.domain.profile.Profile
import org.nitri.ors.domain.route.RouteRequest
import org.nitri.ors.domain.route.RouteResponse

/**
 * ORS -> Mapbox adapter for MapLibre Navigation example app.
 *
 * - The example app MUST NOT do direct HTTP; it only calls [OrsClient].
 * - This adapter converts ORS domain objects into a Mapbox [DirectionsRoute].
 *
 * Important:
 * - This is a pragmatic adapter: it focuses on route geometry + basic maneuvers.
 * - Advanced Mapbox fields (lanes, intersections, banners/voice) are not filled.
 */
object OrsRouteAdapter {

    /**
     * ORS polyline precision is commonly 5. Keep it consistent with the geometry you get.
     * If you decode+re-encode, keep using the same precision throughout.
     *
     * If you notice the drawn route is “shifted”, your precision likely mismatches.
     */
    private const val POLYLINE_PRECISION = 5

    data class ManeuverHint(val type: String, val modifier: String? = null)

    fun orsTypeToMapbox(orsType: Int): ManeuverHint = when (orsType) {
        0  -> ManeuverHint(type = "turn",      modifier = "left")
        1  -> ManeuverHint(type = "turn",      modifier = "right")
        2  -> ManeuverHint(type = "turn",      modifier = "sharp left")
        3  -> ManeuverHint(type = "turn",      modifier = "sharp right")
        4  -> ManeuverHint(type = "turn",      modifier = "slight left")
        5  -> ManeuverHint(type = "turn",      modifier = "slight right")
        6  -> ManeuverHint(type = "continue",  modifier = "straight")
        7  -> ManeuverHint(type = "roundabout")
        8  -> ManeuverHint(type = "roundabout")
        9  -> ManeuverHint(type = "turn",      modifier = "uturn")
        10 -> ManeuverHint(type = "arrive")
        11 -> ManeuverHint(type = "depart")
        12 -> ManeuverHint(type = "fork",      modifier = "slight left")
        13 -> ManeuverHint(type = "fork",      modifier = "slight right")
        else -> ManeuverHint(type = "turn")
    }

    /**
     * Fetch the ORS route (library handles HTTP) and convert to Mapbox DirectionsRoute.
     */
    suspend fun fetchDirectionsRoute(
        ors: OrsClient,
        profile: Profile,
        request: RouteRequest,
    ): DirectionsRoute {
        val response: RouteResponse = ors.getRoute(profile, request)
        return convert(response)
    }

    /**
     * Convert ORS RouteResponse into a Mapbox DirectionsRoute.
     *
     * The exact model field names come from ors-android-client domain classes:
     * - response.routes[0].summary.distance / duration
     * - response.routes[0].geometry (encoded polyline)
     * - response.routes[0].segments[].steps[] (instructions + waypoint indices)
     */
    fun convert(response: RouteResponse): DirectionsRoute {
        val orsRoute = response.routes.firstOrNull()
            ?: error("ORS RouteResponse.routes is empty")

        val encodedGeometry = orsRoute.geometry
            ?: error("ORS route geometry is null (request must include geometry)")

        val routePoints: List<Point> = PolylineUtils.decode(encodedGeometry, POLYLINE_PRECISION)

        val legs: List<RouteLeg> = orsRoute.segments.map { seg ->
            val steps: List<LegStep> = seg.steps.mapIndexed { stepIndex, step ->
                val wp0 = step.wayPoints.firstOrNull() ?: 0
                val wp1 = step.wayPoints.getOrNull(1) ?: wp0

                val maneuverPoint = routePoints.getOrNull(wp0) ?: routePoints.first()

                val hint = orsTypeToMapbox(step.type)

                val maneuverBuilder = StepManeuver.builder()
                    .instruction(step.instruction)
                    .location(listOf(maneuverPoint.longitude(), maneuverPoint.latitude()))
                    .type(hint.type)

                hint.modifier?.let { maneuverBuilder.modifier(it) }

                // Optional: step sub-geometry based on ORS step waypoints
                val stepGeometry: String? =
                    if (wp1 > wp0 && wp1 < routePoints.size) {
                        val slice = routePoints.subList(wp0, wp1 + 1)
                        PolylineUtils.encode(slice, POLYLINE_PRECISION)
                    } else null

                LegStep.builder()
                    .distance(step.distance)
                    .duration(step.duration)
                    .name(step.name ?: "")
                    .maneuver(maneuverBuilder.build())
                    .geometry(stepGeometry)
                    .build()
            }

            RouteLeg.builder()
                .distance(steps.sumOf { it.distance() ?: 0.0 })
                .duration(steps.sumOf { it.duration() ?: 0.0 })
                .steps(steps)
                .summary(seg.summary ?: "")
                .build()
        }

        return DirectionsRoute.builder()
            .distance(orsRoute.summary.distance)
            .duration(orsRoute.summary.duration)
            .geometry(encodedGeometry)
            .legs(legs)
            .build()
    }
}
```

In your `OpenRouteServiceNavigationActivity`, replace the “TODO conversion” call with:

```kotlin
val directionsRoute = OrsRouteAdapter.fetchDirectionsRoute(
    ors = orsClient,
    profile = Profile.DRIVING_CAR,
    request = routeRequest
)
```

…and then launch MapLibre Navigation exactly like the other example activities (same `NavigationLauncherOptions` path).



## 6 Limitations and alternatives

- **No MapLibre `NavigationLauncher` integration:** because ORS does not provide Mapbox/OSRM‑formatted responses, you cannot build a `DirectionsRoute` and launch MapLibre’s turn‑by‑turn navigation UI.  The ORS forum confirms there is no official converter【931019807304375†L37-L46】.  To achieve full turn‑by‑turn guidance, you would need to implement a converter yourself or continue using the GraphHopper or Valhalla activities.

- **Profiles:** ORS offers many profiles (`driving-car`, `cycling-regular`, `cycling-mountain` etc.).  Pass the appropriate `Profile` enum when calling `getRoute()`.

- **Offline support:** The ors‑android‑client requires network connectivity to fetch routes.  There is no offline routing support like GraphHopper’s offline mode.

## 7 Summary of key differences from GraphHopper and Valhalla examples

- **Routing call:** GraphHopper and Valhalla examples build custom OkHttp requests and request OSRM/Mapbox format (`type="mapbox"` or `format="osrm"`), then parse the JSON with `DirectionsResponse.fromJson()`【141666587210485†L171-L228】.  In contrast, the ORS activity uses the pre‑built `ors‑android‑client` to fetch a `RouteResponse`, so no manual HTTP call is written in the activity.

- **Displaying the route:** Because there is no `DirectionsRoute`, the ORS route is drawn as a simple polyline using `PolylineAnnotationManager` rather than using MapLibre’s `NavigationMapRoute`.

- **Navigation UI:** The MapLibre navigation UI is not used.  You manually present step instructions extracted from `segments.steps` and (optionally) handle TTS.

## 8 Putting it all together – skeleton Activity

Below is a condensed skeleton for `OpenRouteServiceNavigationActivity.kt`.  It demonstrates how to tie together the pieces described above.  This is not a drop‑in replacement; adjust the package name, imports and UI binding classes to match your project.

```kotlin
class OpenRouteServiceNavigationActivity : AppCompatActivity(), OnMapReadyCallback, MapClickListener {
    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private var startPoint: Point? = null
    private var destination: Point? = null
    private lateinit var ors: OrsClient
    private lateinit var polylineAnnotationManager: PolylineAnnotationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation_map)
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        // create ORS client with API key
        ors = Ors.create(BuildConfig.ORS_API_KEY, this)
    }

    override fun onMapReady(map: MapLibreMap) {
        this.map = map
        map.setStyle(Style.MAPBOX_STREETS) {
            // enable location component as in GraphHopperNavigationActivity
            enableLocationComponent(it)
        }
        map.addOnMapClickListener(this)
        polylineAnnotationManager = mapView.annotations.createPolylineAnnotationManager()
    }

    override fun onMapClick(point: Point): Boolean {
        if (startPoint == null) {
            startPoint = point
            Toast.makeText(this, "Start set", Toast.LENGTH_SHORT).show()
        } else if (destination == null) {
            destination = point
            Toast.makeText(this, "Destination set", Toast.LENGTH_SHORT).show()
            calculateRoute()
        } else {
            // reset
            startPoint = point
            destination = null
            polylineAnnotationManager.deleteAll()
            Toast.makeText(this, "Start reset", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun calculateRoute() {
        val start = startPoint ?: return
        val end = destination ?: return
        val request = RouteRequest(coordinates = listOf(
            listOf(start.longitude(), start.latitude()),
            listOf(end.longitude(), end.latitude())
        ))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = ors.getRoute(Profile.DRIVING_CAR, request)
                val firstRoute = response.routes.firstOrNull() ?: return@launch
                val geometry = firstRoute.geometry
                val points = PolylineUtils.decode(geometry, 5)

                withContext(Dispatchers.Main) {
                    // draw route
                    polylineAnnotationManager.deleteAll()
                    val options = PolylineAnnotationOptions()
                        .withPoints(points)
                        .withLineColor(Color.parseColor("#ee4f8f"))
                        .withLineWidth(6.0)
                    polylineAnnotationManager.create(options)

                    // show step instructions (optional)
                    val segment = firstRoute.segments.firstOrNull()
                    segment?.let { showStepDialog(it.steps) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OpenRouteServiceNavigationActivity, "Route error: ${'$'}{e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showStepDialog(steps: List<Step>) {
        // implement your preferred UI – e.g., a BottomSheet with RecyclerView
    }

    // remember to forward lifecycle methods to mapView (onStart, onResume, etc.)
}
```

After implementing this activity and registering it in your `AndroidManifest.xml`, build and run the example app.  Tap on the map to set a start point and then a destination; the ORS client will calculate the route, draw it as a polyline and show the step instructions.
