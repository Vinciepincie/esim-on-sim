## What's new

- **Fixed: empty card with root on device with built-in eSIM.** When access is set via root the app would detect the phone's built-in eSIM chip instead of an card, causing it to look empty. (Bug introduced in version 0.5.)
- **More cards in the card switcher:** a second card that only answers to its maker's own ID now shows up, and on rooted phones cards found through the SIM reader stay listed and are found faster on refresh.
- **More card info:** Details now shows the card's own brand name, such as "5ber", once your phone has seen the card with none of its eSIMs on, who made the card, and how much of the card's own RAM is free.

## Download

On your Android phone, tap the one that fits. Both are the same app, and you can ignore the other files below.

- **Google Play broken, or no Google on your phone:** **[eSIM-On-SIM.apk](https://github.com/{{repo}}/releases/download/{{tag}}/eSIM-On-SIM.apk)**. It has its own QR scanner, which asks for camera access. It works on every phone, so take this one if you're not sure.
- **Working Google Play:** you can also use **[eSIM-On-SIM-google.apk](https://github.com/{{repo}}/releases/download/{{tag}}/eSIM-On-SIM-google.apk)**. It scans QR codes with Google's scanner, so it doesn't need camera access.

## Install

Android blocks apps from outside the Play Store until you allow them, so it asks once:

1. Open the downloaded file.
2. Android says it can't install apps from this source. Tap **Settings**, turn on **Allow from this source**, then go back.
3. Tap **Install**.

Android 6 or 7, can't find the setting, or got a Play Protect warning? See the [full install guide](https://github.com/{{repo}}#install).

Already have the app? Install this version the same way. Your eSIMs are on the card, and your setup stays.
