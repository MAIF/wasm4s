package io.otoroshi.wasm4s.impl

import io.otoroshi.wasm4s.scaladsl.ResultsWrapper
import io.otoroshi.wasm4s.scaladsl.implicits.BetterSyntax
import org.extism.sdk.Plugin
import org.extism.sdk.wasmotoroshi.Results
import play.api.libs.json.{JsObject, JsValue, Json}

object CorazaNext {

  def initialize(plugin: Plugin, configuration: String): Either[JsValue, (String, ResultsWrapper)] = {
    plugin.initializeCoraza(configuration)

    val pluginError = plugin.getExtensionPluginError()

    if(pluginError == null) {
      ("", ResultsWrapper(new Results(0))).right
    } else {
      Json.obj("error" -> pluginError).left
    }
  }

  def evaluate(
      plugin: Plugin,
      input: String
  ): Either[JsValue, (String, ResultsWrapper)] = {
    val transaction = plugin.newCorazaTransaction(input)
    val errors = plugin.corazaTransactionErrors()

    val pluginError = plugin.getExtensionPluginError()

    if(pluginError == null) {
      (Json.stringify(Json.obj(
        "response" -> transaction,
        "errors" -> errors
      )), ResultsWrapper(new Results(0))).right
    } else {
      Json.obj("error" -> pluginError).left
    }
  }

  def evaluateResponse(
                        plugin: Plugin,
                        input: String
  ): Either[JsValue, (String, ResultsWrapper)] = {
    val transaction = plugin.processResponseTransaction(input)
    val errors = plugin.corazaTransactionErrors()

    (Json.stringify(Json.obj(
      "response" -> transaction,
      "errors" -> errors
    )), ResultsWrapper(new Results(0))).right
  }
}