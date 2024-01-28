package io.otoroshi.wasm4s.impl

import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import com.codahale.metrics.UniformReservoir
import io.otoroshi.wasm4s.scaladsl.CacheableWasmScript.CachedWasmScript
import io.otoroshi.wasm4s.scaladsl._
import io.otoroshi.wasm4s.scaladsl.opa._
import io.otoroshi.wasm4s.scaladsl.implicits._
import io.otoroshi.wasm4s.impl.WasmVmPoolImpl.logger
import org.extism.sdk.{HostFunction, HostUserData, Plugin}
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.wasm.WasmSourceResolver
import org.extism.sdk.wasmotoroshi._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

sealed trait WasmVmAction

object WasmVmAction {
  case object WasmVmKillAction extends WasmVmAction
  case class WasmVmCallAction(
                               parameters: WasmFunctionParameters,
                               context: Option[WasmVmData],
                               promise: Promise[Either[JsValue, (String, ResultsWrapper)]]
  )                            extends WasmVmAction
}

case class OPAWasmVm(opaDataAddr: Int, opaBaseHeapPtr: Int)

case class WasmVmImpl(
                       index: Int,
                       maxCalls: Int,
                       maxMemory: Long,
                       resetMemory: Boolean,
                       instance: Plugin,
                       vmDataRef: AtomicReference[WasmVmData],
                       memories: Array[LinearMemory],
                       functions: Array[HostFunction[_ <: HostUserData]],
                       pool: WasmVmPoolImpl,
                       var opaPointers: Option[OPAWasmVm] = None
) extends WasmVm {

  private val callDurationReservoirNs       = new UniformReservoir()
  private val lastUsage: AtomicLong         = new AtomicLong(System.currentTimeMillis())
  private val initializedRef: AtomicBoolean = new AtomicBoolean(false)
  private val killAtRelease: AtomicBoolean  = new AtomicBoolean(false)
  private val inFlight                      = new AtomicInteger(0)
  private val callCounter                   = new AtomicInteger(0)
  private val queue = {
    val env = pool.ic
    Source
      .queue[WasmVmAction](env.wasmQueueBufferSize, OverflowStrategy.dropTail)
      .mapAsync(1)(handle)
      .toMat(Sink.ignore)(Keep.both)
      .run()(env.materializer)
      ._1
  }

  def calls: Int   = callCounter.get()
  def current: Int = inFlight.get()

  private def handle(act: WasmVmAction): Future[Unit] = {
    Future.apply {
      lastUsage.set(System.currentTimeMillis())
      act match {
        case WasmVmAction.WasmVmKillAction         => destroy()
        case action: WasmVmAction.WasmVmCallAction => {
          try {
            inFlight.decrementAndGet()
            // action.context.foreach(ctx => WasmContextSlot.setCurrentContext(ctx))
            action.context.foreach(ctx => vmDataRef.set(ctx))
            if (pool.ic.logger.isDebugEnabled)
              pool.ic.logger.debug(s"call vm ${index} with method ${action.parameters.functionName} on thread ${Thread
                .currentThread()
                .getName} on path ${action.context.map(_.properties.get("request.path").map(v => new String(v))).getOrElse("--")}")
            val start = System.nanoTime()
            val res   = action.parameters.call(instance)
            callDurationReservoirNs.update(System.nanoTime() - start)
            if (res.isRight && res.right.get._2.results.getValues() != null) {
              val ret = res.right.get._2.results.getValues()(0).v.i32
              if (ret > 7 || ret < 0) { // weird multi thread issues
                ignore()
                killAtRelease.set(true)
              }
            }
            action.promise.trySuccess(res)
          } catch {
            case t: Throwable => action.promise.tryFailure(t)
          } finally {
            if (resetMemory) {
              action.parameters match {
                case _m: WasmFunctionParameters.ExtismFuntionCall => instance.reset()
                case _m: WasmFunctionParameters.OPACall => // the memory data will already be overwritten during the next call
                case _ => instance.resetCustomMemory()
              }
            }
            pool.ic.logger.debug(s"functions: ${functions.size}")
            pool.ic.logger.debug(s"memories: ${memories.size}")
            // WasmContextSlot.clearCurrentContext()
            // vmDataRef.set(null)
            val count = callCounter.incrementAndGet()
            println("MAX COUNT", count, maxCalls)
            if (count >= maxCalls) {
              callCounter.set(0)
              if (pool.ic.logger.isDebugEnabled)
                pool.ic.logger.debug(s"killing vm ${index} with remaining ${inFlight.get()} calls (${count})")
//              destroyAtRelease()
            }
          }
        }
      }
      ()
    }(pool.ic.wasmExecutor)
  }

  def getOpaPointers(): Option[OPAWasmVm] = opaPointers

  def reset(): Unit = instance.reset()

  def destroy(): Unit = {
    if (pool.ic.logger.isDebugEnabled) pool.ic.logger.debug(s"destroy vm: ${index}")
    pool.ic.logger.debug(s"destroy vm: ${index}")
    pool.clear(this)
    instance.close()
  }

  def isAquired(): Boolean = {
    pool.inUseVms.contains(this)
  }

  def isBusy(): Boolean = {
    inFlight.get() > 0
  }

  def destroyAtRelease(): Unit = {
    ignore()
//    killAtRelease.set(true)
  }

  def release(): Unit = {
    if (killAtRelease.get()) {
      queue.offer(WasmVmAction.WasmVmKillAction)
    } else {
      pool.release(this)
    }
  }

  def lastUsedAt(): Long = lastUsage.get()

  def hasNotBeenUsedInTheLast(duration: FiniteDuration): Boolean =
    if (duration.toNanos == 0L) false else !hasBeenUsedInTheLast(duration)

  def consumesMoreThanMemoryPercent(percent: Double): Boolean = if (percent == 0.0) {
    false
  } else {
    val consumed: Double = instance.getMemorySize.toDouble / maxMemory.toDouble
    val res              = consumed > percent
    if (pool.ic.logger.isDebugEnabled)
      pool.ic.logger.debug(
        s"consumesMoreThanMemoryPercent($percent) = (${instance.getMemorySize} / $maxMemory) > $percent : $res : (${consumed * 100.0}%)"
      )
    res
  }

  def tooSlow(max: Long): Boolean = {
    if (max == 0L) {
      false
    } else {
      callDurationReservoirNs.getSnapshot.getMean.toLong > max
    }
  }

  def hasBeenUsedInTheLast(duration: FiniteDuration): Boolean = {
    val now   = System.currentTimeMillis()
    val limit = lastUsage.get() + duration.toMillis
    now < limit
  }

  def ignore(): Unit = pool.ignore(this)

  def initialized(): Boolean = initializedRef.get()

  def initialize(f: => Any): Unit = {
    if (initializedRef.compareAndSet(false, true)) {
      f
    }
  }

  def finitialize[A](f: => Future[A]): Future[Unit] = {
    if (initializedRef.compareAndSet(false, true)) {
      f.map(_ => ())(pool.ic.executionContext)
    } else {
      ().vfuture
    }
  }

  def call(
      parameters: WasmFunctionParameters,
      context: Option[WasmVmData]
  ): Future[Either[JsValue, (String, ResultsWrapper)]] = {
    val promise = Promise[Either[JsValue, (String, ResultsWrapper)]]()
    inFlight.incrementAndGet()
    lastUsage.set(System.currentTimeMillis())
    queue.offer(WasmVmAction.WasmVmCallAction(parameters, context, promise))
    promise.future
  }

  def callOpa(functionName: String, in: String, context: Option[WasmVmData] = None)(implicit ec: ExecutionContext): Future[Either[JsValue, (String, ResultsWrapper)]] = {
    ensureOpaInitialized().call(WasmFunctionParameters.OPACall(functionName, opaPointers, in), context)
  }

  def ensureOpaInitializedAsync(in: Option[String] = None)(implicit ec: ExecutionContext): Future[WasmVmImpl] = {
    if (!initialized()) {
      call(
        WasmFunctionParameters.OPACall(
          "initialize",
          in = in.getOrElse(Json.obj().stringify),
        ),
        None
      ) flatMap {
        case Left(error) => Future.failed(new RuntimeException(s"opa initialize error: ${error.stringify}"))
        case Right(value) =>
          initialize {
            val pointers = Json.parse(value._1)
            opaPointers = OPAWasmVm(
              opaDataAddr = (pointers \ "dataAddr").as[Int],
              opaBaseHeapPtr = (pointers \ "baseHeapPtr").as[Int]
            ).some
          }
          this.vfuture
      }
    } else {
      this.vfuture
    }
  }

  def ensureOpaInitialized(in: Option[String] = None)(implicit ec: ExecutionContext): WasmVmImpl = {
    Await.result(
      ensureOpaInitializedAsync(in),
      10.seconds
    )
  }
}

case class WasmVmPoolAction(promise: Promise[WasmVmImpl], options: WasmVmInitOptions) {
  private[wasm4s] def provideVm(vm: WasmVmImpl): Unit = promise.trySuccess(vm)
  private[wasm4s] def fail(e: Throwable): Unit    = promise.tryFailure(e)
}

object WasmVmPoolImpl {

  private[wasm4s] val logger = Logger("otoroshi-wasm-vm-pool")
  private val instances    = new TrieMap[String, WasmVmPoolImpl]()

  def allInstances(): Map[String, WasmVmPoolImpl] = instances.synchronized {
    instances.toMap
  }

  def forConfig(config: => WasmConfiguration, maxCallsBetweenUpdates: Int = 100000)(implicit ic: WasmIntegrationContext): WasmVmPoolImpl = instances.synchronized {
    val key = s"${config.source.cacheKey}?mcbu=${maxCallsBetweenUpdates}&cfg=${config.json.stringify.sha512}"
    instances.getOrUpdate(key) {
      new WasmVmPoolImpl(key, config.some, maxCallsBetweenUpdates, ic)
    }
  }

  private[wasm4s] def removePlugin(id: String): Unit = instances.synchronized {
    instances.remove(id)
  }
}

class WasmVmPoolImpl(stableId: => String, optConfig: => Option[WasmConfiguration], maxCallsBetweenUpdates: Int = 100000, val ic: WasmIntegrationContext) extends WasmVmPool {

  WasmVmPoolImpl.logger.debug("new WasmVmPool")

  private val counter              = new AtomicInteger(-1)
  private[wasm4s] val availableVms   = new ConcurrentLinkedQueue[WasmVmImpl]()
  private[wasm4s] val inUseVms       = new ConcurrentLinkedQueue[WasmVmImpl]()
  private val lastCacheUpdateTime  = new AtomicLong(System.currentTimeMillis())
  private val lastCacheUpdateCalls = new AtomicLong(System.currentTimeMillis())
  private val creatingRef          = new AtomicBoolean(false)
  private val lastPluginVersion    = new AtomicReference[String](null)
  private val requestsSource       = Source.queue[WasmVmPoolAction](ic.wasmQueueBufferSize, OverflowStrategy.dropTail)
  private val prioritySource       = Source.queue[WasmVmPoolAction](ic.wasmQueueBufferSize, OverflowStrategy.dropTail)
  private val (priorityQueue, requestsQueue) = {
    prioritySource
      .mergePrioritizedMat(requestsSource, 99, 1, false)(Keep.both)
      .map(handleAction)
      .toMat(Sink.ignore)(Keep.both)
      .run()(ic.materializer)
      ._1
  }

  // unqueue actions from the action queue
  private def handleAction(action: WasmVmPoolAction): Unit = try {
    val time = System.currentTimeMillis()
    wasmConfig() match {
      case None       =>
        // if we cannot find the current wasm config, something is wrong, we destroy the pool
        destroyCurrentVms()
        WasmVmPoolImpl.removePlugin(stableId)
        action.fail(new RuntimeException(s"No more plugin ${stableId}"))
      case Some(wcfg) => {
        // first we ensure the wasm source has been fetched
        if (!wcfg.source.isCached()(ic)) {
          wcfg.source
            .getWasm()(ic, ic.executionContext)
            .andThen { case _ =>
              priorityQueue.offer(action)
            }(ic.executionContext)
        } else {
          // try to self refresh cache if more call than or time elapsed
          if (ic.selfRefreshingPools && (((time - lastCacheUpdateTime.get()) > ic.wasmCacheTtl) || (lastCacheUpdateCalls.get() > maxCallsBetweenUpdates))) {
            lastCacheUpdateTime.set(time)
            lastCacheUpdateCalls.set(0L)
            wcfg.source.getWasm()(ic, ic.executionContext)
          }
          // TODO: try to refresh cache if more than n calls or env.wasmCacheTtl elasped since last time
          val changed   = hasChanged(wcfg)
          val available = hasAvailableVm(wcfg)
          val creating  = isVmCreating()
          val atMax     = atMaxPoolCapacity(wcfg)
          // then we check if the underlying wasmcode + config has not changed since last time
          if (changed) {
            // if so, we destroy all current vms and recreate a new one
            WasmVmPoolImpl.logger.warn("plugin has changed, destroying old instances")
            destroyCurrentVms()
            createVm(wcfg, action.options)
          }
          // check if a vm is available
          if (!available) {
            // if not, but a new one is creating, just wait a little bit more
            if (creating) {
              priorityQueue.offer(action)
            } else {
              // check if we hit the max possible instances
              if (atMax) {
                // if so, just wait
                priorityQueue.offer(action)
              } else {
                // if not, create a new instance because we need one
                createVm(wcfg, action.options)
                priorityQueue.offer(action)
              }
            }
          } else {
            // if so, acquire one
            val vm = acquireVm()
            action.provideVm(vm)
          }
        }
      }
    }
  } catch {
    case t: Throwable =>
      t.printStackTrace()
      action.fail(t)
  }

  // create a new vm for the pool
  // we try to create vm one by one and to not create more than needed
  private def createVm(config: WasmConfiguration, options: WasmVmInitOptions): Unit = synchronized {
    if (creatingRef.compareAndSet(false, true)) {
      val index                                                                     = counter.incrementAndGet()
      WasmVmPoolImpl.logger.debug(s"creating vm: ${index}")

      if (!config.source.isCached()(ic)) {
        // this part should never happen anymore, but just in case
        WasmVmPoolImpl.logger.warn("fetching missing source")
        Await.result(config.source.getWasm()(ic, ic.executionContext), 30.seconds)
      }
      lastPluginVersion.set(computeHash(config, config.source.cacheKey, ic.wasmScriptCache))
      val cache    = ic.wasmScriptCache
      val key      = config.source.cacheKey
      val wasm     = cache(key) match {
        case CacheableWasmScript.CachedWasmScript(script, _) => script
        case CacheableWasmScript.FetchingCachedWasmScript(_, script) => script
        case _ => throw new RuntimeException("unable to get wasm source from cache. this should not happen !")
      }
//        val hash     = wasm.sha256
      val resolver = new WasmSourceResolver()
      val source   = resolver.resolve("wasm", wasm.toByteBuffer.array())

      val vmDataRef                                                                 = new AtomicReference[WasmVmData](null)
      val addedFunctions                                                            = options.addHostFunctions(vmDataRef)
      val functions: Array[HostFunction[_ <: HostUserData]] = {
        val fs: Array[HostFunction[_ <: HostUserData]] = if (options.importDefaultHostFunctions) {
          ic.hostFunctions(config, stableId) ++ addedFunctions
        } else {
          addedFunctions.toArray[HostFunction[_ <: HostUserData]]
        }
        if (config.opa) {
          val opaFunctions: Seq[HostFunction[_ <: HostUserData]] = OPA.getFunctions(config).collect {
            case func if func.authorized(config) => func.function
          }
          fs ++ opaFunctions
        } else {
          fs
        }
      }
      val memories                                                                  = LinearMemories.getMemories(config)
      val instance = new Plugin(
        new Manifest(
          Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
          new MemoryOptions(config.memoryPages),
          config.config.asJava,
          config.allowedHosts.asJava,
          config.allowedPaths.asJava
        ),
        config.wasi,
        functions,
        memories
      )
//      val instance                                                                  = template.instantiate(engine, functions, memories, config.wasi)
      val vm                                                                        = WasmVmImpl(
        index,
        config.killOptions.maxCalls,
        config.memoryPages * (64L * 1024L),
        options.resetMemory,
        instance,
        vmDataRef,
        memories,
        functions,
        this
      )
      availableVms.offer(vm)
      creatingRef.compareAndSet(true, false)
    }
  }

  // acquire an available vm for work
  private def acquireVm(): WasmVmImpl = synchronized {
    if (availableVms.size() > 0) {
      availableVms.synchronized {
        val vm = availableVms.poll()
        availableVms.remove(vm)
        inUseVms.offer(vm)
        vm
      }
    } else {
      throw new RuntimeException("no instances available")
    }
  }

  // release the vm to be available for other tasks
  private[wasm4s] def release(vm: WasmVmImpl): Unit = synchronized {
    availableVms.synchronized {
      availableVms.offer(vm)
      inUseVms.remove(vm)
    }
  }

  // do not consider the vm anymore for more work (the vm is being dropped for some reason)
  private[wasm4s] def ignore(vm: WasmVmImpl): Unit = synchronized {
    availableVms.synchronized {
      inUseVms.remove(vm)
    }
  }

  // do not consider the vm anymore for more work (the vm is being dropped for some reason)
  private[wasm4s] def clear(vm: WasmVmImpl): Unit = synchronized {
    availableVms.synchronized {
      availableVms.remove(vm)
    }
  }

  private[wasm4s] def wasmConfig(): Option[WasmConfiguration] = {
    optConfig.orElse(ic.wasmConfigSync(stableId)).orElse(ic.wasmConfig(stableId).await(30.seconds)) // ugly but ...
  }

  private def hasAvailableVm(plugin: WasmConfiguration): Boolean =
    availableVms.size() > 0 && (inUseVms.size < plugin.instances)

  private def isVmCreating(): Boolean = creatingRef.get()

  private def atMaxPoolCapacity(plugin: WasmConfiguration): Boolean = (availableVms.size + inUseVms.size) >= plugin.instances

  // close the current pool
  private[wasm4s] def close(): Unit = availableVms.synchronized {
//    engine.close()
  }

  // destroy all vms and clear everything in order to destroy the current pool
  private[wasm4s] def destroyCurrentVms(): Unit = availableVms.synchronized {
    WasmVmPoolImpl.logger.info("destroying all vms")
    availableVms.asScala.foreach(_.destroy())
    availableVms.clear()
    inUseVms.clear()
    //counter.set(0)
//    templateRef.set(null)
    creatingRef.set(false)
    lastPluginVersion.set(null)
  }

  // compute the current hash for a tuple (wasmcode + config)
  private def computeHash(
      config: WasmConfiguration,
      key: String,
      cache: TrieMap[String, CacheableWasmScript]
  ): String = {
    config.json.stringify.sha512 + "#" + cache
      .get(key)
      .map {
        case CacheableWasmScript.CachedWasmScript(wasm, _) => wasm.sha512
        case CacheableWasmScript.FetchingCachedWasmScript(_, wasm) => wasm.sha512
        case _                                             => "fetching"
      }
      .getOrElse("null")
  }

  // compute if the source (wasm code + config) is the same than current
  private def hasChanged(config: WasmConfiguration): Boolean = availableVms.synchronized {
    val key     = config.source.cacheKey
    val cache   = ic.wasmScriptCache
    var oldHash = lastPluginVersion.get()
    if (oldHash == null) {
      oldHash = computeHash(config, key, cache)
      lastPluginVersion.set(oldHash)
    }
    cache.get(key) match {
      case Some(CacheableWasmScript.CachedWasmScript(_, _)) => {
        val currentHash = computeHash(config, key, cache)
        oldHash != currentHash
      }
      case Some(CacheableWasmScript.FetchingCachedWasmScript(_, _)) => {
        val currentHash = computeHash(config, key, cache)
        oldHash != currentHash
      }
      case _                                                => false
    }
  }

  // get a pooled vm when one available.
  // Do not forget to release it after usage
  def getPooledVm(options: WasmVmInitOptions = WasmVmInitOptions.empty()): Future[WasmVm] = {
    val p = Promise[WasmVmImpl]()
    requestsQueue.offer(WasmVmPoolAction(p, options))
    p.future
  }

  // borrow a vm for sync operations
  def withPooledVm[A](options: WasmVmInitOptions = WasmVmInitOptions.empty())(f: WasmVm => A): Future[A] = {
    implicit val ev = ic
    implicit val ec = ic.executionContext
    getPooledVm(options).flatMap { vm =>
      val p = Promise[A]()
      try {
        val ret = f(vm)
        p.trySuccess(ret)
      } catch {
        case e: Throwable =>
          p.tryFailure(e)
      } finally {
        vm.release()
      }
      p.future
    }
  }

  // borrow a vm for async operations
  def withPooledVmF[A](options: WasmVmInitOptions = WasmVmInitOptions.empty())(f: WasmVm => Future[A]): Future[A] = {
    implicit val ev = ic
    implicit val ec = ic.executionContext
    getPooledVm(options).flatMap { vm =>
      f(vm).andThen { case _ =>
        vm.release()
      }
    }
  }
}