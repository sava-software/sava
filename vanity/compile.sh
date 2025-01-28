#!/usr/bin/env bash

set -e

./gradlew clean --no-daemon --exclude-task=test :vanity:jlink
