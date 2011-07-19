#!/usr/bin/env bash
set -ueo pipefail
scriptDir=$(scala -e 'println(new java.io.File("'$(dirname $0)'").getAbsolutePath)')

$scriptDir/get_deps.sh

echo >&2 "Building source..."
mkdir -p $scriptDir/bin
fsc -cp $scriptDir/lib/scalatest-1.6.1.jar \
    -d $scriptDir/bin/ \
    $scriptDir/scala/*.scala

echo >&2 "Building JAR..."
(cd $scriptDir/bin; zip -qr $scriptDir/ducttape.jar *)