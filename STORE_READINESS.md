# SoftAlarm: store readiness guide

Not legal advice. The privacy policy is a DRAFT written from what the app really does: have it reviewed for the
countries where you publish.

## 1. What is ready now
- Privacy policy and delete-account pages, served by your server at /privacy and /delete-account.
- In-app "Privacy policy" and "Delete my account" (Alarm tab > Account & restore).
- App icon (adaptive + one-colour version for Android 13 themed icons), Play icon 512x512 and feature graphic (folder store/).
- Signed release build by GitHub (APK + AAB + checksums) when you push a version tag.
- Backup rules: alarms may be restored on a new phone; the cached Pro flag is not copied. Alarms are set again every time the app opens.
- Listing text: store/LISTING.md.

## 2. What is NOT ready (read before choosing where to publish)
- NO PRO VOICES YET. All 48 voices are free, so there is nothing to buy: payments, Polar and Play Billing do not matter until you add a Pro voice.
- GOOGLE PLAY TARGET LEVEL. As of 31 August 2026 Google Play requires new apps and updates to target Android 16 (API 36).
  SoftAlarm targets API 34, so Play would reject the upload. Moving to API 36 needs newer Android build tools and changes how the
  screens are drawn. I kept it as a separate, careful follow-up ("Step 8b") instead of changing it blind. Sideloading and other stores are not affected.
- GOOGLE PLAY BILLING. On Google Play, an in-app unlock of digital features must be sold through Google Play Billing, not through Polar's web page.
  Polar is fine for APKs you share yourself (website, GitHub Releases, other stores that allow it).
- PLAY PERMISSION FORMS. Play asks you to justify these: exact alarms (USE_EXACT_ALARM), full-screen intent, display over other apps,
  and the battery-optimisation exemption. SoftAlarm is a real alarm clock, which is the allowed use, but you must fill the forms.
- NEW DEVELOPER ACCOUNTS. Google has required a closed test with a minimum number of testers for some time before production for new personal accounts.
  Check the current rule in Play Console.

## 3. Create your signing key (once, on a computer)
The key proves that updates really come from you. Android only accepts an update signed with the SAME key.
LOSING THE KEY MEANS YOU CAN NEVER UPDATE THE APP. Anyone who gets the key can publish fake updates.

1. Install Java (JDK 17). In a terminal / command prompt run (change the names; it asks you for passwords):

     keytool -genkeypair -v -keystore softalarm-release.jks -alias softalarm -keyalg RSA -keysize 2048 -validity 10000

   Choose a long password for the keystore and use the same password for the key when asked. Remember the alias (softalarm).
2. Make two backups of softalarm-release.jks in different places (for example a USB stick and a password manager that stores files).
   Write the passwords in the password manager too. NEVER upload the .jks file or the passwords to GitHub or send them in chat.
3. Turn the file into one line of text (base64):
     Linux:    base64 -w0 softalarm-release.jks
     Mac:      base64 -i softalarm-release.jks | tr -d '\n'
     Windows (PowerShell):  [Convert]::ToBase64String([IO.File]::ReadAllBytes("softalarm-release.jks"))

## 4. Add 4 secrets in GitHub
Repo > Settings > Secrets and variables > Actions > New repository secret. Names must be exactly:
  KEYSTORE_BASE64     the long base64 text from step 3
  KEYSTORE_PASSWORD   the keystore password
  KEY_ALIAS           softalarm   (or the alias you chose)
  KEY_PASSWORD        the key password

## 5. Publish a release
1. On your computer or in GitHub: create a tag like v1.0.0 on the main branch.
     git tag v1.0.0 && git push origin v1.0.0       (GitHub website: Releases > Draft a new release > choose a new tag)
2. GitHub runs all tests, then the release job: it checks the tag and secrets, opens your keystore, builds the SIGNED APK and AAB,
   verifies the signature, writes SHA256 checksums and publishes a GitHub Release with the files.
3. The version name comes from the tag (v1.2.3 -> 1.2.3). The version code goes up automatically with every run.
4. If something is wrong the job stops with a clear message (bad tag, missing secret, wrong password or alias).
5. Install the .apk from the Release on a phone: it must install over the previous release without uninstalling (same key).

## 6. Server settings for the legal pages
On your server set CONTACT_EMAIL and PUBLISHER_NAME (required when POLAR_ENVIRONMENT=production; optional POLICY_UPDATED).
Then open https://YOUR-SERVER/privacy and /delete-account and read them. Use these two addresses in the store listing.
Set SERVER_URL in ProConfig.kt so the in-app "Privacy policy" button works.

## 7. Draft answers for the Play "Data safety" form (check each against your final build)
- Data collected: Email address (only if the user signs in, optional); User ID (random account ID); Purchase history (order records).
  Not collected: location, contacts, photos, audio recordings, advertising ID, health data, browsing history.
- Data shared with others: none for advertising. Payments are processed by Polar, the merchant of record.
- Encrypted in transit: yes (HTTPS). Users can request deletion: yes (in the app and at /delete-account).
- Security practices: data is encrypted in transit; deletion is available.
- Content rating questionnaire: no violence, no user-generated content, no gambling. Expect a rating for everyone.
- Ads: no. Target audience: 13 and over (the policy says the app is not for children under 13).

## 8. Final checklist on a real phone (signed release APK)
[ ] Installs, opens, icon looks right (also as a round icon and, on Android 13+, the themed icon)
[ ] Permission screen: every item can be allowed
[ ] Test alarm in 10 seconds rings with the screen locked, fades in gently
[ ] A real alarm 3 minutes ahead rings; after a phone restart another one still rings
[ ] Free voices work; Pro voices show the lock; own file works; missing mp3 falls back to the phone tone
[ ] Unlock Pro with a sandbox payment; Restore on a second phone; refund removes Pro
[ ] Account > Privacy policy opens; Delete my account works and the guest starts fresh
[ ] Installing the next release over this one keeps the alarms
