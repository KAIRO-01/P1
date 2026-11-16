# KAIRO P1 Preview: Setup and Device Test

This **KAIRO P1** preview is a separate Android package: `app.shilex.android.p1.preview`. It can install alongside the original `app.shilex.android.p1` without uninstalling it. Preview and original app data are **not shared**.

## 1. Private Google Sheets (read-only)

1. Create or select a Google Cloud project and **enable Google Sheets API**.
2. Configure the Google OAuth consent screen and add the intended Google Account as a test user if in testing mode.
3. Create an **Android OAuth client ID** with package `app.shilex.android.p1.preview`, using the **SHA-1 of the signing certificate used for the APK**.
4. On the phone, sign into the Google Account that can already open the spreadsheet.
5. In KAIRO Preview > Settings > Google Sheets, paste a full `https://docs.google.com/spreadsheets/d/.../edit#gid=...` link; tap **Save link**.
6. Tap **Connect** and approve Google's **read-only spreadsheets** scope. Tap **Test**. Only after a **LIVE** test succeeds should you enable order-change notifications.
7. Make sure row 1 contains recognizable headings (e.g., Customer Name, Mandir, Total Price, Advance, Balance, Delivery Date, Address, Status). Open the correct tab in Google Sheets and copy its link (with `gid`).
8. KAIRO can now answer read-only order questions. It cannot change orders or automatically sync a private Sheet until Google consent and API access are genuinely working.

If authorization fails, verify OAuth client **package + SHA-1**, app signing, Sheets API, and that the chosen account has spreadsheet access. **Never make customer data public to bypass authorization.**

## 2. Stable signing (required for repeatable private-Sheets OAuth)

GitHub Actions can use these repository Actions secrets:
- `KAIRO_PREVIEW_KEYSTORE_BASE64` — base64 of a privately generated Android keystore.
- `KAIRO_PREVIEW_PASSWORD` — keystore password.
- `KAIRO_PREVIEW_ALIAS` — key alias, defaults to `kairo`.
- `KAIRO_PREVIEW_KEY_PASSWORD` — key password if different, otherwise the keystore password is used.

The project must use the **same key for every future preview** for Android updates and Google OAuth SHA-1 registration. Keep the signing key and passwords private; do not send them in chat, commit them to Git, or include them in screenshots. If those secrets are missing, CI creates a temporary debug signature. Later previews may fail to update; do not uninstall your original app.

To inspect the signature of the locally held private keystore: `keytool -list -v -keystore /path/to/kairo-preview.jks -alias kairo`.

## 3. Navigation and voice

- Google Maps must be installed and location services enabled, with the **Google Maps app** allowed location access. Say “Hey Kairo, navigate to Viviana Mall, Thane.” Google Maps determines the current GPS origin and starts its supported route.
- For a delivery address from your Sheet, first ask KAIRO to find the customer's order, then request navigation to that saved address.
- Before enabling the wake switch, enroll **five** clear voice samples. Owner matching is an approximate local acoustic check, **not infallible biometric authentication**. Check false accepts with other speakers and false rejects in a noisy room on the actual phone.
- In Settings, review microphone, Maps location, notification, accessibility and background battery permissions. Android controls several of these directly.

## 4. Verification and safety

The GitHub CI pipeline runs Android unit tests and assembles the preview APK. A successful compile and unit tests do **not** prove Google OAuth consent, actual GPS routes, background scheduled alerts or speaker verification on your specific phone. Those require a manual phone test.

**Never uninstall or clear the original KAIRO app just to install a preview.** Its private databases and preferences are not automatically migrated to the separate preview.
