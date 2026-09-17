#!/bin/bash -ex
#
# DO NOT RUN DIRECTLY.
# Script must be sourced by deploy.sh or deploy-fips.sh
# after setting or unsetting `SNOWPARK_FIPS` environment variable.
#

# disable xtrace so credentials are not echoed to logs
set +x

if [ -z "$GPG_KEY_ID" ]; then
  export GPG_KEY_ID="Snowflake Computing"
  echo "[WARN] GPG key ID not specified, using default: $GPG_KEY_ID."
fi

if [ -z "$GPG_KEY_PASSPHRASE" ]; then
  echo "[ERROR] GPG passphrase is not specified for $GPG_KEY_ID!"
  exit 1
fi

if [ -z "$GPG_PRIVATE_KEY" ]; then
  echo "[ERROR] GPG private key file is not specified!"
  exit 1
fi

if [ -z "$sonatype_user" ]; then
  echo "[ERROR] Jenkins sonatype user is not specified!"
  exit 1
fi

if [ -z "$sonatype_password" ]; then
  echo "[ERROR] Jenkins sonatype pwd is not specified!"
  exit 1
fi

if [ -z "$PUBLISH" ]; then
  echo "[ERROR] 'PUBLISH' is not specified!"
  exit 1
fi

if [ -z "$github_version_tag" ]; then
  echo "[ERROR] 'github_version_tag' is not specified!"
  exit 1
fi

# Restrict the release ref to an immutable vMAJOR.MINOR.PATCH tag. This rejects
# branches, raw commit hashes, HEAD, and option-like values, and (together with
# quoting below) neutralizes word-splitting/globbing on $github_version_tag.
if ! [[ "$github_version_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "[ERROR] 'github_version_tag' must match vMAJOR.MINOR.PATCH (got: $github_version_tag)"
  exit 1
fi

# Fetch, verify, and check out the release tag BEFORE any release secrets are
# loaded, so a malicious/poisoned build.sbt can never be evaluated while the
# Sonatype credentials and GPG signing key are present in the environment.
# Fetch the tag from the canonical upstream by explicit URL rather than a named
# remote: the Jenkins workspace does not reliably configure an "origin" remote,
# and pinning the URL guarantees the tag comes from the known upstream repo
# regardless of local remote configuration. Overridable via GITHUB_REPO_URL.
GITHUB_REPO_URL="${GITHUB_REPO_URL:-https://github.com/snowflakedb/snowpark-java-scala.git}"
echo "[INFO] Fetching and verifying tag: $github_version_tag from $GITHUB_REPO_URL."
git fetch --tags --force "$GITHUB_REPO_URL" "refs/tags/${github_version_tag}:refs/tags/${github_version_tag}"
if ! git rev-parse --verify --quiet "refs/tags/${github_version_tag}^{commit}" >/dev/null; then
  echo "[ERROR] tag refs/tags/${github_version_tag} not found"
  exit 1
fi
echo "[INFO] Checking out snowpark-java-scala @ tag: $github_version_tag."
git -c advice.detachedHead=false checkout --detach "refs/tags/${github_version_tag}"

mkdir -p ~/.ivy2

STR=$'host=central.sonatype.com
user='$sonatype_user'
password='$sonatype_password''

echo "$STR" > ~/.ivy2/.credentials

# import private key first
echo "[INFO] Importing PGP key."
if [ ! -z "$GPG_PRIVATE_KEY" ] && [ -f "$GPG_PRIVATE_KEY" ]; then
  # First check if already imported private key
  if ! gpg --list-secret-key | grep "$GPG_KEY_ID"; then
    gpg --allow-secret-key-import --import "$GPG_PRIVATE_KEY"
  fi
fi

# re-enable xtrace now that credential handling is done
set -x

which sbt
if [ $? -ne 0 ]
then
   pushd ..
   echo "[INFO] sbt is not installed, downloading latest sbt for test and build."
   curl -L -o sbt-1.11.4.zip https://github.com/sbt/sbt/releases/download/v1.11.4/sbt-1.11.4.zip
   unzip sbt-1.11.4.zip
   PATH=$PWD/sbt/bin:$PATH
   popd
else
   echo "[INFO] Using system installed sbt."
fi
which sbt
# Safe: the release tag was already checked out above, so this evaluates the
# verified tree (not the pre-checkout workspace HEAD).
sbt version

# clean locally staged artifacts
rm -rf ~/.ivy2/local/

if [ "$PUBLISH" = true ]; then
  if [ "$SNOWPARK_FIPS" = true ]; then
    echo "[INFO] Packaging snowpark-fips @ tag: $github_version_tag."
  else
    echo "[INFO] Packaging snowpark @ tag: $github_version_tag."
  fi
  sbt +publishSigned
  echo "[INFO] Staged packaged artifacts locally with PGP signing."
  sbt sonaUpload
  echo "[INFO] Uploaded artifacts to sonatype central portal."
  sbt sonaRelease
  if [ "$SNOWPARK_FIPS" = true ]; then
    echo "[SUCCESS] Released snowpark-fips_2.12-$github_version_tag and snowpark-fips_2.13-$github_version_tag to Maven Central"
  else
    echo "[SUCCESS] Released snowpark_2.12-$github_version_tag and snowpark_2.13-$github_version_tag to Maven Central."
  fi
else
  #release to s3
  echo "[INFO] Staging signed artifacts to local ivy2 repository."
  sbt +publishLocalSigned

  # SBT will build FIPS version of Snowpark automatically if the environment variable exists.
  if [ "$SNOWPARK_FIPS" = true ]; then
    S3_JENKINS_URL="s3://sfc-eng-jenkins/repository/snowparkclient-fips/$github_version_tag/"
    S3_DATA_URL="s3://sfc-eng-data/client/snowparkclient-fips/releases/$github_version_tag/"
    echo "[INFO] Uploading snowpark-fips artifacts to:"
  else
    S3_JENKINS_URL="s3://sfc-eng-jenkins/repository/snowparkclient/$github_version_tag/"
    S3_DATA_URL="s3://sfc-eng-data/client/snowparkclient/releases/$github_version_tag/"
    echo "[INFO] Uploading snowpark artifacts to:"
  fi
  echo "[INFO]   - $S3_JENKINS_URL"
  echo "[INFO]   - $S3_DATA_URL"

  # Remove release folders in s3 for current release version if they already exist due to previously failed release pipeline runs.
  echo "[INFO] Deleting $github_version_tag release folders in s3 if they already exist."
  aws s3 rm "$S3_JENKINS_URL" --recursive
  echo "[INFO] $S3_JENKINS_URL folder deleted if it exists."
  aws s3 rm "$S3_DATA_URL" --recursive
  echo "[INFO] $S3_DATA_URL folder deleted if it exists."

  # Rename all produced artifacts to include version number (sbt doesn't by default when publishing to local ivy2 repository).
  # TODO: BEFORE SNOWPARK v2.12.0, fix the regex in the sed command to not match the 2.12.x or 2.13.x named folder under ~/.ivy2/local/com.snowflake/snowpark_2.1[23]/
  find ~/.ivy2/local -type f -name '*snowpark*' | while read file; do newfile=$(echo "$file" | sed "s/\(2\.1[23]\)\([-\.]\)/\1-${github_version_tag#v}\2/"); mv "$file" "$newfile"; done

  # Generate sha256 checksums for all artifacts produced except .md5, .sha1, and existing .sha256 checksum files.
  find ~/.ivy2/local -type f -name '*snowpark*' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' -exec sh -c 'for f; do sha256sum "$f" | awk '"'"'{printf "%s", $1}'"'"' > "$f.sha256"; done' _ {} +

  # Copy all files, flattening the nested structure of the ivy2 repository into the expected structure on s3.
  find ~/.ivy2/local -type f -name '*snowpark*' ! -name '*.sha1' -exec aws s3 cp \{\} $S3_JENKINS_URL \;
  find ~/.ivy2/local -type f -name '*snowpark*' ! -name '*.sha1' -exec aws s3 cp \{\} $S3_DATA_URL \;

  echo "[SUCCESS] Published Snowpark Java-Scala $github_version_tag artifacts to S3."
fi

# ── Phase-2 adapter upload (optional) ──────────────────────────────────────────
# When ADAPTER_JAR_PATH is set the script also publishes the pre-built JDBC
# stored-procedure adapter JAR to the sfc-eng-jenkins S3 path consumed by
# jdbc_adapter_s3_mirror.sh in the Anaconda RPM build.
#
# Required env vars:
#   ADAPTER_JAR_PATH         – local path to the adapter deploy JAR
#                              (e.g. output of the ExecPlatform Bazel build)
#   ARTIFACT_MANIFEST_PATH   – local path to the artifact.manifest sidecar
#                              (default: poc-artifacts/artifact.manifest)
#
# The script:
#   1. Reads ADAPTER_GAV from the manifest to determine the adapter version.
#   2. Computes SHA-256 of the adapter JAR.
#   3. Uploads JAR + sha256 + manifest to
#        s3://sfc-eng-jenkins/anaconda/jdbc-stored-proc-jdbc4-adapter/<version>/
#      where jdbc_adapter_s3_mirror.sh will find them during the RPM build.
if [[ -n "${ADAPTER_JAR_PATH:-}" ]]; then
  MANIFEST_PATH="${ARTIFACT_MANIFEST_PATH:-poc-artifacts/artifact.manifest}"

  if [[ ! -f "${ADAPTER_JAR_PATH}" ]]; then
    echo "[ERROR] ADAPTER_JAR_PATH='${ADAPTER_JAR_PATH}' does not exist."
    exit 1
  fi
  if [[ ! -f "${MANIFEST_PATH}" ]]; then
    echo "[ERROR] ARTIFACT_MANIFEST_PATH='${MANIFEST_PATH}' does not exist."
    exit 1
  fi

  # Extract adapter version from ADAPTER_GAV, e.g.
  # ADAPTER_GAV=com.snowflake:jdbc-stored-proc-jdbc4-adapter:1.0.0
  #                                                            ↑ field 3
  ADAPTER_VERSION=$(grep '^ADAPTER_GAV=' "${MANIFEST_PATH}" | head -1 | cut -d: -f3)
  if [[ -z "${ADAPTER_VERSION}" ]]; then
    echo "[ERROR] Could not parse ADAPTER_GAV from ${MANIFEST_PATH}."
    exit 1
  fi
  echo "[INFO] Adapter version: ${ADAPTER_VERSION}"

  ADAPTER_ID="jdbc-stored-proc-jdbc4-adapter"
  ADAPTER_JAR_NAME="${ADAPTER_ID}-${ADAPTER_VERSION}-with-dependencies.jar"
  ADAPTER_SHA_NAME="${ADAPTER_JAR_NAME}.sha256"

  # Compute SHA-256 (truncate to bare hex; strip filename suffix from sha256sum output).
  sha256sum "${ADAPTER_JAR_PATH}" | awk '{printf "%s", $1}' > "/tmp/${ADAPTER_SHA_NAME}"

  ADAPTER_S3_BASE="s3://sfc-eng-jenkins/anaconda/${ADAPTER_ID}/${ADAPTER_VERSION}"
  echo "[INFO] Uploading adapter artifacts to ${ADAPTER_S3_BASE}/"
  aws s3 cp "${ADAPTER_JAR_PATH}"           "${ADAPTER_S3_BASE}/${ADAPTER_JAR_NAME}"
  aws s3 cp "/tmp/${ADAPTER_SHA_NAME}"      "${ADAPTER_S3_BASE}/${ADAPTER_SHA_NAME}"
  aws s3 cp "${MANIFEST_PATH}"              "${ADAPTER_S3_BASE}/artifact.manifest"
  echo "[SUCCESS] Uploaded adapter ${ADAPTER_VERSION} and artifact.manifest to S3."
else
  echo "[INFO] ADAPTER_JAR_PATH not set; skipping adapter upload."
fi
