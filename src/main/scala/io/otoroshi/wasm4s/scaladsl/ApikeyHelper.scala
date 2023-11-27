package io.otoroshi.wasm4s.scaladsl

import java.nio.charset.StandardCharsets
import java.util.Base64

object ApikeyHelper {
  def generate(settings: WasmoSettings): String = {
    Base64.getEncoder.encodeToString(
      s"Basic ${settings.clientId}:${settings.clientSecret}".getBytes(StandardCharsets.UTF_8)
    )
  }
}
