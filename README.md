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
