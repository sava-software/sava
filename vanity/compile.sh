#!/usr/bin/env bash

set -e

./gradlew clean --exclude-task=test :vanity:jlink
