package io.otoroshi.wasm4s.scaladsl

import akka.stream.Materializer
import akka.util.ByteString
import io.otoroshi.wasm4s.impl._
import io.otoroshi.wasm4s.scaladsl.implicits._
import org.extism.sdk.{HostFunction, HostUserData, Plugin}
import org.extism.sdk.wasmotoroshi._
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

trait AwaitCapable {
  def await[T](future: Future[T], atMost: FiniteDuration = 5.seconds): T = {
    Await.result(future, atMost)
  }
}

case class HostFunctionWithAuthorization(
                                          function: HostFunction[_ <: HostUserData],
                                          authorized: WasmConfiguration => Boolean
                                        )

case class EnvUserData(
                        ic: WasmIntegrationContext,
                        executionContext: ExecutionContext,
                        mat: Materializer,
                        config: WasmConfiguration
                      ) extends HostUserData

case class StateUserData(
                          ic: WasmIntegrationContext,
                          executionContext: ExecutionContext,
                          mat: Materializer,
                          cache: TrieMap[String, TrieMap[String, ByteString]]
                        ) extends HostUserData

case class EmptyUserData() extends HostUserData

sealed abstract class WasmFunctionParameters {
  def functionName: String
  def input: Option[String]
  def parameters: Option[Parameters]
  def resultSize: Option[Int]
  def call(plugin: Plugin): Either[JsValue, (String, ResultsWrapper)]
  def withInput(input: Option[String]): WasmFunctionParameters
  def withFunctionName(functionName: String): WasmFunctionParameters
}

object WasmFunctionParameters {

  def from(
            functionName: String,
            input: Option[String],
            parameters: Option[Parameters],
            resultSize: Option[Int]
          ) = {
    (input, parameters, resultSize) match {
      case (_, Some(p), Some(s))  => BothParamsResults(functionName, p, s)
      case (_, Some(p), None)     => NoResult(functionName, p)
      case (_, None, Some(s))     => NoParams(functionName, s)
      case (Some(in), None, None) => ExtismFuntionCall(functionName, in)
      case _                      => UnknownCombination()
    }
  }

  case class UnknownCombination(
                                 functionName: String = "unknown",
                                 input: Option[String] = None,
                                 parameters: Option[Parameters] = None,
                                 resultSize: Option[Int] = None
                               ) extends WasmFunctionParameters {
    override def call(plugin: Plugin): Either[JsValue, (String, ResultsWrapper)] = {
      Left(Json.obj("error" -> "bad call combination"))
    }
    def withInput(input: Option[String]): WasmFunctionParameters       = this.copy(input = input)
    def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class NoResult(
                       functionName: String,
                       params: Parameters,
                       input: Option[String] = None,
                       resultSize: Option[Int] = None
                     ) extends WasmFunctionParameters {
    override def parameters: Option[Parameters]                     = Some(params)
    override def call(plugin: Plugin): Either[JsValue, (String, ResultsWrapper)] = {
      plugin.callWithoutResults(functionName, parameters.get)
      Right[JsValue, (String, ResultsWrapper)](("", ResultsWrapper(new Results(0), plugin)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters       = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class NoParams(
                       functionName: String,
                       result: Int,
                       input: Option[String] = None,
                       parameters: Option[Parameters] = None
                     ) extends WasmFunctionParameters {
    override def resultSize: Option[Int]                                        = Some(result)
    override def call(plugin: Plugin): Either[JsValue, (String, ResultsWrapper)] = {
      plugin
        .callWithoutParams(functionName, resultSize.get)
        .right
        .map(_ => ("", ResultsWrapper(new Results(0), plugin)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters       = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class BothParamsResults(
                                functionName: String,
                                params: Parameters,
                                result: Int,
                                input: Option[String] = None
                              ) extends WasmFunctionParameters {
    override def parameters: Option[Parameters]                     = Some(params)
    override def resultSize: Option[Int]                                        = Some(result)
    override def call(plugin: Plugin): Either[JsValue, (String, ResultsWrapper)] = {
      plugin
        .call(functionName, parameters.get, resultSize.get)
        .right
        .map(res => ("", ResultsWrapper(res, plugin)))
    }
    override def withInput(input: Option[String]): WasmFunctionParameters       = this.copy(input = input)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class ExtismFuntionCall(
                                functionName: String,
                                in: String,
                                parameters: Option[Parameters] = None,
                                resultSize: Option[Int] = None
                              ) extends WasmFunctionParameters {
    override def input: Option[String] = Some(in)
    override def call(plugin: Plugin): Either[JsValue, (String, ResultsWrapper)] = {
      plugin
        .call(functionName, input.get.getBytes(StandardCharsets.UTF_8))
        .right
        .map { str =>
          (new String(str, StandardCharsets.UTF_8), ResultsWrapper(new Results(0), plugin))
        }
    }

    override def withInput(input: Option[String]): WasmFunctionParameters       = this.copy(in = input.get)
    override def withFunctionName(functionName: String): WasmFunctionParameters = this.copy(functionName = functionName)
  }

  case class OPACall(functionName: String, pointers: Option[OPAWasmVm] = None, in: String)
    extends WasmFunctionParameters {
    override def input: Option[String] = Some(in)

    override def call(plugin: Plugin): Either[JsValue, (String, ResultsWrapper)] = {
      if (functionName == "initialize")
        OPA.initialize(plugin)
      else
        OPA.evaluate(plugin, pointers.get.opaDataAddr, pointers.get.opaBaseHeapPtr, in)
    }

    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(in = input.get)

    override def withFunctionName(functionName: String): WasmFunctionParameters = this
    override def parameters: Option[Parameters]                     = None
    override def resultSize: Option[Int]                                        = None
  }

  case class CorazaNextCall(functionName: String, in: String = "", configuration: Option[String] = None) extends WasmFunctionParameters {
    override def input: Option[String] = Some(in)

    override def call(plugin: Plugin): Either[JsValue, (String, ResultsWrapper)] = {
      if (functionName == "initialize")
        CorazaNext.initialize(plugin, configuration.getOrElse(""))
      else if(functionName == "evaluate")
        CorazaNext.evaluate(plugin, in)
      else
        CorazaNext.evaluateResponse(plugin, in)
    }

    override def withInput(input: Option[String]): WasmFunctionParameters = this.copy(in = input.get)

    override def withFunctionName(functionName: String): WasmFunctionParameters = this
    override def parameters: Option[Parameters]                     = None
    override def resultSize: Option[Int]                                        = None
  }
}