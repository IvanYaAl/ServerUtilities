#!/bin/bash
# Сборка: ./build.sh <путь к paper-api или папке libraries сервера Paper>
set -e
cd "$(dirname "$0")"
LIBS="${1:-libs}"
CP=$(find "$LIBS" -name "*.jar" | tr '\n' ':')
VER=$(grep '^version:' resources/plugin.yml | awk '{print $2}')
rm -rf build && mkdir -p build/classes
javac -proc:none -nowarn --release 21 -encoding UTF-8 -cp "$CP" -d build/classes $(find src -name "*.java")
cp resources/*.yml build/classes/
jar --create --file "build/ServerUtilities-$VER.jar" -C build/classes .
echo "OK: build/ServerUtilities-$VER.jar"
