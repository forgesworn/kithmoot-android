# Signing-lineage tests against a minified release

25 October 2026. Source review before building, by the implementing agent.

The signing-lineage jobs installed a debug instrumentation APK alongside a
minified release APK. The debug harness expects original shared-library names;
R8 can rename or remove them. Main run 40191682667 crashed on Kotlin Intrinsics
before seeding. Retaining app and Kotlin entry points exposed the next mismatch,
AndroidX Trace, in run 40196972305. These are test startup failures, not passing
lineage evidence. Release minification must stay enabled.

The default instrumentation type remains debug for existing recovery and
recording consumers. A validated `kithmoot.instrumentationBuildType=release`
property selects the release test variant for lineage. AGP processes that test
APK with the app's release mapping. The runner requires this release harness;
there is no fallback to the debug one.

Packaging retains the arm64 production APK before adding the emulator ABI.
`verify-lineage-apk-payload.py` checks every ZIP payload entry against that
production input, permitting only extra x86 native libraries. Missing engines,
changed code, manifest, resources or arm64 libraries, and unexpected payloads
fail. Five device-free tests exercise that boundary. Both API-33 and API-35
lineage jobs must still seed storage, rotate certificates, read retained data,
check the release UI and refuse rollback. This repair adds no version bump or
public call-relay feature, so it can qualify the pending 0.6.113 release first.

Soundness outcome: ready for hosted qualification. App/Kotlin API retention
keeps fixture entry points, mapped release instrumentation handles the remaining
shared dependencies, and the payload check prevents testing different app code.
No local instrumentation test, device or emulator is run by this repair.

Legal-cover readback: this changes packaging and test selection, with no new
runtime service, endpoint, discovery, telemetry or credential retention. The
publisher position and existing notices are unchanged. The existing law note
is verified 28 September 2026, with section 2.13's primary text read 25 October;
its application readings remain unchecked. No fresh legal classification is
made by this CI repair.

References: [test build type](https://developer.android.com/build/gradle-tips#change-the-test-build-type),
[test mapping notes](https://android.googlesource.com/platform/tools/base/+/eb7637849b9d9c36057f96ffb1fefed61e005d7b/build-system/changelog.txt).
