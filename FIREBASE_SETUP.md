# Firebase setup (5 minutes)

1. Firebase console > your project > Project settings > Your apps > Android app.
   The package name must be exactly:  com.softalarm.app
2. Download `google-services.json` and put it at:  app/google-services.json  (inside the repo, next to app/build.gradle.kts)
3. Build > Authentication > Get started > Sign-in method > turn ON "Anonymous".
4. Build > Firestore Database > Create database (production mode, pick the region closest to your users).
5. Firestore > Rules: paste the contents of `firestore.rules` and press Publish.
6. Test the rules in the Rules Playground (Firestore > Rules > Rules playground):
   - get  /users/ABC  signed in as uid ABC      -> ALLOWED
   - get  /users/ABC  signed in as uid XYZ      -> DENIED
   - create /users/ABC with {pro: true}         -> DENIED   (nobody can give themselves Pro)
   - create /users/ABC with {createdAt, appVersion, lastSeen} -> ALLOWED
   - update /users/ABC changing "pro"           -> DENIED
7. Push to GitHub. The build checks the file's package name for you.

Without the file the app still builds and works; it just shows "Cloud: not set up yet".
Never put the Firebase *service account* key (the secret admin JSON) in the repo or the app. It is only for the payment server (Step 5).
