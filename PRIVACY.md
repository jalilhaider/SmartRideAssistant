# Privacy policy and permissions – Smart Ride Assistant

Personal-use app. No accounts, no analytics, no ads, no developer server.

## What the app reads
- **Screen content** while you have pressed *Start Monitoring* and accepted Android's capture dialog. Frames are processed in
  memory by on-device OCR (Google ML Kit, bundled model) and discarded. Screenshots are never saved or uploaded.
- **Your location** (GPS/network) to know where you are on your route.

## What leaves the phone
Only these requests, over HTTPS, with no personal identifiers and no images:
| Service | Data sent | Purpose |
|---|---|---|
| OpenStreetMap Nominatim | address text (home, office, pickup, destination) | convert text to coordinates |
| OSRM routing server (default: router.project-osrm.org; configurable) | route coordinates, including your current position | road distances |

Both operators can see your IP address and these coordinates under their own policies. Use your own OSRM server to avoid the public one.

## What is stored (on this phone only)
- Settings: home/office text and coordinates, detour limit, voice options.
- History (max 300 rows): time, fare, pickup, destination, classification, extra distance. No passenger names, ratings or images.
- *Settings ▸ Clear local history* deletes the history. Uninstalling removes everything (`allowBackup` is off).

## What the app never does
No inDrive login or credentials · no inDrive private API · no network interception or reverse engineering · no tapping,
accepting or bidding · no bypass of Android capture restrictions or protected windows · no silent capture restart ·
no screenshot upload.

## Permissions
| Permission | Why |
|---|---|
| Screen capture (system dialog via MediaProjection) | read the ride-offer screen; asked every session |
| `FOREGROUND_SERVICE`, `…_MEDIA_PROJECTION`, `…_LOCATION` | required to keep capture and GPS running legally in the background |
| `POST_NOTIFICATIONS` | the persistent "analysing your screen" notice with a Stop button |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | current position for the detour calculation |
| `INTERNET` | geocoding and routing requests above |

## Your responsibility
inDrive's terms of service and local law may restrict automated reading of its app. Check them before using this tool.

Map data © OpenStreetMap contributors.
