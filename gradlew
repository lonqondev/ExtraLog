#!/usr/bin/env sh
set -e
base_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ ! -f "$base_dir/gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "Gradle Wrapper jar bulunamadi. Gradle kuruluysa gradle wrapper --gradle-version 8.10.2 calistirin."
  exit 1
fi
exec java -classpath "$base_dir/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
