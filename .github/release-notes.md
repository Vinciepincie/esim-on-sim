## What's new

- **More cards through root, Shizuku, and Wireless or USB debugging.** If one of these couldn't open your card before, the app now tries two more things before it gives up:
  - It asks for the card's eSIM manager by its full ID, for cards that don't answer to the shorter one the app normally uses.
  - On Android 14 and newer, it can also try the phone's SIM reader directly. That may work on rooted phones, and on a few phones without root. Not every phone has it.
- **Nothing changes where the app already works.** The app only tries these after the usual steps, so a card that opened before opens the same way. There's nothing new to choose under Card access.

These two tries haven't been tested on a real phone yet. If this version gets your card working, or it still won't open, please [tell us how it went](https://github.com/{{repo}}/issues). Say which Android version your phone has and how the app reaches your card: root, Shizuku, or Wireless or USB debugging.

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
