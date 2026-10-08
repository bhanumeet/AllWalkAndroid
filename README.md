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

## API Usage

- **Google**: Street View, Maps, Places, and Directions share one API key.
- **Geoapify**: Geocode and reverse geocode.

Keys are not stored in this repository. Put them in `local.properties` (see `local.properties.example`).

## How to Set Up

1. Clone the repository to your local machine.
2. Copy `local.properties.example` to `local.properties` and fill in `sdk.dir`, `MAPS_API_KEY`, and `GEOAPIFY_API_KEY`.
3. Follow the build instructions for Android to compile and install the application on your device.

## Contributing

If you'd like to contribute, please fork the repository and use a feature branch. Pull requests are warmly welcome.

## Licensing
   
The code in this project is licensed under MIT license.
