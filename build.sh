#!/bin/bash

echo "Cleaning old artifacts..."
rm -f ./target/scala-2.13/wasm4s_2.13-*.jar
rm -f ./target/scala-2.13/wasm4s-bundle_2.13-*.jar
rm -f ./target/scala-3.7.1/wasm4s_3-*.jar
rm -f ./target/scala-3.7.1/wasm4s-bundle_3-*.jar

echo "Building main project..."
sbt '+package'
sbt '+assembly'

echo "Build complete!"
