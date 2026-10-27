# What is left for you (in order)

## A. Get a green build (do this first)
1. Upload the whole project to your repo root. Delete the old folder `app/src/main/java/com/example/` and any old `android.yml`.
2. Push. In the GitHub "Actions" tab three jobs run: `build`, `kotlin-unit-tests`, `server-tests`.
3. If anything is red, copy the red error lines to me. Nobody has compiled this code yet, so expect a small fix or two.
4. Download the APK from the finished run (Artifacts > SoftAlarm-debug-apk) and install it.

## B. Add the voice files
5. Put your mp3 files in `app/src/main/assets/audio/`. The names are listed in `README.txt` there:
   mother.mp3, mother_pa.mp3, mother_ur.mp3, mother_hi.mp3, father.mp3 ... child_baby_hi.mp3 (48) + default_file.mp3.
6. The `build` job prints how many files it found. Missing files simply play the phone's alarm tone.

## C. Test on your phone
7. Alarm tab: allow every permission until the yellow bar is gone. Tap "Test alarm in 10 seconds" and lock the phone.
8. Follow the checklist at the end of `STORE_READINESS.md` (section 8).

## D. Only when you want to SELL Pro voices (not needed now: every voice is free)
9. Mark at least one voice as Pro in `Voices.kt` (third value true). The Unlock button then appears.
10. Follow `PAYMENTS_SETUP.md`: Polar account, $1 product, access token, webhook, host for `backend-stub/`.
11. Follow `FIREBASE_SETUP.md`: your `google-services.json` in `app/`, turn on Anonymous sign-in, publish `firestore.rules`.
12. In `ProConfig.kt` set USE_FAKE_PAYMENTS = false and SERVER_URL. Plug in the Firebase token provider and sign-in
    (the commented code in `RealProGateway.kt` and `AccountGateways.kt`).

## E. Publish
13. Server settings CONTACT_EMAIL and PUBLISHER_NAME, then read /privacy and /delete-account.
14. Create the signing key, add the 4 GitHub secrets, push a tag like v1.0.0 (see `STORE_READINESS.md`).
15. Google Play also needs the API 36 upgrade ("Step 8b") and, for paid unlocks, Google Play Billing.
