#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

PROJECT_DIR="$PWD/android/LinkChatBridge"
OUTPUT_DIR="$PWD/dist"
HOST_PORT="${LINKCHATAI_HOST_PORT:-8002}"
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
if [ -z "$LAN_IP" ]; then
  LAN_IP="$(ipconfig getifaddr en1 2>/dev/null || true)"
fi
DEFAULT_URL="${LINKCHATAI_APK_DEFAULT_URL:-http://${LAN_IP:-127.0.0.1}:${HOST_PORT}}"
IMAGE="${ANDROID_BUILD_IMAGE:-ghcr.io/cirruslabs/android-sdk:35}"
PLATFORM="${ANDROID_BUILD_PLATFORM:-linux/amd64}"
GRADLE_VERSION="${GRADLE_VERSION:-8.10.2}"
GRADLE_DOWNLOAD_URL="${GRADLE_DOWNLOAD_URL:-https://mirrors.cloud.tencent.com/gradle/gradle-${GRADLE_VERSION}-bin.zip}"
GRADLE_FALLBACK_DOWNLOAD_URL="${GRADLE_FALLBACK_DOWNLOAD_URL:-https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip}"
GRADLE_CACHE_DIR="$PWD/.cache/gradle"
DEBUG_KEYSTORE="$PROJECT_DIR/debug.keystore"

mkdir -p "$OUTPUT_DIR"

ensure_debug_keystore() {
  if [ -f "$DEBUG_KEYSTORE" ]; then
    return
  fi

  echo "Creating local debug keystore: $DEBUG_KEYSTORE"
  if command -v keytool >/dev/null 2>&1 && keytool -genkeypair -v \
    -keystore "$DEBUG_KEYSTORE" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" \
    -noprompt >/dev/null; then
    return
  fi

  rm -f "$DEBUG_KEYSTORE"
  docker run --rm \
    --platform "$PLATFORM" \
    -v "$PROJECT_DIR:/workspace" \
    -w /workspace \
    "$IMAGE" \
    bash -lc "keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' -noprompt >/dev/null"
}

ensure_debug_keystore

if command -v gradle >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ]; then
  (
    cd "$PROJECT_DIR"
    gradle :app:clean :app:assembleDebug -PlinkchatDefaultServerUrl="$DEFAULT_URL"
  )
else
  mkdir -p "$GRADLE_CACHE_DIR"
  docker run --rm \
    --platform "$PLATFORM" \
    -v "$PROJECT_DIR:/workspace" \
    -v "$GRADLE_CACHE_DIR:/gradle-cache" \
    -w /workspace \
    "$IMAGE" \
    bash -lc "set -euo pipefail; yes | sdkmanager --licenses >/dev/null 2>&1 || true; sdkmanager 'platforms;android-35' 'build-tools;35.0.0' >/dev/null 2>&1 || true; if [ ! -x /gradle-cache/gradle-${GRADLE_VERSION}/bin/gradle ]; then curl -fL '$GRADLE_DOWNLOAD_URL' -o /gradle-cache/gradle-${GRADLE_VERSION}-bin.zip || curl -fL '$GRADLE_FALLBACK_DOWNLOAD_URL' -o /gradle-cache/gradle-${GRADLE_VERSION}-bin.zip; unzip -q /gradle-cache/gradle-${GRADLE_VERSION}-bin.zip -d /gradle-cache; fi; GRADLE_USER_HOME=/gradle-cache/.gradle /gradle-cache/gradle-${GRADLE_VERSION}/bin/gradle --no-daemon -Dorg.gradle.vfs.watch=false :app:clean :app:assembleDebug -PlinkchatDefaultServerUrl='$DEFAULT_URL'"
fi

cp "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$OUTPUT_DIR/LinkChatBridge-debug.apk"
echo "APK built: $OUTPUT_DIR/LinkChatBridge-debug.apk"
echo "Default LinkChatAI URL baked into APK: $DEFAULT_URL"
