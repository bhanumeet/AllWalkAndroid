package com.google.ar.core.codelabs.hellogeospatial

data class GeoResponse(
    val geocoded_waypoints: List<GeocodedWaypoint>,
    val routes: List<Route>
)

data class GeocodedWaypoint(
    val geocoder_status: String,
    val place_id: String,
    val types: List<String>
)

data class Route(
    val bounds: Bounds,
    val copyrights: String,
    val legs: List<Leg>
)

data class Bounds(
    val northeast: Location,
    val southwest: Location
)

data class Leg(
    val distance: Distance,
    val duration: Duration,
    val end_address: String,
    val end_location: Location,
    val start_address: String,
    val start_location: Location,
    val steps: List<Step>
)

data class Location(
    val lat: Double,
    val lng: Double
)

data class Distance(
    val text: String,
    val value: Int
)

data class Duration(
    val text: String,
    val value: Int
)

data class Step(
    val distance: Distance,
    val duration: Duration,
    val start_location: Location,
    val end_location: Location,
    val travel_mode: String,
    val html_instructions: String,
    val polyline: Polyline
)

data class Polyline(
    val points: String
)

data class GeoCodingResponse(
    val results: List<Result>
)

data class Result(
    val postcode: String
)

data class Root(
    val results: List<GeocodingResult>,
)

data class GeocodingResult(
    val name: String,
    val country: String,
    val country_code: String,
    val state: String,
    val county: String,
    val city: String,
    val postcode: String,
    val suburb: String,
    val street: String,
    val housenumber: String,
    val lon: Double,
    val lat: Double,
    val state_code: String,
    val formatted: String,
    val address_line1: String,
    val address_line2: String,
    val category: String,
    val result_type: String,
    val place_id: String

)