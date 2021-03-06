package me.reminisce.fetching

import akka.actor.{Actor, ActorLogging}
import akka.event.Logging
import me.reminisce.fetching.config.FacebookServiceConfig
import org.json4s.DefaultFormats
import spray.client.pipelining._
import spray.http.HttpHeaders.Accept
import spray.http.MediaTypes._
import spray.http.{HttpRequest, HttpResponse}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Defines a basic actor communicating with facebook
  */
abstract class FBCommunicationManager extends Actor with ActorLogging {

  implicit val formats = DefaultFormats

  def defaultFilter[T](entities: Vector[T]): Vector[T] = {
    for {
      e <- entities
    } yield e
  }

  def facebookPath = s"${FacebookServiceConfig.facebookHostAddress}/${FacebookServiceConfig.apiVersion}"

  implicit val pipelineRawJson: HttpRequest => Future[HttpResponse] = (
    addHeader(Accept(`application/json`))
      ~> sendReceive
    )

  override val log = Logging(context.system, this)
}
