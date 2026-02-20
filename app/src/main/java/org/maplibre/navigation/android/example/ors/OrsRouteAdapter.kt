package org.maplibre.navigation.android.example.ors

import org.maplibre.geojson.utils.PolylineUtils
import org.maplibre.navigation.core.models.BannerInstructions
import org.maplibre.navigation.core.models.BannerText
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.LegStep
import org.maplibre.navigation.core.models.ManeuverModifier
import org.maplibre.navigation.core.models.RouteLeg
import org.maplibre.navigation.core.models.StepIntersection
import org.maplibre.navigation.core.models.StepManeuver
import org.nitri.ors.OrsClient
import org.nitri.ors.Profile
import org.nitri.ors.domain.route.RouteRequest
import org.nitri.ors.domain.route.RouteResponse

/**
 * ORS -> MapLibre DirectionsRoute adapter for the example app.
 *
 * The example app must call [OrsClient] for HTTP and then convert the response
 * to [DirectionsRoute] for Navigation UI rendering.
 */
object OrsRouteAdapter {

    private const val ORS_POLYLINE_PRECISION = 5
    private const val MAPLIBRE_POLYLINE_PRECISION = 6

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
            ?: return DirectionsRoute(geometry = "", legs = emptyList(), distance = 0.0, duration = 0.0)
            ?: error("ORS RouteResponse.routes is empty")
        val encodedGeometry = orsRoute.geometry
            ?: return DirectionsRoute(geometry = "", legs = emptyList(), distance = 0.0, duration = 0.0)
            ?: error("ORS route geometry is null (request must include geometry)")


        val routePoints = PolylineUtils.decode(encodedGeometry, ORS_POLYLINE_PRECISION)
        if (routePoints.isEmpty()) {
            return DirectionsRoute(geometry = "", legs = emptyList(), distance = 0.0, duration = 0.0)
        }
        require(routePoints.isNotEmpty()) { "ORS route geometry decoded to no points" }
        val mapLibreGeometry = PolylineUtils.encode(routePoints, MAPLIBRE_POLYLINE_PRECISION)

        val legs = orsRoute.segments.map { segment ->
            val mappedSteps = segment.steps.map { step ->
                val wp0 = step.wayPoints.firstOrNull() ?: 0
                val wp1 = step.wayPoints.getOrNull(1) ?: wp0

                val maneuverPoint = routePoints.getOrNull(wp0) ?: routePoints.first()
                val hint = orsTypeToMaplibre(step.type)
                val stepGeometry =
                    if (wp1 > wp0 && wp1 < routePoints.size) {
                        val slice = routePoints.subList(wp0, wp1 + 1)
                        PolylineUtils.encode(slice, MAPLIBRE_POLYLINE_PRECISION)
                    } else {
                        mapLibreGeometry
                    }

                LegStep(
                    geometry = stepGeometry,
                    distance = step.distance,
                    duration = step.duration,
                    name = step.name,
                    bannerInstructions = listOf(
                        BannerInstructions(
                            distanceAlongGeometry = step.distance,
                            primary = BannerText(
                                text = step.instruction,
                                type = hint.type,
                                modifier = hint.modifier,
                            ),
                        )
                    ),
                    intersections = listOf(
                        StepIntersection(
                            location = maneuverPoint,
                            geometryIndex = wp0,
                        )
                    ),
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

            val steps = if (mappedSteps.isNotEmpty()) {
                mappedSteps
            } else {
                listOf(
                    LegStep(
                        geometry = mapLibreGeometry,
                        distance = segment.distance,
                        duration = segment.duration,
                        bannerInstructions = listOf(
                            BannerInstructions(
                                distanceAlongGeometry = segment.distance,
                                primary = BannerText(
                                    text = "Continue",
                                    type = StepManeuver.Type.DEPART,
                                    modifier = ManeuverModifier.Type.STRAIGHT,
                                ),
                            )
                        ),
                        intersections = listOf(
                            StepIntersection(
                                location = routePoints.first(),
                                geometryIndex = 0,
                            )
                        ),
                        maneuver = StepManeuver(
                            location = routePoints.first(),
                            bearingBefore = 0.0,
                            bearingAfter = 0.0,
                            instruction = "Continue",
                            type = StepManeuver.Type.DEPART,
                            modifier = ManeuverModifier.Type.STRAIGHT,
                        ),
                    )
                )
            }

            RouteLeg(
                distance = steps.sumOf { it.distance } ?: 0.0,
                duration = steps.sumOf { it.duration } ?: 0.0,
                steps = steps,
            )
        }

        return DirectionsRoute(
            geometry = mapLibreGeometry,
            legs = legs,
            distance = orsRoute.summary.distance,
            duration = orsRoute.summary.duration,
        )
    }
}
