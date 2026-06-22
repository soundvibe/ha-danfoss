# Release Process

## Overview

This project uses a **GitHub Release-driven** build pipeline. The Docker image for the Home Assistant add-on is built by downloading the JAR artifact that was built and uploaded by CI.

## Step-by-Step

### 1. Bump the version

Update `version` in [`config.yaml`](./config.yaml):

```yaml
version: "0.4.8"
```

> **Note:** Do **not** bump the version in `pom.xml` — that stays at `0.0.1` and tracks the Maven artifact independently of the add-on release version.

### 2. Update the changelog

Add a new section at the top of [`CHANGELOG.md`](./CHANGELOG.md) describing all notable changes for this release:

```markdown
## [0.4.8]

- Fixed NullPointerException in `IconMaster.toState()`.
- Modernized discovery UI.
```

### 3. Merge the changes

Open a PR (or push directly) with the version bump and changelog update, then merge it to the default branch.

### 4. Create a GitHub Release

1. Go to the [**Releases**](https://github.com/soundvibe/ha-danfoss/releases) page on GitHub.
2. Click **"Draft a new release"**.
3. Choose **"Choose a new tag"** and enter the version (e.g., `v0.4.8`).
4. Set the **Release title** (e.g., `v0.4.8`).
5. Generate release notes by clicking the button in the GitHub UI
6. Click **"Publish release"**.

### 5. CI builds and uploads the JAR

Publishing the release triggers the GitHub Actions workflow, which:

1. Checks out the repository at the release tag.
2. Builds the JAR using Maven (`mvn package`).
3. Uploads the resulting `ha-danfoss-addon.jar` as a release asset.

### 6. Docker image picks up the new JAR

The [`Dockerfile`](./Dockerfile) downloads the JAR from the release artifacts when the Home Assistant add-on image is built:

```dockerfile
RUN VERSION=$(grep '^version:' /tmp/config.yaml | sed 's/version: *"//;s/"$//') && \
    curl -L -o /app.jar "https://github.com/soundvibe/ha-danfoss/releases/download/v${VERSION}/ha-danfoss-addon.jar"
```

If the versioned release asset is not found, the Dockerfile falls back to the latest release asset.

