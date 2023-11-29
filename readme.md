<p align="center">
  <img src="./resources/wasm4s-logo-transparent.png" />
</p>

# wasm4s

A library to easily run WASM vms from inside your scala project. The wasm vms can be pooled and can auto update themselves when needed. The wasm source can be a file, a base64 payload, an http response, and a [wasmo](https://github.com/maif/wasmo) plugin.

# dependency

first declare the dependency to wasm4s in your `build.sbt`

```scala
libraryDependencies += "fr.maif" %% "wasm4s" % "2.0.0" classifier "bundle"
```

the dependency is quite big as it embed multiarch build of wasmtime and extism.

## how to use it in you project

Wasm4s is supposed to be used in an environment where you are going to store and retrieve configurations of wasm vms with callable functions. Wasm4s provides a trait called  `WasmConfiguration` that you can extend to represent those vm configuration. You need to extend the `WasmConfiguration` trait with your own implementation in order to interact with wasm4s. Wasm4s provides a `BasicWasmConfiguration` implementation, but you can build your own. It doesn't matter if those configuration are just objects in memory or something that is stored durably in a datastore. Wasm4s provides an `InMemoryWasmConfigurationStore` type to store your configuration in memory.

Once you have types that extends `WasmConfiguration`, you'll need to create an integration context class that will provide access to the whole infrastructure needed by wasm4s

for instance, here is the otoroshi integration :

```scala
import io.otoroshi.wasm4s._

class OtoroshiWasmIntegrationContext(env: Env) extends WasmIntegrationContext {

  implicit val ec = env.otoroshiExecutionContext
  implicit val ev = env

  val logger: Logger = Logger("otoroshi-wasm-integration")
  val materializer: Materializer = env.otoroshiMaterializer
  val executionContext: ExecutionContext = env.otoroshiExecutionContext
  val wasmCacheTtl: Long = env.wasmCacheTtl
  val wasmQueueBufferSize: Int = env.wasmQueueBufferSize
  val wasmScriptCache: TrieMap[String, CacheableWasmScript] = new TrieMap[String, CacheableWasmScript]()
  val wasmExecutor: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool(Math.max(32, (Runtime.getRuntime.availableProcessors * 4) + 1))
  )

  override def url(path: String): WSRequest = env.Ws.url(path)

  override def mtlsUrl(path: String, tlsConfig: TlsConfig): WSRequest = {
    val cfg = NgTlsConfig.format.reads(tlsConfig.json).get.legacy
    env.MtlsWs.url(path, cfg)
  }

  override def wasmManagerSettings: Future[Option[WasmManagerSettings]] = env.datastores.globalConfigDataStore.latest().wasmManagerSettings.vfuture

  override def wasmConfig(path: String): Option[WasmConfiguration] = env.proxyState.wasmPlugin(path).map(_.config)

  override def wasmConfigs(): Seq[WasmConfiguration] = env.proxyState.allWasmPlugins().map(_.config)

  override def hostFunctions(config: WasmConfiguration, pluginId: String): Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] = {
    HostFunctions.getFunctions(config.asInstanceOf[WasmConfig], pluginId, None)
  }
}
```

you can create one yourself like :

```scala
val testWasmConfigs: InMemoryWasmConfigurationStore[WasmConfiguration] = InMemoryWasmConfigurationStore(
  "basic" -> BasicWasmConfiguration.fromWasiSource(WasmSource(WasmSourceKind.File, "./src/test/resources/basic.wasm")),
  "opa" -> BasicWasmConfiguration.fromOpaSource(WasmSource(WasmSourceKind.File, "./src/test/resources/opa.wasm")),
)

class FooWasmIntegrationContext(env: Env) extends WasmIntegrationContext {
  val system = ActorSystem("foo-wasm")
  val materializer: Materializer = Materializer(system)
  val executionContext: ExecutionContext = system.dispatcher
  val logger: Logger = Logger("foo-wasm")
  val wasmCacheTtl: Long = 2000
  val wasmQueueBufferSize: Int = 100
  val wasmManagerSettings: Future[Option[WasmManagerSettings]] = Future.successful(None)
  val wasmScriptCache: TrieMap[String, CacheableWasmScript] = new TrieMap[String, CacheableWasmScript]()
  val wasmExecutor: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newWorkStealingPool(Math.max(32, (Runtime.getRuntime.availableProcessors * 4) + 1))
  )
  override def url(path: String): WSRequest = ??? // we do not provide http call right now ;)
  override def mtlsUrl(path: String, tlsConfig: TlsConfig): WSRequest = ???  // we do not provide http call right now ;)
  override def wasmConfig(path: String): Option[WasmConfiguration] = testWasmConfigs.wasmConfiguration(path)
  override def wasmConfigs(): Seq[WasmConfiguration] = testWasmConfigs.wasmConfigurations()
  override def hostFunctions(config: WasmConfiguration, pluginId: String): Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] = Array.empty
}
```

you can also use `DefaultWasmIntegrationContext` and `DefaultWasmIntegrationContextWithNoHttpClient` types that provides default settings for your `WasmIntegrationContext`.

then instanciate a wasm integration 

```scala
val wasmIntegration = WasmIntegration(new FooWasmIntegrationContext(env))
```

now you have to trigger jobs that will cache wasm stuff and clean vm. you can either do it manually or let the integration do it.

```scala
wasmIntegration.start()
Runtime.getRuntime().addShutdownHook(() => {
  wasmIntegration.stop()
})
```

now you can get a wasm vm through the wasm integration object and use it

```scala
wasmIntegration.withPooledVm(basicConfiguration) { vm =>
  vm.callExtismFunction(
    "execute",
    Json.obj("message" -> "coucou").stringify
  ).map {
    case Left(error) => println(s"error: ${error.prettify}")
    case Right(out) => {
      assertEquals(out, "{\"input\":{\"message\":\"coucou\"},\"message\":\"yo\"}")
      println(s"output: ${out}")
    }
  }
}
```
