package io.otoroshi.wasm4s.test

import io.otoroshi.wasm4s.scaladsl._
import io.otoroshi.wasm4s.scaladsl.implicits._
import play.api.libs.json.{JsArray, Json}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class WasmSpec extends munit.FunSuite {

  val wasmStore = InMemoryWasmConfigurationStore(
    "basiccp" -> BasicWasmConfiguration.fromWasiSource(WasmSource(WasmSourceKind.ClassPath, "basic.wasm")),
    "basic" -> BasicWasmConfiguration.fromWasiSource(WasmSource(WasmSourceKind.File, "./src/test/resources/basic.wasm")),
    "opa" -> BasicWasmConfiguration.fromOpaSource(WasmSource(WasmSourceKind.File, "./src/test/resources/opa.wasm")),
    "coraza" -> BasicWasmConfiguration.fromOpaSource(WasmSource(WasmSourceKind.File, "./src/test/resources/coraza.wasm")),
  )

  implicit val intctx = BasicWasmIntegrationContextWithNoHttpClient("test-wasm4s", wasmStore)
  val wasmIntegration = WasmIntegration(intctx)

  wasmIntegration.runVmLoaderJob()

  test("basic setup with manual release should work") {

    import wasmIntegration.executionContext

    val fu = wasmIntegration.wasmVmById("basic").flatMap {
      case Some((vm, _)) => {
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
        .andThen {
          case _ => vm.release()
        }
      }
      case _ =>
        println("vm not found !")
        ().vfuture
    }
    Await.result(fu, 10.seconds)
  }

  test("basic setup with auto release should work") {

    import wasmIntegration.executionContext

    val fu = wasmIntegration.withPooledVm(wasmStore.wasmConfigurationUnsafe("basic")) { vm =>
      vm.callExtismFunction("execute", Json.obj("message" -> "coucou").stringify).map {
        case Left(error) => println(s"error: ${error.prettify}")
        case Right(out) =>
          assertEquals(out, "{\"input\":{\"message\":\"coucou\"},\"message\":\"yo\"}")
          println(s"output: ${out}")
      }
    }

    Await.result(fu, 10.seconds)
  }

  test("initialize coraza plugin") {
    import wasmIntegration.executionContext

    val wasmConfig = BasicWasmConfiguration(
        source = wasmStore.wasmConfigurationUnsafe("coraza").source,
        memoryPages = 10000,
        functionName = None,
        instances = 1,
        wasi = true,
        killOptions = WasmVmKillOptions(
          maxCalls = 2000,
          maxMemoryUsage = 0.9,
          maxAvgCallDuration = 1.day,
          maxUnusedDuration = 5.minutes
        ),
        allowedPaths = Map("/tmp" -> "/tmp"))

    val pool: WasmVmPool = WasmVmPool.forConfigurationWithId("key", wasmConfig)


    def getCorazaVm = {
      val start = System.currentTimeMillis()
      pool
        .getPooledVm(WasmVmInitOptions(importDefaultHostFunctions = false, resetMemory = false, _ => Seq.empty))
        .flatMap { vm =>
          vm.finitialize {
              val directives = Seq("Include @recommended-conf",
                "Include @crs-setup-conf",
                "SecRequestBodyAccess On",
                "SecResponseBodyAccess On",
                "Include @owasp_crs/*.conf",
                "SecRuleEngine On"
              )

              vm.callCorazaNext("initialize", "", None, Json.stringify(Json.obj(
                "directives" -> directives.mkString("\n"),
                "inspect_bodies" -> true
              )).some)
            }
            .andThen { case _ =>
              vm.release()
            }
        }
    }

    getCorazaVm
      .flatMap(_ => getCorazaVm)
  }

  test("opa manual setup with auto release should work") {
    import wasmIntegration.executionContext
    val callCtx = Json.obj("request" -> Json.obj("headers" -> Json.obj("foo" -> "bar"))).stringify
    val cc = callCtx
    println(s"payload size: ${cc.size}")
    val fu = wasmIntegration.withPooledVm(wasmStore.wasmConfigurationUnsafe("opa")) { vm =>
      vm.ensureOpaInitialized(cc.some).call(
        WasmFunctionParameters.OPACall("execute", vm.getOpaPointers(), cc), None
      ).map {
        case Left(error) => println(s"error: ${error.prettify}")
        case Right((out, _)) => {
          println(s"opa output: ${out}")
        }
      }
    }

    Await.result(fu, 600.seconds)
  }

  test("opa auto setup with auto release should work") {

    import wasmIntegration.executionContext

    val callCtx = Json.obj("request" -> Json.obj("headers" -> Json.obj("foo" -> "bar"))).stringify
    val fu = wasmIntegration.withPooledVm(wasmStore.wasmConfigurationUnsafe("opa")) { vm =>
      vm.callOpa("execute", callCtx).map {
        case Left(error) => println(s"error: ${error.prettify}")
        case Right((out, wrapper)) => {
          println(s"opa output: ${out}")
        }
      }
    }

    Await.result(fu, 10.seconds)
  }

  test("check if an aquired vm is acquired") {
    val callCtx = Json.obj("request" -> Json.obj("headers" -> Json.obj("foo" -> "bar"))).stringify

    val pool = wasmStore.wasmConfigurationUnsafe("basic").pool(100000)
    val vm =  Await.result(pool.getPooledVm(), 10.seconds).asInstanceOf[io.otoroshi.wasm4s.impl.WasmVmImpl]
    assertEquals(vm.isAquired(), true)
    vm.release()
    assertEquals(vm.isAquired(), false)
  }

  test("classpath source should work") {

    import wasmIntegration.executionContext

    val fu = wasmIntegration.withPooledVm(wasmStore.wasmConfigurationUnsafe("basiccp")) { vm =>
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

    Await.result(fu, 10.seconds)
  }
}
