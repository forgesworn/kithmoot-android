# Shared Signet contacts SDK

This module compiles the unmodified public Kotlin contacts SDK core from the
pinned source archive in `vendor/`. `vendor/source.json` records the exact
upstream commit and SHA-256. The Gradle extraction task verifies that digest
before compilation. The archive includes the upstream MIT licence, which is
also carried in the resulting JAR. Application signing, relay and encrypted
storage adapters remain in `:app`; no private account key is supplied to this
module.

To update, review a public upstream commit, regenerate with
`git archive --format=tar.gz <commit> core/src/main/kotlin LICENSE`, update its
manifest and the Gradle digest, and rerun SDK and consumer qualification.
This is a pinned source dependency, not a fork of the contact protocol.
