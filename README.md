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
2. In Render: New > Blueprint, pick the repository. Render reads render.yaml and creates the app, the
   PostgreSQL database and the storage disk. (Or create them by hand, see below.)
3. When the build finishes, open the https address Render gives you.

### Setting it up by hand
- Web service: runtime Docker. The Dockerfile already switches the app to production mode.
- Database: New > PostgreSQL. Copy its **Internal Database URL**.
- On the web service, add one environment variable: `DATABASE_URL` = that URL.
  (The older DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD values still work if you prefer them.)
- Add a disk mounted at `/var/data` (needs a paid plan) so voice notes and files survive restarts.
- If no database is set, the app refuses to start and says so in the logs. That is on purpose: without a
  database, accounts live in a temporary file that is wiped every time the server restarts.

### Staying signed in
Logins are saved in the database and the login cookie lasts about 400 days (the most browsers allow), so
restarts and redeploys do not sign anyone out.

### The "starting your service" page
Render's free web services go to sleep after 15 minutes without visitors, and the first visit afterwards
shows Render's own "starting" page for up to a minute. Zavelo cannot remove that page on the free plan.
- A paid web service (Starter) never sleeps.
- Once the app is installed on a phone, it opens from its saved copy instead and shows "Waking up Zavelo"
  while it reconnects.

## Install as an app
Zavelo is a web app you can install. Open it over https (or localhost), then:
- Android / Chrome / Edge: Settings > Install, or use the install button in the address bar.
- iPhone / iPad: Share > Add to Home Screen.
The icon files are in static/icons. To change the icon, replace static/icon.svg and the PNG files.

## App lock
Settings > App lock: PIN or password, plus fingerprint or face unlock when the device offers it.
The lock is stored on the device only and works as a privacy screen over your chats.

## Notifications (messages and calls)

Zavelo sends Web Push notifications, so your phone is told about a new message or an incoming call even when Zavelo is closed.

- Each person turns them on once, on each device: **Settings → Notifications → Turn on notifications** (or the "Turn on" banner at the top of the chat list).
- **iPhone/iPad:** notifications only work after you add Zavelo to the Home Screen (Share → Add to Home Screen) and open it from there (iOS 16.4 or newer).
- The server makes its notification signing keys the first time it starts and keeps them in the database. Nothing to configure. Optionally set `PUSH_SUBJECT` (e.g. `mailto:you@example.com`) to give push services a contact address.
- A call notification stays on screen with vibration. Tapping it opens Zavelo and the call (held for 45 seconds) appears with Answer/Decline. A browser notification cannot loop a phone ringtone the way a native phone app can.
- On the free Render plan the server sleeps when idle; a sleeping server cannot send notifications until someone's activity wakes it. The Starter plan keeps it awake.
