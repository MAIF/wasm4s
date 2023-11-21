rm ./target/scala-2.12/wasm4s_2.12-*.jar
rm ./target/scala-2.13/wasm4s_2.13-*.jar
rm ./target/scala-2.12/wasm4s-bundle_2.12-*.jar
rm ./target/scala-2.13/wasm4s-bundle_2.13-*.jar
sbt '+package'
sbt '+assembly'
