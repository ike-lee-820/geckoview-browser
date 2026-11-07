#!/usr/bin/env sh
# Gradle Wrapper 启动脚本（精简版，行为与官方 wrapper 一致：优先用 JAVA_HOME，否则用 PATH 中的 java）。
set -e
APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ -n "$JAVA_HOME" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD=java; fi
if [ ! -f "$CLASSPATH" ]; then
  echo "gradle-wrapper.jar 缺失。CI 使用 'gradle wrapper' 或 setup-android 生成；也可本地执行一次 gradle wrapper。" >&2
fi
exec "$JAVACMD" $GRADLE_OPTS -Dorg.gradle.appname=gradlew -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
