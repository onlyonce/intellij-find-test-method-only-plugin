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

The display name *can* be changed later, and was: `Test-Only Method Detector` became
`Test-Only Declaration Detector` in 0.3.2, once the inspection reported fields and classes as well as
methods. The change takes effect on the next upload — there is no separate rename step.

## Checking what is actually live

`publishPlugin` succeeding means **uploaded**, not **visible**. Approval is a separate, per-plugin
gate, and until it clears the listing is absent from search and from the plugin-manager feed — so
"I cannot find it in the Marketplace" is the expected state, not evidence the upload failed. New
versions uploaded while a plugin is still unapproved simply queue behind it.

Public endpoints show nothing for an unapproved plugin. The authoritative check needs the token:

```bash
set -a; . ./.env; set +a
curl -s -H "Authorization: Bearer $PUBLISH_TOKEN" \
  "https://plugins.jetbrains.com/api/plugins?xmlId=dev.onlyonce.testonlymethods&family=intellij"
curl -s -H "Authorization: Bearer $PUBLISH_TOKEN" \
  "https://plugins.jetbrains.com/api/plugins/33223/updates"
```

`33223` is this plugin's numeric id. The fields that answer the question:

| Field | Meaning |
|---|---|
| `approve` | the plugin has cleared review. `false` ⇒ nothing is publicly visible, whatever is uploaded |
| `hasUnapprovedUpdate` | a version is uploaded and waiting |
| `isHidden` | vendor chose to keep it out of search — distinct from unapproved |
| `isBlocked` | rejected. Different problem, and not what "not approved yet" looks like |
| per-update `listed` | that specific version is downloadable |

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
