# Releasing

## First release — manual, once

JetBrains Marketplace has no API to *create* a plugin entry, only to add versions to an existing one.
So the first upload is done by hand; every later one is `./gradlew publishPlugin`.

1. Sign in at [plugins.jetbrains.com](https://plugins.jetbrains.com) with a JetBrains account.
2. Accept the Marketplace Developer Agreement and create a **vendor profile**. The vendor name becomes
   public and is awkward to change afterwards.
3. **Upload plugin** from the account menu. Attach `build/distributions/find-test-only-methods-<version>.zip`.
4. Fill in the form:
   - **Licence** — MIT. Open-source licences are declared by linking the source repository.
   - **Tags** — `Inspection`, `Code tools`, `Java` are the relevant ones.
   - **Hidden** — keeps the listing out of search while still installable by direct URL. Before ticking
     it, check whether it also defers the approval review; if it does, you would be waiting on a review
     that has not started.
5. Wait for review. Marketplace does a manual approval pass on first submissions.

Two values are fixed permanently by that first upload:

| | |
|---|---|
| **Plugin ID** | `dev.onlyonce.testonlymethods` — cannot be changed afterwards, ever |
| **Vendor** | changing it later means contacting marketplace@jetbrains.com |

The display name (`Test-Only Method Detector`) *can* be changed later.

## Later releases — one command

```bash
PUBLISH_TOKEN=… ./gradlew publishPlugin
```

Generate the token at *Marketplace → your profile → My Tokens*. It is a **permanent** token: store it in
a password manager, and in GitHub Actions secrets if this is ever automated. Never commit it.

Marketplace rejects an upload whose version already exists, so `version` in
[`build.gradle.kts`](../build.gradle.kts) must be bumped every time.

A version with a pre-release suffix (`0.2.0-beta.1`) is published to a `beta` channel automatically — see
the `channels` computation in the build script — so it cannot reach users on the stable channel.

A custom channel is not merely "hidden from stable users": it is invisible to *everyone* until they add
the custom repository URL in *Settings | Plugins | ⚙ | Manage Plugin Repositories*. Use it for a version
you want a named group to test, not as a soft launch.

## Signing — optional

An unsigned plugin installs, but the IDE shows a warning dialog first. To avoid that:

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

Then `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` and `CERTIFICATE_CHAIN` (file *contents*, not paths) drive
`./gradlew signPlugin`. `publishPlugin` picks up the signed archive.

Keep the key. Uploading a version signed with a *different* key than the previous one is rejected — the
certificate is how Marketplace ties versions to the same author.

## Pre-flight

```bash
./gradlew clean build verifyPlugin
```

`verifyPlugin` must report **Compatible** for every IDE in the range. It is the only thing that checks
the `since-build` promise, and it catches API misuse the compiler cannot see.
