# Contributing

Thanks for helping. Keep changes small and focused.

- **Build:** see "Build from source" in the README. Run `bash tools/runtests.sh` (Git Bash) before sending anything: all tests must pass.
- **Style:** plain Java, no Gradle, no new libraries unless there is a strong reason (the app targets old, low-memory TVs). Match the surrounding code.
- **TV first:** every screen must work with a remote (arrow keys, OK, Back) and read from 3 metres away. Test on a real TV or an Android TV emulator if you can.
- **Privacy:** no analytics, no tracking, no secrets in code. Never log a playlist link (it can contain a login): use `Redact`.
- **Content:** do not add playlist links, channel lists or stream URLs that you do not have the right to share. Pull requests that add or point to unlicensed content will be closed.
- **Pull requests:** one topic per PR, a short description of what changed and how you tested it. By contributing you agree your work is licensed under Apache-2.0 (see LICENSE).
- **Bugs:** open an issue with your Android version, TV model, app version (Settings > About) and what you pressed.
