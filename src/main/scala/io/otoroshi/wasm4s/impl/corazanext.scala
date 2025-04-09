package io.otoroshi.wasm4s.impl

import io.otoroshi.wasm4s.scaladsl.ResultsWrapper
import io.otoroshi.wasm4s.scaladsl.implicits.BetterSyntax
import org.extism.sdk.Plugin
import org.extism.sdk.wasmotoroshi.Results
import play.api.libs.json.JsValue

object CorazaNext {

  def initialize(plugin: Plugin): Either[JsValue, (String, ResultsWrapper)] = {
    plugin.initializeCoraza()
    ("", ResultsWrapper(new Results(0))).right
  }

  def evaluate(
      plugin: Plugin,
      input: String
  ): Either[JsValue, (String, ResultsWrapper)] = (plugin.newCorazaTransaction(input), ResultsWrapper(new Results(0))).right
}