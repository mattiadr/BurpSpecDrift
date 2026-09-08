# BurpExtensionTemplate

This is just a starting point for Burp extensions in Kotlin.

After cloning make the following changes:
- Rename project in IDEA
- `build.gradle.kts`
  - Update the kotlin and shadow plugin versions
  - Update the montoya dependency version
  - Change the group
- `settings.gradle.kts`
  - Change the project name
- Rename package `com.mattiadr.burpExtensionTemplate`
- `Main` class
  - Change extension name
  - Optionally rename this class
- Create gradle shadowJar run configuration

### GitHub Actions

This repository includes a GitHub action to automatically create a new release when a version tag `v*` is pushed.
If you want to disable this behavior remove the file `.github/workflows/release.yml`.
