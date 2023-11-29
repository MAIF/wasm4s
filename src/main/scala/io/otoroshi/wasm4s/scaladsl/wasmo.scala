package io.otoroshi.wasm4s.scaladsl

import io.otoroshi.wasm4s.scaladsl.implicits._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class WasmoSettings(
  url: String = "http://localhost:5001",
  clientId: String = "admin-api-apikey-id",
  clientSecret: String = "admin-api-apikey-secret",
  pluginsFilter: Option[String] = Some("*"),
  legacyAuth: Boolean = false,
) {
  def json: JsValue = WasmoSettings.format.writes(this)
}

object WasmoSettings {
  val format = new Format[WasmoSettings] {
    override def writes(o: WasmoSettings): JsValue =
      Json.obj(
        "url"           -> o.url,
        "clientId"      -> o.clientId,
        "clientSecret"  -> o.clientSecret,
        "legacyAuth"  -> o.legacyAuth,
        "pluginsFilter" -> o.pluginsFilter.map(JsString).getOrElse(JsNull).as[JsValue],
      )

    override def reads(json: JsValue): JsResult[WasmoSettings] =
      Try {
        WasmoSettings(
          url = (json \ "url").asOpt[String].getOrElse("http://localhost:5001"),
          clientId = (json \ "clientId").asOpt[String].getOrElse("admin-api-apikey-id"),
          clientSecret = (json \ "clientSecret").asOpt[String].getOrElse("admin-api-apikey-secret"),
          legacyAuth = (json \ "legacyAuth").asOpt[Boolean].getOrElse(false),
          pluginsFilter = (json \ "pluginsFilter").asOpt[String].getOrElse("*").some,
        )
      } match {
        case Failure(e)  => JsError(e.getMessage)
        case Success(ac) => JsSuccess(ac)
      }
  }
}