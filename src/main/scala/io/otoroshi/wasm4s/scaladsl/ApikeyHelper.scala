package io.otoroshi.wasm4s.scaladsl

import io.otoroshi.wasm4s.scaladsl.implicits._

object ApikeyHelper {
  def generate(settings: WasmoSettings): String = {
    val token = s"${settings.clientId}:${settings.clientSecret}".base64
    s"Basic $token"
  }
}
