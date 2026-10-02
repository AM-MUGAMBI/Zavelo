# Zavelo

Accounts with a 9-digit key, live chat, emojis, voice notes, files up to 10 GB, voice calls and video calls.

## Run
Needs Java 17+ and Maven.

    mvn spring-boot:run

Open http://localhost:8080

To keep accounts and messages from an older copy, copy its `data` folder into this folder before starting.

## Test
Use a normal window and a private/incognito window with two accounts.
Microphone and camera only work on http://localhost or on HTTPS.
To test video, use two devices if you can: most browsers will not give the same camera to two windows.

## Files
Images, videos, audio and documents up to 10 GB are sent in 8 MB pieces, so a dropped connection
only repeats one piece. Files are stored in ./data/files. Make sure the disk has room.

## Calls over the internet
STUN (free, already set) is enough for testing on one network.
For real users behind strict networks, add a TURN server (for example coturn) and fill in
zavelo.ice.turn.* in src/main/resources/application.properties.

## Deploy to Render
1. Push this folder to a GitHub repository.
2. In Render: New > Blueprint, pick the repository. Render reads render.yaml and creates the app and database.
3. When the build finishes, open the https address Render gives you.
Settings for the free database, disk size and plan are in render.yaml.

## Install as an app
Zavelo is a web app you can install. Open it over https (or localhost), then:
- Android / Chrome / Edge: Settings > Install, or use the install button in the address bar.
- iPhone / iPad: Share > Add to Home Screen.
The icon files are in static/icons. To change the icon, replace static/icon.svg and the PNG files.

## App lock
Settings > App lock: PIN or password, plus fingerprint or face unlock when the device offers it.
The lock is stored on the device only and works as a privacy screen over your chats.
