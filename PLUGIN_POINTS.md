# SoftAlarm: what is FAKE now, and where to plug in the real things

IMPORTANT: right now every voice is FREE (your 12 voices x 4 languages), so the whole "Unlock Pro" system is DORMANT:
the Unlock button, the sign-in prompts and the payment screens stay hidden. They appear by themselves as soon as you mark
at least one voice as Pro (in Voices.kt change the third value of that line from false to true).
To try the fake payment flow below, temporarily mark one voice as Pro.

The app's "Unlock Pro" journey works today with a FAKE payment server (no internet, no money).
Polar and Firebase are NOT called by the new payment code. Plug them in later at these points.

## What is fake right now
- FakeBackend.kt        pretend payment server. It confirms a payment on the 2nd check, like a real webhook delay.
- FakeProGateway.kt     answers after a short delay, like a network.
- The "Test checkout" pop-up stands in for Polar's secure payment page.
- ProConfig.USE_FAKE_PAYMENTS = true is the switch.

## Plug-in point 1: the Firebase sign-in token  (RealProGateway.kt, bottom of the file)
The server needs to know WHO is paying. The app proves it with a Firebase ID token.
1. Add the Firebase Auth library and your google-services.json yourself.
2. In RealProGateway.kt copy the FirebaseTokenProvider class from the comment into the file.
3. In ProGatewayFactory replace  PlaceholderTokenProvider()  with  FirebaseTokenProvider().

## Plug-in point 2: switch to real payments  (ProConfig.kt)
  USE_FAKE_PAYMENTS = false
  SERVER_URL = "https://your-server"      (the backend-stub server, see PAYMENTS_SETUP.md)
  PRICE_LABEL = "$1"                      (only the text on the button; the real price is in your Polar product)
No Polar or Stripe library is needed in the app: Polar's payment page is just a web address the server gives the app.

## Plug-in point 3: the server  (backend-stub/, see PAYMENTS_SETUP.md)
Needs your Polar token, product id, webhook secret and Firebase service account. Nothing secret goes in the app.

## Plug-in point 4: sign-in so Pro survives a new phone  (AccountGateways.kt)
Everyone starts as a silent guest. Signing in (Google or email, optional) turns the guest into a permanent account
and keeps the purchase, because Firebase "linking" keeps the same user id.
1. Add Firebase Auth, turn on the Google and Email providers in the Firebase console.
2. In AccountGateways.kt copy the FirebaseAccountGateway class from the comment (the Google / email pop-up itself is the part you add).
3. In AccountGatewayFactory replace PlaceholderAccountGateway() with your FirebaseAccountGateway.
Until then: fake sign-in pop-ups are used in test mode, and the real build shows "Sign-in is not set up yet".

## Already done for you
- The softalarm://paid link (AndroidManifest.xml) brings the user back to the app after paying.
- The app checks the payment several times after the user returns, because the confirmation can take a few seconds.
- Pro is only switched OFF when the server says the payment was REFUNDED. A server hiccup, being offline, or a new guest id can never take Pro away.
- "Restore on a new phone" and the Account screen: sign in, check, unlock.
- A guest who already paid is asked before switching to a different existing account (that Pro would not follow them).
- Right after the first purchase the app offers (once) to sign in so Pro is safe.
- Alarms that use a Pro voice show "🔒 Pro" while Pro is locked, and ring with the phone tone instead.

## Note about Firebase from Step 4
Firebase libraries and CloudSync.kt are still in the project from Step 4 (silent sign-in, user record).
CloudSync.kt is the only file that uses them. If you prefer to add Firebase yourself, delete CloudSync.kt,
the Firebase lines in app/build.gradle.kts, and the two calls to CloudSync in MainActivity.kt, and tell me.

## Phone test of the FAKE flow (5 minutes)
1. Alarm tab > tap "★ Unlock Pro voices" > "Unlock Pro ($1)".
2. A "Test checkout" pop-up appears > "Pay $1". The orange box says "Checking your payment... (1 of 4)", then "(2 of 4)", then
   "Thank you! Pro is unlocked". The 🔒 signs disappear from the voice list.
3. Unlock dialog > "Reset test purchase": Pro voices lock again.
4. Try again but tap "Card declined" (or Close): after the checks you see "We could not see your payment yet...". Tap the box to check again.
5. Deep link: open the address softalarm://paid (type it in the browser's address bar or tap it in a note). The app opens and checks.
6. Pick a locked voice while not Pro: the unlock dialog opens and your previous choice is kept.

### Privacy and delete account (fake mode)
13. Account & restore > "Delete my account" > Delete: "Your account and its data were deleted." You are a guest again and Pro is locked.
14. "Privacy policy" says the server address is not set yet (it opens your /privacy page once SERVER_URL is set).

### Restore / new phone / refund (fake mode)
7. After unlocking, a "Keep your Pro safe" box appears once. Choose Google > alex@example.com. The orange box says
   "Signed in as alex@example.com. Your Pro is saved to this account."
8. Alarm tab > "👤 Account & restore" > "Test: pretend I have a new phone": Pro locks, you are a guest again.
9. Unlock dialog > "Restore on a new phone" > Google > alex@example.com: "Pro restored for alex@example.com". Voices unlock.
10. Try Google > sam@example.com instead: "No purchase found for sam@example.com...".
11. Account > "Test: pretend my payment was refunded": "Your Pro was removed because the payment was refunded." Voices lock.
12. Pay as a guest, then sign in with sam@example.com (an account that already exists after step 10): the app asks
    "Switch account?" because your guest Pro would not follow you. "Stay" changes nothing.

## Automatic tests
GitHub runs "kotlin-unit-tests" (60 tests: payment flow, restore flow, refund sync, delete account, server replies, fake backend) and the server tests (52) on every push.
