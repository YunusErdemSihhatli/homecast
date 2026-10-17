# Contributing to HomeCast

Thanks for helping! Bug reports, fixes and small improvements are welcome. For
bigger changes, please open an issue first so we can agree on the approach.

## Build and check

You need JDK 17 or newer and the Android SDK (platform 36).

```sh
./gradlew assembleDebug                                 # build the debug APK
./gradlew spotlessApply lint testDebugUnitTest          # format, lint, unit tests
```

Run the second command before every pull request; CI runs `spotlessCheck`, `lint`
and `testDebugUnitTest` and fails on any problem. Formatting follows the official
Kotlin coding conventions, enforced by ktlint through Spotless.

## Ground rules

- **No tracking.** No ads, analytics, crash reporting, Google Play Services or
  any SDK that sends data anywhere. The app may only talk to the pages the user
  opens, their media servers and the user's TV.
- **No new permissions** without discussing them in an issue first.
- **No site names.** Code, tests, docs and issues must not name or link to
  third-party video or streaming sites. Use local test fixtures instead.
- **No DRM circumvention, geo-restriction bypass or downloading.**
- All user-facing text goes in `app/src/main/res/values/strings.xml`.
- Add or update unit tests for behaviour you change.
- Never commit secrets: keystores, `keystore.properties`, `local.properties`.
- Releases are built by `.github/workflows/release.yml` on `v*` tags and must be
  signed with the certificate whose SHA-256 is pinned in `HOMECAST_CERT_SHA256`
  there. Rotating the signing key requires updating that value.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/), for example
`fix(proxy): keep query strings on redirected playlists` or
`feat(remote): add playback speed`. Add a line to the `[Unreleased]` section of
`CHANGELOG.md` for user-visible changes.

## Licence

HomeCast is licensed under the GPL-3.0. By contributing, you agree that your
contributions are licensed under the same licence.
