# File share (快传)

Open it with the **File share** pill in the Up-toolbar. The screen shows a QR code and a link; a phone on
the same Wi-Fi scans it, picks an APK, and the TV asks to install it.

- One `ServerSocket` on the LAN (ports 8808–8812, first free one), one thread, plain HTTP. No library besides
  the QR encoder (ZXing core, Apache-2.0, only its `QRCodeWriter` survives R8).
- The page and the upload endpoint live under a random 12-hex-character token, so a scan of the network
  cannot push anything, and an APK must start with the ZIP magic every APK begins with. Uploads are capped
  at 300 MB and land in the launcher's private `files/inbox`.
- The server runs only while the screen is open; leaving it closes the socket. Nothing polls, nothing talks
  to the internet.
- Installing needs `REQUEST_INSTALL_PACKAGES`; the first time, the TV asks to allow it for the launcher and
  the screen sends you to that setting.

Phone side: scan with the camera or any QR reader, then the page's file picker. The page reports upload
progress and the TV says "Received `<name>`" once the file is complete.

Files: `FastShare.java` (server), `FastShareActivity.java` (screen), `activity_fast_share.xml`,
`xml/file_paths.xml` (the provider that hands the APK to the installer).
