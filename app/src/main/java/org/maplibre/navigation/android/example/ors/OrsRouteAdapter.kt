package org.maplibre.navigation.android.example.ors

import org.maplibre.geojson.utils.PolylineUtils
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.LegStep
import org.maplibre.navigation.core.models.ManeuverModifier
import org.maplibre.navigation.core.models.RouteLeg
import org.maplibre.navigation.core.models.StepManeuver
import org.nitri.ors.OrsClient
import org.nitri.ors.domain.profile.Profile
import org.nitri.ors.domain.route.RouteRequest
import org.nitri.ors.domain.route.RouteResponse

/**
 * ORS -> MapLibre DirectionsRoute adapter for the example app.
 *
 * The example app must call [OrsClient] for HTTP and then convert the response
 * to [DirectionsRoute] for Navigation UI rendering.
 */
object OrsRouteAdapter {

    private const val POLYLINE_PRECISION = 5

    data class ManeuverHint(
        val type: StepManeuver.Type,
        val modifier: ManeuverModifier.Type? = null,
    )

    fun orsTypeToMaplibre(orsType: Int): ManeuverHint = when (orsType) {
        0 -> ManeuverHint(type = StepManeuver.Type.TURN, modifier = ManeuverModifier.Type.LEFT)
        1 -> ManeuverHint(type = StepManeuver.Type.TURN, modifier = ManeuverModifier.Type.RIGHT)
        2 -> ManeuverHint(type = StepManeuver.Type.TURN, modifier = ManeuverModifier.Type.SHARP_LEFT)
        3 -> ManeuverHint(type = StepManeuver.Type.TURN, modifier = ManeuverModifier.Type.SHARP_RIGHT)
        4 -> ManeuverHint(type = StepManeuver.Type.TURN, modifier = ManeuverModifier.Type.SLIGHT_LEFT)
        5 -> ManeuverHint(type = StepManeuver.Type.TURN, modifier = ManeuverModifier.Type.SLIGHT_RIGHT)
        6 -> ManeuverHint(type = StepManeuver.Type.CONTINUE, modifier = ManeuverModifier.Type.STRAIGHT)
        7 -> ManeuverHint(type = StepManeuver.Type.ROUNDABOUT)
        8 -> ManeuverHint(type = StepManeuver.Type.ROUNDABOUT)
        9 -> ManeuverHint(type = StepManeuver.Type.TURN, modifier = ManeuverModifier.Type.UTURN)
        10 -> ManeuverHint(type = StepManeuver.Type.ARRIVE)
        11 -> ManeuverHint(type = StepManeuver.Type.DEPART)
        12 -> ManeuverHint(type = StepManeuver.Type.FORK, modifier = ManeuverModifier.Type.SLIGHT_LEFT)
        13 -> ManeuverHint(type = StepManeuver.Type.FORK, modifier = ManeuverModifier.Type.SLIGHT_RIGHT)
        else -> ManeuverHint(type = StepManeuver.Type.TURN)
    }

    suspend fun fetchDirectionsRoute(
        ors: OrsClient,
        profile: Profile,
        request: RouteRequest,
    ): DirectionsRoute {
        val response: RouteResponse = ors.getRoute(profile, request)
        return convert(response)
    }

    fun convert(response: RouteResponse): DirectionsRoute {
        val orsRoute = response.routes.firstOrNull()
            ?: error("ORS RouteResponse.routes is empty")
        val encodedGeometry = orsRoute.geometry
            ?: error("ORS route geometry is null (request must include geometry)")

        val routePoints = PolylineUtils.decode(encodedGeometry, POLYLINE_PRECISION)
        require(routePoints.isNotEmpty()) { "ORS route geometry decoded to no points" }

        val legs = orsRoute.segments.map { segment ->
            val steps = segment.steps.map { step ->
                val wp0 = step.wayPoints.firstOrNull() ?: 0
                val wp1 = step.wayPoints.getOrNull(1) ?: wp0

                val maneuverPoint = routePoints.getOrNull(wp0) ?: routePoints.first()
                val hint = orsTypeToMaplibre(step.type)
                val stepGeometry =
                    if (wp1 > wp0 && wp1 < routePoints.size) {
                        val slice = routePoints.subList(wp0, wp1 + 1)
                        PolylineUtils.encode(slice, POLYLINE_PRECISION)
                    } else {
                        encodedGeometry
                    }

                LegStep(
                    geometry = stepGeometry,
                    distance = step.distance,
                    duration = step.duration,
                    name = step.name,
                    maneuver = StepManeuver(
                        location = maneuverPoint,
                        bearingBefore = 0.0,
                        bearingAfter = 0.0,
                        instruction = step.instruction,
                        type = hint.type,
                        modifier = hint.modifier,
                    ),
                )
            }

            RouteLeg(
                distance = steps.sumOf { it.distance },
                duration = steps.sumOf { it.duration },
                steps = steps,
            )
        }

        return DirectionsRoute(
            geometry = encodedGeometry,
            legs = legs,
            distance = orsRoute.summary.distance,
            duration = orsRoute.summary.duration,
        )
    }
}
