package io.otoroshi.wasm4s.scaladsl

import akka.util.ByteString
import io.otoroshi.wasm4s.impl.OPAWasmVm
import io.otoroshi.wasm4s.scaladsl.implicits._
import io.otoroshi.wasm4s.scaladsl.security._
import org.extism.sdk.{HostFunction, HostUserData, Plugin}
import org.extism.sdk.wasmotoroshi._
import play.api.libs.json._

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

trait WasmVm {

  // def reset(): Unit

  // def destroy(): Unit

  // def isBusy(): Boolean

  // def destroyAtRelease(): Unit

  def calls: Int

  def current: Int

  def index: Int

  def getOpaPointers(): Option[OPAWasmVm]

  def release(): Unit

  // def lastUsedAt(): Long

  def hasNotBeenUsedInTheLast(duration: FiniteDuration): Boolean

  def consumesMoreThanMemoryPercent(percent: Double): Boolean

  def tooSlow(max: Long): Boolean

  def hasBeenUsedInTheLast(duration: FiniteDuration): Boolean

  // def ignore(): Unit

  def initialized(): Boolean

  def initialize(f: => Any): Unit

  def finitialize[A](f: => Future[A]): Future[Unit]

  def call(parameters: WasmFunctionParameters, context: Option[WasmVmData]): Future[Either[JsValue, (String, ResultsWrapper)]]

  def callWithNoParams(functionName: String, result: Int, input: Option[String] = None, parameters: Option[Parameters] = None, context: Option[WasmVmData] = None)(implicit ec: ExecutionContext): Future[Either[JsValue, Unit]] = {
    call(WasmFunctionParameters.NoParams(functionName, result, input, parameters), context).map {
      case Left(e) => Left(e)
      case Right(_) => Right(())
    }
  }

  def callWithNoResult(functionName: String, params: Parameters, input: Option[String] = None,  resultSize: Option[Int] = None, context: Option[WasmVmData] = None)(implicit ec: ExecutionContext): Future[Either[JsValue, Unit]] = {
    call(WasmFunctionParameters.NoResult(functionName, params, input, resultSize), context).map {
      case Left(e) => Left(e)
      case Right(_) => Right(())
    }
  }

  def callExtismFunction(functionName: String, in: String, parameters: Option[Parameters] = None, resultSize: Option[Int] = None, context: Option[WasmVmData] = None)(implicit ec: ExecutionContext): Future[Either[JsValue, String]] = {
    call(WasmFunctionParameters.ExtismFuntionCall(functionName, in, parameters, resultSize), context).map {
      case Left(e) => Left(e)
      case Right((str, _)) => Right(str)
    }
  }

  def callWithParamsAndResult(functionName: String,  params: Parameters, result: Int, input: Option[String] = None, context: Option[WasmVmData] = None)(implicit ec: ExecutionContext): Future[Either[JsValue, ResultsWrapper]] = {
    call(WasmFunctionParameters.BothParamsResults(functionName, params, result, input), context).map {
      case Left(e) => Left(e)
      case Right((_, wrap)) => Right(wrap)
    }
  }

  def callOpa(functionName: String, in: String, context: Option[WasmVmData] = None)(implicit ec: ExecutionContext): Future[Either[JsValue, (String, ResultsWrapper)]]

  def ensureOpaInitializedAsync(in: Option[String] = None)(implicit ec: ExecutionContext): Future[WasmVm]

  def ensureOpaInitialized(in: Option[String] = None)(implicit ec: ExecutionContext): WasmVm
}

object WasmVmPool {
  def forConfiguration(config: WasmConfiguration, maxCallsBetweenUpdates: Int = 100000)(implicit ic: WasmIntegrationContext): WasmVmPool = io.otoroshi.wasm4s.impl.WasmVmPoolImpl.forConfig(config, maxCallsBetweenUpdates)
  def forConfigurationWithId(stableId: => String, config: => WasmConfiguration, maxCallsBetweenUpdates: Int = 100000)(implicit ic: WasmIntegrationContext): WasmVmPool = {
    new io.otoroshi.wasm4s.impl.WasmVmPoolImpl(stableId, config.some, maxCallsBetweenUpdates, ic)
  }
  def apply(stableId: => String, optConfig: => Option[WasmConfiguration], maxCallsBetweenUpdates: Int = 100000, ic: WasmIntegrationContext): WasmVmPool = {
    new io.otoroshi.wasm4s.impl.WasmVmPoolImpl(stableId, optConfig, maxCallsBetweenUpdates, ic)
  }
}

trait WasmVmPool {
  // get a pooled vm when one available.
  // Do not forget to release it after usage
  def getPooledVm(options: WasmVmInitOptions = WasmVmInitOptions.empty()): Future[WasmVm]

  // borrow a vm for sync operations
  def withPooledVm[A](options: WasmVmInitOptions = WasmVmInitOptions.empty())(f: WasmVm => A): Future[A]

  // borrow a vm for async operations
  def withPooledVmF[A](options: WasmVmInitOptions = WasmVmInitOptions.empty())(f: WasmVm => Future[A]): Future[A]
}

case class WasmDataRights(read: Boolean = false, write: Boolean = false)

object WasmDataRights {
  def fmt =
    new Format[WasmDataRights] {
      override def writes(o: WasmDataRights) =
        Json.obj(
          "read"  -> o.read,
          "write" -> o.write
        )

      override def reads(json: JsValue) =
        Try {
          JsSuccess(
            WasmDataRights(
              read = (json \ "read").asOpt[Boolean].getOrElse(false),
              write = (json \ "write").asOpt[Boolean].getOrElse(false)
            )
          )
        } recover { case e =>
          JsError(e.getMessage)
        } get
    }
}

sealed trait WasmSourceKind {
  def name: String
  def json: JsValue                                                                                               = JsString(name)
  def getWasm(path: String, opts: JsValue)(implicit ic: WasmIntegrationContext, ec: ExecutionContext): Future[Either[JsValue, ByteString]]
  def getConfig(path: String, opts: JsValue)(implicit ic: WasmIntegrationContext, ec: ExecutionContext): Future[Option[WasmConfiguration]] =
    None.vfuture
}

object WasmSourceKind {
  case object Unknown     extends WasmSourceKind {
    def name: String = "Unknown"
    def getWasm(path: String, opts: JsValue)(implicit
        ic: WasmIntegrationContext,
        ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      Left(Json.obj("error" -> "unknown source")).vfuture
    }
  }
  case object Base64      extends WasmSourceKind {
    def name: String = "Base64"
    def getWasm(path: String, opts: JsValue)(implicit
        ic: WasmIntegrationContext,
        ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSourceKind Base64] fetching wasm from base64")
      ByteString(path.replace("base64://", "")).decodeBase64.right.future
    }
  }
  case object Http        extends WasmSourceKind {
    def name: String = "Http"
    def getWasm(path: String, opts: JsValue)(implicit
        ic: WasmIntegrationContext,
        ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      val method         = opts.select("method").asOpt[String].getOrElse("GET")
      val headers        = opts.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      val timeout        = opts.select("timeout").asOpt[Long].getOrElse(10000L).millis
      val followRedirect = opts.select("followRedirect").asOpt[Boolean].getOrElse(true)
      val proxy          = opts.select("proxy").asOpt[JsObject].flatMap(v => WSProxyServerJson.proxyFromJson(v))
      val tlsConfig: Option[TlsConfig]      =
        opts.select("tls").asOpt(TlsConfig.format).orElse(opts.select("tls").asOpt(TlsConfig.format))
      if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSourceKind Http] fetching wasm from source at ${method} ${path}")
      ic.url(path, tlsConfig)
        .withMethod(method)
        .withFollowRedirects(followRedirect)
        .withHttpHeaders(headers.toSeq: _*)
        .withRequestTimeout(timeout)
        .applyOnWithOpt(proxy) { case (req, proxy) =>
          req.withProxyServer(proxy)
        }
        .execute()
        .map { resp =>
          if (resp.status == 200) {
            val body = resp.bodyAsBytes
            Right(body)
          } else {
            val body: String = resp.body
            if (ic.logger.isErrorEnabled) ic.logger.error(s"[WasmSourceKind Http] error fetching wasm from source at ${method} ${path}: ${resp.status} - ${resp.headers} - ${resp.body}")
            Left(
              Json.obj(
                "error"   -> "bad response",
                "status"  -> resp.status,
                "headers" -> resp.headers.mapValues(_.last),
                "body"    -> body
              )
            )
          }
        }
        .recover {
          case e => Left(Json.obj(
            "error" -> s"error while fetching plugin from http at '${method} ${path}': ${e.getMessage}",
            "message" -> e.getMessage
          ))
        }
    }
  }
  case object Wasmo extends WasmSourceKind {
    def name: String = "Wasmo"
    def getWasm(path: String, opts: JsValue)(implicit
        ic: WasmIntegrationContext,
        ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      ic.wasmoSettings.flatMap {
        case Some(settings @ WasmoSettings(url, _, _, kind, _)) => {
          val (apikeyHeader, apikey) = ApikeyHelper.generate(settings)
          val wasmoUrl = s"$url/wasm/$path"
          val followRedirect = opts.select("follow_redirect").asOpt[Boolean].getOrElse(true)
          val timeout = opts.select("timeout").asOpt[Long].map(_.millis).getOrElse(5.seconds)
          if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSourceKind Wasmo] fetching wasm from source at GET ${wasmoUrl}")
          ic.url(wasmoUrl)
            .withFollowRedirects(followRedirect)
            .withRequestTimeout(timeout)
            .withHttpHeaders(
              "Accept"     -> "application/json",
              apikeyHeader          -> apikey,
              "kind"                -> kind.getOrElse("*")
            )
            .get()
            .flatMap { resp =>
              if (resp.status == 200) {
                Right(resp.bodyAsBytes).vfuture
              } else {
                if (ic.logger.isErrorEnabled) ic.logger.error(s"[WasmSourceKind Wasmo] error fetching wasm from source at GET ${wasmoUrl}: ${resp.status} - ${resp.headers} - ${resp.body}")
                val body = resp.body
                Left(Json.obj(
                  "error" -> "bad wasmo response",
                  "status" -> resp.status,
                  "headers" -> resp.headers.mapValues(_.last),
                  "body" -> body
                )).vfuture
              }
            }
            .recover {
              case e => Left(Json.obj(
                "error" -> s"error while fetching plugin from wasmo at '${path}': ${e.getMessage}",
                "message" -> e.getMessage
              ))
            }
        }
        case _                                                            =>
          Left(Json.obj("error" -> "missing wasm manager url")).vfuture
      }
    }
  }
  case object Local       extends WasmSourceKind {
    def name: String = "Local"
    override def getWasm(path: String, opts: JsValue)(implicit
        ic: WasmIntegrationContext,
        ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSourceKind Local] fetching wasm from local config: ${path}")
      ic.wasmConfig(path) flatMap {
        case None         => Left(Json.obj("error" -> "resource not found")).vfuture
        case Some(config) => config.source.getWasm()
      }
    }
    override def getConfig(path: String, opts: JsValue)(implicit
        ic: WasmIntegrationContext,
        ec: ExecutionContext
    ): Future[Option[WasmConfiguration]] = {
      ic.wasmConfig(path)
    }
  }
  case object File        extends WasmSourceKind {
    def name: String = "File"
    def getWasm(path: String, opts: JsValue)(implicit
        ic: WasmIntegrationContext,
        ec: ExecutionContext
    ): Future[Either[JsValue, ByteString]] = {
      if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSourceKind File] fetching wasm from file: ${path}")
      Right(ByteString(Files.readAllBytes(Paths.get(path.replace("file://", ""))))).vfuture
    }
  }

  def apply(value: String): WasmSourceKind = value.toLowerCase match {
    case "base64"      => Base64
    case "http"        => Http
    case "wasmmanager" => Wasmo
    case "wasmo"       => Wasmo
    case "local"       => Local
    case "file"        => File
    case _             => Unknown
  }
}

case class WasmSource(kind: WasmSourceKind, path: String, opts: JsValue = Json.obj()) {
  def json: JsValue                                                                    = WasmSource.format.writes(this)
  def cacheKey                                                                         = s"${kind.name.toLowerCase}://${path}"
  def getConfig()(implicit ic: WasmIntegrationContext, ec: ExecutionContext): Future[Option[WasmConfiguration]] = kind.getConfig(path, opts)
  def isCached()(implicit ic: WasmIntegrationContext): Boolean = {
    val cache = ic.wasmScriptCache
    cache.get(cacheKey) match {
      case Some(CacheableWasmScript.CachedWasmScript(_, _)) => true
      case Some(CacheableWasmScript.FetchingCachedWasmScript(_, _)) => true
      case _                                                => false
    }
  }
  def getWasm()(implicit ic: WasmIntegrationContext, ec: ExecutionContext): Future[Either[JsValue, ByteString]] = try {
    val cache = ic.wasmScriptCache
    cache.synchronized {
      def fetchAndAddToCache(maybeAlready: Option[ByteString]): Future[Either[JsValue, ByteString]] = {
        if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSource] actual wasm fetch at ${path}")
        val promise = Promise[Either[JsValue, ByteString]]()
        maybeAlready match {
          case None => cache.put(cacheKey, CacheableWasmScript.FetchingWasmScript(promise.future))
          case Some(s) => cache.put(cacheKey, CacheableWasmScript.FetchingCachedWasmScript(promise.future, s))
        }
        kind.getWasm(path, opts).map {
            case Left(err) =>
              if (ic.logger.isErrorEnabled) ic.logger.error(s"[WasmSource] error while wasm fetch at ${path}: ${err}")
              maybeAlready match {
                case None => cache.remove(cacheKey)
                case Some(s) =>
                  // put if back and wait for better times ???
                  if (ic.logger.isWarnEnabled) ic.logger.warn(s"[WasmSource] using old version of ${path} because of fetch error: ${err}")
                  cache.put(cacheKey, CacheableWasmScript.CachedWasmScript(s, System.currentTimeMillis()))
              }
              promise.trySuccess(err.left)
              err.left
            case Right(bs) => {
              if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSource] success while wasm fetch at ${path}")
              cache.put(cacheKey, CacheableWasmScript.CachedWasmScript(bs, System.currentTimeMillis()))
              promise.trySuccess(bs.right)
              bs.right
            }
          }
          .recover {
            case e =>
              val err = Json.obj("error" -> s"error while getting wasm from source: ${e.getMessage}")
              maybeAlready match {
                case None => cache.remove(cacheKey)
                case Some(s) =>
                  // put if back and wait ???
                  if (ic.logger.isWarnEnabled) ic.logger.warn(s"[WasmSource] using old version of ${path} because of recover error: ${err}")
                  cache.put(cacheKey, CacheableWasmScript.CachedWasmScript(s, System.currentTimeMillis()))
              }
              promise.trySuccess(err.left)
              err.left
          }
      }

      cache.get(cacheKey) match {
        case None =>
          if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSource] getWasm nothing in cache for ${path}")
          fetchAndAddToCache(None)
        case Some(CacheableWasmScript.FetchingWasmScript(fu)) =>
          if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSource] getWasm fetching for ${path}")
          fu
        case Some(CacheableWasmScript.FetchingCachedWasmScript(_, script)) =>
          if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSource] getWasm fetching already cached for ${path}")
          script.right.future
        case Some(CacheableWasmScript.CachedWasmScript(script, createAt))
          if createAt + ic.wasmCacheTtl < System.currentTimeMillis =>
          if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSource] getWasm expired cache for ${path} - ${createAt} - ${ic.wasmCacheTtl} - ${createAt + ic.wasmCacheTtl} - ${System.currentTimeMillis}")
          fetchAndAddToCache(script.some)
          script.right.vfuture
        case Some(CacheableWasmScript.CachedWasmScript(script, _)) =>
          if (ic.logger.isDebugEnabled) ic.logger.debug(s"[WasmSource] getWasm cached for ${path}")
          script.right.vfuture
      }
    }
  } catch {
    case e: Throwable =>
      e.printStackTrace()
      Future.failed(e)
  }
}

object WasmSource {
  val format = new Format[WasmSource] {
    override def writes(o: WasmSource): JsValue             = Json.obj(
      "kind" -> o.kind.json,
      "path" -> o.path,
      "opts" -> o.opts
    )
    override def reads(json: JsValue): JsResult[WasmSource] = Try {
      WasmSource(
        kind = json.select("kind").asOpt[String].map(WasmSourceKind.apply).getOrElse(WasmSourceKind.Unknown),
        path = json.select("path").asString,
        opts = json.select("opts").asOpt[JsValue].getOrElse(Json.obj())
      )
    } match {
      case Success(s) => JsSuccess(s)
      case Failure(e) => JsError(e.getMessage)
    }
  }
}

sealed trait WasmVmLifetime {
  def name: String
  def json: JsValue = JsString(name)
}

object WasmVmLifetime {

  case object Invocation extends WasmVmLifetime { def name: String = "Invocation" }
  case object Request    extends WasmVmLifetime { def name: String = "Request"    }
  case object Forever    extends WasmVmLifetime { def name: String = "Forever"    }

  def parse(str: String): Option[WasmVmLifetime] = str.toLowerCase() match {
    case "invocation" => Invocation.some
    case "request"    => Request.some
    case "forever"    => Forever.some
    case _            => None
  }
}

trait WasmConfiguration {
  def source: WasmSource
  def memoryPages: Int
  def functionName: Option[String]
  def config: Map[String, String]
  def allowedHosts: Seq[String]
  def allowedPaths: Map[String, String]
  def wasi: Boolean
  def opa: Boolean
  def instances: Int
  def killOptions: WasmVmKillOptions
  def json: JsValue
  def pool(maxCallsBetweenUpdates: Int = 100000)(implicit ic: WasmIntegrationContext): WasmVmPool = io.otoroshi.wasm4s.impl.WasmVmPoolImpl.forConfig(this, maxCallsBetweenUpdates)
}

object BasicWasmConfiguration {
  def fromSource(source: WasmSource): BasicWasmConfiguration = {
    BasicWasmConfiguration(source)
  }
  def fromWasiSource(source: WasmSource): BasicWasmConfiguration = {
    BasicWasmConfiguration(source = source, wasi = true)
  }
  def fromOpaSource(source: WasmSource): BasicWasmConfiguration = {
    BasicWasmConfiguration(source = source, opa = true)
  }
}

case class BasicWasmConfiguration(
  source: WasmSource,
  memoryPages: Int = 100,
  functionName: Option[String] = None,
  config: Map[String, String] = Map.empty,
  allowedHosts: Seq[String] = Seq.empty,
  allowedPaths: Map[String, String] = Map.empty,
  wasi: Boolean = false,
  opa: Boolean = false,
  instances: Int = 1,
  killOptions: WasmVmKillOptions = WasmVmKillOptions.default,
) extends WasmConfiguration {
  def json: JsValue = Json.obj(
    "source" -> source.json,
    "memory_pages" -> memoryPages,
    "function_name" -> functionName,
    "config" -> config,
    "allowed_hosts" -> allowedHosts,
    "allowed_paths" -> allowedPaths,
    "wasi" -> wasi,
    "opa" -> opa,
    "instances" -> instances,
    "kill_options" -> killOptions.json
  )
}

object InMemoryWasmConfigurationStore {
  def apply[A <: WasmConfiguration](tuples: (String, A)*): InMemoryWasmConfigurationStore[A] = {
    val map = new TrieMap[String, A]()
    map.addAll(tuples)
    new InMemoryWasmConfigurationStore(map)
  }
}

class InMemoryWasmConfigurationStore[A <: WasmConfiguration](store: TrieMap[String, A]) {
  def wasmConfiguration(key: String): Option[A] = store.get(key)
  def wasmConfigurationUnsafe(key: String): A = store.apply(key)
  def wasmConfigurations(): Seq[A] = store.values.toSeq
  def removeWasmConfiguration(key: String): Option[A] = store.remove(key)
  def putWasmConfiguration(key: String, value: A): Option[A] = store.put(key, value)
}

object ResultsWrapper {
  def apply(results: Results): ResultsWrapper                               = new ResultsWrapper(results, None)
  def apply(results: Results, plugin: Plugin): ResultsWrapper =
    new ResultsWrapper(results, Some(plugin))
}

case class ResultsWrapper(results: Results, pluginOpt: Option[Plugin]) {
  def free(): Unit = try {
    if (results.getLength > 0) {
      results.close()
    }
  } catch {
    case t: Throwable =>
      t.printStackTrace()
      ()
  }
}

sealed trait CacheableWasmScript

object CacheableWasmScript {
  case class CachedWasmScript(script: ByteString, createAt: Long)       extends CacheableWasmScript
  case class FetchingWasmScript(f: Future[Either[JsValue, ByteString]]) extends CacheableWasmScript
  case class FetchingCachedWasmScript(f: Future[Either[JsValue, ByteString]], script: ByteString) extends CacheableWasmScript
}

case class WasmVmInitOptions(
                              importDefaultHostFunctions: Boolean = true,
                              resetMemory: Boolean = true,
                              addHostFunctions: (AtomicReference[WasmVmData]) => Seq[HostFunction[_ <: HostUserData]] = _ =>
                                Seq.empty
                            )

object WasmVmInitOptions {
  def empty(): WasmVmInitOptions = WasmVmInitOptions(
    importDefaultHostFunctions = true,
    resetMemory = true,
    addHostFunctions = _ => Seq.empty
  )
}

case class WasmVmKillOptions(
                              immortal: Boolean = false,
                              maxCalls: Int = Int.MaxValue,
                              maxMemoryUsage: Double = 0.0,
                              maxAvgCallDuration: FiniteDuration = 0.nano,
                              maxUnusedDuration: FiniteDuration = 5.minutes
                            ) {
  def json: JsValue = WasmVmKillOptions.format.writes(this)
}

object WasmVmKillOptions {
  val default = WasmVmKillOptions()
  val format  = new Format[WasmVmKillOptions] {
    override def writes(o: WasmVmKillOptions): JsValue             = Json.obj(
      "immortal"              -> o.immortal,
      "max_calls"             -> o.maxCalls,
      "max_memory_usage"      -> o.maxMemoryUsage,
      "max_avg_call_duration" -> o.maxAvgCallDuration.toMillis,
      "max_unused_duration"   -> o.maxUnusedDuration.toMillis
    )
    override def reads(json: JsValue): JsResult[WasmVmKillOptions] = Try {
      WasmVmKillOptions(
        immortal = json.select("immortal").asOpt[Boolean].getOrElse(false),
        maxCalls = json.select("max_calls").asOpt[Int].getOrElse(Int.MaxValue),
        maxMemoryUsage = json.select("max_memory_usage").asOpt[Double].getOrElse(0.0),
        maxAvgCallDuration = json.select("max_avg_call_duration").asOpt[Long].map(_.millis).getOrElse(0.nano),
        maxUnusedDuration = json.select("max_unused_duration").asOpt[Long].map(_.millis).getOrElse(5.minutes)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
}
