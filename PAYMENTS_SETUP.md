# SoftAlarm payments: Polar + Firebase setup

Note: all voices are free for now, so you do NOT need any of this until you add a Pro voice (see PLUGIN_POINTS.md).

How it works (nothing secret ever lives in the app):
1. The app signs in to Firebase silently and asks YOUR server: "give me a payment page" (it sends its Firebase token).
2. The server checks the token and asks Polar to create a checkout for your "SoftAlarm Pro" product. The user's id travels inside the checkout.
3. The app opens Polar's secure payment page in the browser. Card details go straight to Polar, never to you.
4. Polar tells your server "order paid" (a signed webhook). The server saves the payment in Firestore and sets users/{uid}.pro = true.
5. The buyer is sent back to the app (softalarm://paid) and the app sees Pro. The price is set in Polar, never in the app.

Polar is the "merchant of record": it is the legal seller, collects the money, and handles sales tax / VAT for you.

## 1. Polar account and payouts (Pakistan)
1. Create an account at https://polar.sh and an organization. Do everything first at https://sandbox.polar.sh (a separate test copy; separate account, product, token and webhook).
2. Polar pays you through Stripe Connect Express. Polar lists Pakistan as a supported payout country.
3. IMPORTANT, from Polar's own docs: the payout bank account must be in the same country as you/your business and in that country's currency (a Pakistani PKR bank account).
   Multi-currency "borderless" accounts (Payoneer, Wise, Revolut) usually do NOT pass Stripe's check. You will also do identity verification (KYC) with Stripe.
4. Finance > Account in the Polar dashboard starts the payout setup.

## 2. The product ($1)
1. Products > New product: name "SoftAlarm Pro", type: one-time purchase, fixed price USD $1.00.
2. Open the product and copy its Product ID (a UUID like 1a2b3c4d-....). This is POLAR_PRODUCT_ID.
3. In the price settings, check the currency options. Polar says it supports 135+ currencies using Stripe's exchange rates, so buyers can see their own currency. Confirm how it shows for your product before launch.

## 3. Access token
Settings > Developers (or "Access tokens") > create an Organization Access Token. Give it permission to create checkouts (checkouts:write).
Copy it: this is POLAR_ACCESS_TOKEN. It is secret.

## 4. Firebase service account (the server's key)
1. Firebase console > Project settings > Service accounts > Generate new private key. A JSON file downloads.
2. This file is a SECRET. Never put it in GitHub or the app. It goes only into your host's environment settings.
3. Easiest: open the file in a text editor, copy everything, paste it as FIREBASE_SERVICE_ACCOUNT_JSON.
   (Or one line of base64 in FIREBASE_SERVICE_ACCOUNT_BASE64: Linux `base64 -w0 key.json`.)

## 5. Deploy the server (any host that runs Node 18+ over https)
Render, Railway, Fly.io or a VPS all work. Example with Render:
1. New > Web Service > connect your GitHub repo.
2. Root Directory: `backend-stub`   Build command: `npm install`   Start command: `npm start`
3. Environment variables (see backend-stub/.env.example):
   POLAR_ACCESS_TOKEN, POLAR_PRODUCT_ID, POLAR_WEBHOOK_SECRET (step 6), POLAR_ENVIRONMENT=sandbox,
   PUBLIC_URL=https://your-server-address (no slash at the end), FIREBASE_SERVICE_ACCOUNT_JSON
   CONTACT_EMAIL and PUBLISHER_NAME (shown in the privacy policy page; required when POLAR_ENVIRONMENT=production)
4. Deploy, then open https://YOUR-URL/health . You should see {"ok":true}. Open https://YOUR-URL/thanks to see the thank-you page, and /privacy and /delete-account for the legal pages.
   If the server does not start, its log lists exactly which setting is missing or wrong.
Free plans may "sleep": the first payment after a pause can be slow. An always-on plan is better for real customers.

## 6. Webhook
1. Polar dashboard > Settings > Webhooks > Add endpoint.
2. URL: https://YOUR-URL/webhook/polar      Format: Raw (the normal one, not Discord/Slack)
3. Events: `order.paid` and `order.refunded`
4. Copy the endpoint's secret into POLAR_WEBHOOK_SECRET on your host and redeploy.

## 7. Test in sandbox
Make a test purchase from the app (Step 6) with the test card 4242 4242 4242 4242, any future date, any CVC.
The phone should come back to the app and unlock. In Polar > Webhooks > your endpoint you can see each delivery and resend it.

## 8. Going live
Repeat steps 1-3 and 6 in the REAL Polar (polar.sh): new product, new token, new webhook secret.
On your host set POLAR_ENVIRONMENT=production and the new values, then redeploy.

## Fees (be aware)
Polar charges 4% + $0.40 per sale, plus 1.5% for non-US cards. On a $1 sale that is about $0.44 to $0.46, so you keep roughly $0.54.
Payouts also cost: about $2 per month while payouts are active, 0.25% + $0.25 per payout, and up to 1% for currency conversion.
Polar says it can offer custom pricing for products under $10: ask them. Disputes cost $15 each.

## Payment methods
Cards and the methods Stripe supports in the buyer's country. Easypaisa and JazzCash are NOT supported.

## What is stored in Firestore
payments/{orderId}: uid, provider, amount, currency, status, environment, createdAt. users/{uid}: pro, proSince, lastPaymentId.
No card numbers, names or emails. A full refund in Polar removes Pro on the server, and the app locks Pro again the next time it opens (the server's status answer says refunded:true; the app removes Pro for that reason only). If the same user buys again, the refund mark is cleared.

## Account deletion
The app's "Delete my account" calls DELETE /api/account with the user's sign-in token. The server deletes the user record and the sign-in account.
Payment records are kept but cut loose from the person (uid becomes "deleted-user"), because accounting and tax rules require them.

## Tests
`cd backend-stub && npm test` runs 52 automatic tests (no keys needed). GitHub runs them on every push.

## Google Play
If you publish on Google Play, digital unlocks must use Google Play Billing, not Polar. Polar is fine for APKs you share yourself.
