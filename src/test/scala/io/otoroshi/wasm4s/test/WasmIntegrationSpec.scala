// package io.otoroshi.wasm4s.test

// import io.otoroshi.wasm4s.scaladsl._
// import io.otoroshi.wasm4s.scaladsl.implicits._
// import play.api.libs.json.{Json, JsValue}

// import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
// import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
// import scala.concurrent.duration._
// import scala.concurrent.{Await, ExecutionContext, Future}
// import scala.util.Random

// class WasmIntegrationSpec extends munit.FunSuite {

//   val wasmStore = InMemoryWasmConfigurationStore(
//     "basic" -> BasicWasmConfiguration.fromWasiSource(WasmSource(WasmSourceKind.File, "./src/test/resources/basic.wasm")),
//     "opa" -> BasicWasmConfiguration.fromOpaSource(WasmSource(WasmSourceKind.File, "./src/test/resources/opa.wasm"))
//   )

//   implicit val intctx: BasicWasmIntegrationContextWithNoHttpClient[BasicWasmConfiguration] = 
//     BasicWasmIntegrationContextWithNoHttpClient("integration-test-wasm4s", wasmStore)
//   val wasmIntegration = WasmIntegration(intctx)

//   wasmIntegration.runVmLoaderJob()

//   override def beforeAll(): Unit = {
//     wasmIntegration.start(Json.obj())
//   }

//   override def afterAll(): Unit = {
//     wasmIntegration.stop()
//   }

//   test("concurrent VM pooling with chaos - mixed workload of fast, slow, and failing calls") {
//     import wasmIntegration.executionContext
    
//     val numThreads = 8
//     val callsPerThread = 10
//     val successCount = new AtomicInteger(0)
//     val failureCount = new AtomicInteger(0)
//     val slowCallCount = new AtomicInteger(0)
//     val latch = new CountDownLatch(numThreads)
    
//     val config = wasmStore.wasmConfigurationUnsafe("basic")
    
//     // Create multiple threads with different behavior patterns
//     val futures = (1 to numThreads).map { threadId =>
//       Future {
//         try {
//           (1 to callsPerThread).foreach { callId =>
//             val callType = (threadId + callId) % 4
            
//             val result = try {
//               callType match {
//                 case 0 => // Fast successful call
//                   Await.result(
//                     wasmIntegration.withPooledVm(config) { vm =>
//                       vm.callExtismFunction("execute", Json.obj("message" -> s"fast-$threadId-$callId").stringify)
//                     },
//                     5.seconds
//                   )
                
//                 case 1 => // Slow call (hold VM longer)
//                   slowCallCount.incrementAndGet()
//                   Await.result(
//                     wasmIntegration.withPooledVm(config) { vm =>
//                       Thread.sleep(200) // Hold VM for 200ms
//                       vm.callExtismFunction("execute", Json.obj("message" -> s"slow-$threadId-$callId").stringify)
//                     },
//                     10.seconds
//                   )
                
//                 case 2 => // Failing call (non-existent function)
//                   Await.result(
//                     wasmIntegration.withPooledVm(config) { vm =>
//                       vm.callExtismFunction("nonexistent_function", s"fail-$threadId-$callId")
//                     },
//                     5.seconds
//                   )
                
//                 case 3 => // Normal call
//                   Await.result(
//                     wasmIntegration.withPooledVm(config) { vm =>
//                       vm.callExtismFunction("execute", Json.obj("message" -> s"normal-$threadId-$callId").stringify)
//                     },
//                     5.seconds
//                   )
//               }
//             } catch {
//               case _: Exception => Left(Json.obj("error" -> "Call failed"))
//             }
            
//             result match {
//               case Left(_) => failureCount.incrementAndGet()
//               case Right(_) => successCount.incrementAndGet()
//             }
            
//             // Small random delay to increase concurrency chaos
//             Thread.sleep(Random.nextInt(10))
//           }
//         } finally {
//           latch.countDown()
//         }
//       }
//     }
    
//     // Wait for all threads to complete
//     assert(latch.await(60, TimeUnit.SECONDS), "All threads should complete within 60 seconds")
    
//     Await.ready(Future.sequence(futures), 65.seconds)
    
//     val totalCalls = numThreads * callsPerThread
//     val actualTotal = successCount.get() + failureCount.get()
    
//     println(s"Chaos test results: ${successCount.get()} successes, ${failureCount.get()} failures, ${slowCallCount.get()} slow calls")
    
//     assertEquals(actualTotal, totalCalls, "All calls should be accounted for")
//     assert(successCount.get() > 0, "Some calls should succeed")
//     assert(failureCount.get() > 0, "Some calls should fail (by design)")
//     assert(slowCallCount.get() > 0, "Some calls should be slow (by design)")
    
//     // Most importantly: the pool should survive the chaos and continue working
//     val recoveryResult = Await.result(
//       wasmIntegration.withPooledVm(config) { vm =>
//         vm.callExtismFunction("execute", Json.obj("message" -> "recovery-test").stringify)
//       },
//       10.seconds
//     )
    
//     assert(recoveryResult.isRight, "Pool should remain functional after chaos testing")
//   }

//   test("VM lifecycle - VMs should be properly created, reused, and cleaned up") {
//     import wasmIntegration.executionContext
    
//     val config = wasmStore.wasmConfigurationUnsafe("basic")
//     val vmIds = collection.mutable.Set[Int]()
    
//     // Make multiple calls and track VM instances used
//     val futures = (1 to 10).map { i =>
//       wasmIntegration.withPooledVm(config) { vm =>
//         vmIds.synchronized {
//           vmIds += vm.index
//         }
//         vm.callExtismFunction("execute", Json.obj("message" -> s"call-$i").stringify).map {
//           case Left(error) => Left(error)
//           case Right(_) => Right(vm.index)
//         }
//       }
//     }
    
//     val results = Await.result(Future.sequence(futures), 15.seconds)
    
//     // Should have reused some VMs (fewer unique VMs than total calls)
//     assert(vmIds.size < 10, s"Should reuse VMs: used ${vmIds.size} unique VMs for 10 calls")
//     assert(vmIds.size >= 1, "Should use at least 1 VM")
    
//     results.foreach { result =>
//       assert(result.isRight, "All calls should succeed")
//     }
//   }

//   test("caching behavior - WASM binaries should be cached and reused") {
//     import wasmIntegration.executionContext
    
//     val config = wasmStore.wasmConfigurationUnsafe("basic")
//     val source = config.source
    
//     // Clear any existing cache to ensure clean test state
//     source.removeFromCache()
    
//     // Verify WASM is not cached initially
//     assert(!source.isCached(), "WASM should not be cached initially")
    
//     val result1 = Await.result(
//       wasmIntegration.withPooledVm(config) { vm =>
//         vm.callExtismFunction("execute", Json.obj("message" -> "cache-test-1").stringify)
//       },
//       10.seconds
//     )
    
//     assert(result1.isRight, "First call should succeed")
//     assert(source.isCached(), "WASM should be cached after first use")
    
//     // Second call should use cached WASM
//     val result2 = Await.result(
//       wasmIntegration.withPooledVm(config) { vm =>
//         vm.callExtismFunction("execute", Json.obj("message" -> "cache-test-2").stringify)
//       },
//       10.seconds
//     )
    
//     assert(result2.isRight, "Second call should succeed using cached WASM")
//   }

//   test("error handling - should gracefully handle WASM execution failures") {
//     import wasmIntegration.executionContext
    
//     val config = wasmStore.wasmConfigurationUnsafe("basic")
    
//     // Test with invalid function name - handle both Left values and exceptions
//     val invalidFunctionResult = try {
//       val result = Await.result(
//         wasmIntegration.withPooledVm(config) { vm =>
//           vm.callExtismFunction("nonexistent_function", "test")
//         },
//         10.seconds
//       )
//       result
//     } catch {
//       case _: Exception => Left(Json.obj("error" -> "Function not found"))
//     }
    
//     assert(invalidFunctionResult.isLeft, "Calling non-existent function should fail gracefully")
    
//     // VM pool should still work for valid calls after errors
//     val validResult = Await.result(
//       wasmIntegration.withPooledVm(config) { vm =>
//         vm.callExtismFunction("execute", Json.obj("message" -> "recovery-test").stringify)
//       },
//       10.seconds
//     )
    
//     assert(validResult.isRight, "Valid calls should work after handling errors")
//   }

//   test("integration scenario - simulated web request processing") {
//     import wasmIntegration.executionContext
    
//     val config = wasmStore.wasmConfigurationUnsafe("basic")
//     val opaConfig = wasmStore.wasmConfigurationUnsafe("opa")
    
//     // Simulate processing multiple web requests with different types of WASM processing
//     case class SimulatedRequest(id: String, payload: String, needsAuth: Boolean)
    
//     val requests = (1 to 15).map { i =>
//       SimulatedRequest(
//         s"req-$i",
//         Json.obj("user" -> s"user$i", "action" -> "read", "resource" -> s"doc$i").stringify,
//         i % 3 == 0 // Every third request needs auth
//       )
//     }
    
//     val processedRequests = Await.result(
//       Future.traverse(requests) { request =>
//         for {
//           // Step 1: Basic processing with WASM
//           basicResult <- wasmIntegration.withPooledVm(config) { vm =>
//             vm.callExtismFunction("execute", Json.obj("message" -> request.payload).stringify)
//           }
          
//           // Step 2: OPA authorization if needed
//           authResult <- if (request.needsAuth) {
//             wasmIntegration.withPooledVm(opaConfig) { vm =>
//               vm.callOpa("execute", request.payload).map {
//                 case Left(error) => Left(s"Auth failed: $error")
//                 case Right((result, _)) => Right(s"Auth: $result")
//               }
//             }
//           } else {
//             Future.successful(Right("No auth needed"))
//           }
          
//         } yield (request.id, basicResult, authResult)
//       },
//       30.seconds
//     )
    
//     // Verify all requests were processed
//     assertEquals(processedRequests.length, requests.length, "All requests should be processed")
    
//     processedRequests.foreach { case (requestId, basicResult, authResult) =>
//       assert(basicResult.isRight, s"Basic processing should succeed for $requestId")
//       assert(authResult.isRight, s"Auth processing should succeed for $requestId")
//     }
    
//     // Verify that auth was actually performed for the right requests
//     val authRequests = processedRequests.filter { case (id, _, authResult) =>
//       authResult.exists(_.contains("Auth: "))
//     }
    
//     assert(authRequests.length == requests.count(_.needsAuth), "Auth should be performed for the right number of requests")
//   }

//   test("resource management - VMs should enforce call limits and VM recycling") {
//     import wasmIntegration.executionContext
    
//     // Create config with very strict call limits to force VM recycling
//     val baseConfig = wasmStore.wasmConfigurationUnsafe("basic")
//     val strictConfig = baseConfig.copy(
//       instances = 1,    // Single instance to force recycling
//       killOptions = WasmVmKillOptions(
//         maxCalls = 3,    // Kill VM after only 3 calls
//         maxMemoryUsage = 0.9,
//         maxUnusedDuration = 100.millis  // Very short unused duration
//       )
//     )
    
//     val vmIndices = collection.mutable.Set[Int]()
    
//     // Make more calls than maxCalls to force VM recycling
//     val results = Await.result(
//       Future.sequence((1 to 10).map { i =>
//         wasmIntegration.withPooledVm(strictConfig) { vm =>
//           vmIndices.synchronized {
//             vmIndices += vm.index
//           }
//           // Add small delay to allow VM cleanup between calls
//           Thread.sleep(50)
//           vm.callExtismFunction("execute", Json.obj("message" -> s"recycling-test-$i").stringify).map {
//             case Left(error) => Left(error)
//             case Right(result) => Right((vm.index, result))
//           }
//         }
//       }),
//       30.seconds
//     )
    
//     results.foreach { result =>
//       assert(result.isRight, "All calls should succeed despite VM recycling")
//     }
    
//     // Should have used multiple VM instances due to recycling
//     assert(vmIndices.size > 1, s"Should have recycled VMs (used ${vmIndices.size} different VM indices)")
//     println(s"VM recycling test used ${vmIndices.size} different VMs for 10 calls (max 3 calls per VM)")
//   }
// }