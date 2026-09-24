#!/bin/sh
set -eu

tag="${1:?release tag is required}"
version="$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"

if [ "$tag" != "v$version" ]; then
    echo "Release tag $tag does not match Maven version $version" >&2
    exit 1
fi

echo "Release tag $tag matches Maven version $version"
