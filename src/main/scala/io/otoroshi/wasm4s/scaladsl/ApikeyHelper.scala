package io.otoroshi.wasm4s.scaladsl

import com.auth0.jwt.algorithms.Algorithm
import io.otoroshi.wasm4s.scaladsl.implicits._
import play.api.libs.json._

import java.nio.charset.StandardCharsets

object ApikeyHelper {

  def generate(settings: WasmoSettings): (String, String) = {
    if (settings.legacyAuth) {
      generateJwt(settings)
    } else {
      generateBasicAuth(settings)
    }
  }

  def generateBasicAuth(settings: WasmoSettings): (String, String) = {
    val token = s"${settings.clientId}:${settings.clientSecret}".base64
    ("Authorization", s"Basic $token")
  }

  def generateJwt(settings: WasmoSettings): (String, String) = {
    val header = Json.obj(
      "typ" -> "JWT",
      "alg" -> "HS512"
    )
    val payload = Json.obj(
      "iss" -> "otoroshi"
    )
    ("Otoroshi-User", sign(header, payload, settings.clientId))
  }

  private def sign(headerJson: JsObject, payloadJson: JsObject, tokenSecret: String): String = {
    val header: String = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(Json.toBytes(headerJson))
    val payload: String = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(Json.toBytes(payloadJson))
    val signatureBytes: Array[Byte] =
      Algorithm.HMAC512(tokenSecret).sign(header.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8))

    val signature: String = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(signatureBytes)
    String.format("%s.%s.%s", header, payload, signature)
  }
}
