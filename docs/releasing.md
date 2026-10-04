# Manual alpha preparation

The current alpha candidate uses `mod_version=0.1.0-alpha.2`. Its matching tag name
is `v0.1.0-alpha.2`, with GitHub's **pre-release** flag enabled. Recheck tags/releases
before assigning it. Release preparation does not authorize a stable release.
There is no release workflow; tagging and publishing are manual after acceptance
of the integrated candidate.

## Build the integrated candidate

1. Integrate the reviewed release-preparation and accepted fixes. In a clean,
   isolated checkout of that exact candidate, record `git rev-parse HEAD` and
   confirm `git status --short` has no source changes. Keep unrelated work intact.
2. Use JDK 25 and run `./gradlew --version`, then `./gradlew build`. Keep the
   build log and relevant `build/reports/tests/` reports with the candidate SHA.
3. Take **only** `build/libs/codon-0.1.0-alpha.2.jar` from `:jar` as the installable
   artifact. `-sources.jar` is not installable; this build has no `remapJar` task.
4. Inspect that runtime archive: `fabric.mod.json` must contain version
   `0.1.0-alpha.2`, the expected dependency requirements and production entrypoints.
   Confirm `LICENSE_codon`, core/server/client classes, mixin configurations and
   resources are present. It must not contain Minecraft classes, the
   `codon-ui-test` manifest, GameTest classes or test-only mixins/fixtures.
5. Record its byte size and SHA-256 alongside the full candidate commit. On macOS,
   `shasum -a 256 build/libs/codon-0.1.0-alpha.2.jar` produces the checksum; Linux
   can use `sha256sum`. Keep this exact file for the acceptance run and upload.

## Accept and publish that file

Test the built runtime JAR installed with its documented dependencies in a
disposable/backed-up world. Actual clicks and key presses must cover a triggered
breakpoint, cursor mode, stepping/Continue through completion, Source browsing,
Watch/NBT inspection and relevant UI changes included in the candidate. Confirm
the installed version. Record the candidate commit, artifact checksum, runtime
setup and observed results. The [feature guides](verification/README.md) give
reproduction paths and evidence expectations. Report dedicated-server or platform
checks only when actually observed; build/JVM checks alone are insufficient.

If integration or acceptance changes source, build a new candidate and repeat the
affected acceptance on its new artifact. A JAR from an earlier PR head cannot
stand in for the integrated candidate. Verify the accepted file's checksum again
before uploading. If rebuilding changes the file, test and record that new file.

After acceptance, the release owner can tag the recorded candidate commit and
manually create the GitHub prerelease with that exact runtime JAR, its SHA-256,
requirements and the [alpha notes](../CHANGELOG.md). Keep the prerelease flag set.
Do not upload test fixtures, worlds, logs, private data or credentials. Record
remaining limitations instead of claiming unperformed platform checks.
