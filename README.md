# Street View Navigation for the Visually Impaired (All Walk)

## Description

This Android application harnesses the power of Google Street View, Google Maps, and Google Directions combined with Geofy's geocoding capabilities to provide a unique navigation experience tailored for the visually impaired. The app offers a plethora of voice prompts to ensure safe and accurate navigation.

## Features

- **Google Street View Recognition**: Utilizes ARCore to offer real-time street view recognition.
- **Voice Prompts**: Multiple voice prompts guide users through their journey, making navigation simpler and more accessible.
- **Geofy Geocoding**: Makes use of Geofy's API for both geocoding and reverse geocoding.
- **Google Direction and Maps**: For reliable map views and route directions.

## Process

The app operates at a high frame rate of 30 frames per second, and each of these frames undergoes comprehensive processing to ensure real-time feedback for the user:

- **Direction Recognition**: Each frame evaluates and recognizes the user's current direction.
- **Location Tracking**: The application identifies the user's current latitude and longitude with each frame.
- **Road Verification**: Once every second, the app calculates if the user remains on the correct path.
- **OCR Functionality**: OCR (Optical Character Recognition) operates on every frame, ensuring that any visible text in the user's surroundings is promptly detected and processed.

## API keys

This repository does not contain API keys. The app reads them from `local.properties`, which Git ignores. Copy `local.properties.example` to `local.properties` and fill in the values below. Do not commit that file.

You need a Google key to run navigation. A Geoapify key is only needed if you choose Geoapify for destination search in Settings. Google Places works with the Google key alone.

### Google key (`MAPS_API_KEY`)

Used for ARCore Geospatial localization, the map, walking directions, and Google Places search.

1. Open [Google Cloud Console](https://console.cloud.google.com/) and create or select a project.
2. Turn on billing for that project. Maps, Directions, and Places will not respond without it.
3. Enable these APIs: [ARCore API](https://console.cloud.google.com/apis/library/arcore.googleapis.com), [Maps SDK for Android](https://console.cloud.google.com/apis/library/maps-android-backend.googleapis.com), [Directions API](https://console.cloud.google.com/apis/library/directions-backend.googleapis.com), and [Places API](https://console.cloud.google.com/apis/library/places-backend.googleapis.com).
4. Go to [APIs & Services → Credentials](https://console.cloud.google.com/apis/credentials), create an API key, and paste it as `MAPS_API_KEY`.
5. Restrict the key to those APIs, and to this Android app (`com.google.ar.core.codelabs.hellogeospatial`) plus your debug or release certificate, so a leaked key cannot be reused.

### Geoapify key (`GEOAPIFY_API_KEY`)

Used only for Geoapify destination search and reverse geocoding.

1. Create a free account at [Geoapify](https://www.geoapify.com/).
2. Open the [Geoapify dashboard](https://myprojects.geoapify.com/) and copy the project API key.
3. Paste it as `GEOAPIFY_API_KEY`.

## How to set up

1. Clone this repository.
2. Open the project in Android Studio so it can write `sdk.dir` into `local.properties`.
3. Add `MAPS_API_KEY` and, if you want Geoapify search, `GEOAPIFY_API_KEY` to that same file.
4. Run the app on a physical Android phone with Google Play Services for AR. The Geospatial API does not work on an emulator.

## Contributing

If you'd like to contribute, please fork the repository and use a feature branch. Pull requests are warmly welcome.

## Licensing
   
The code in this project is licensed under MIT license.
