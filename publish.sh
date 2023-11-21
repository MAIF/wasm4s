#!/bin/sh

echo "ThisBuild / version := \"$1\"" > version.sbt
sh ./update-extism.sh
sbt ';+compile;+test;+package;+assembly;+doc;+packageDoc;+publishSigned;sonatypeBundleRelease'
echo 'ThisBuild / version := "dev"' > version.sbt
