package me.reminisce.server.domain

/**
 * Created by roger on 09/11/14.
 */
trait RestMessage


// Domain objects

object Domain {

  case class Done(message: String) extends RestMessage

  case class GraphAPIUnreachable(message: String) extends RestMessage

  case class GraphAPIInvalidToken(message: String) extends RestMessage

  case class Error(message: String)

  case class TooManyRequests(message: String) extends RestMessage

  case class AlreadyFresh(message: String) extends RestMessage

  case class Validation(message: String)

  case class NoContentFound(message: String) extends RestMessage

  // Exceptions

  case object ExampleException extends Exception("Fetcher encountered exception: need more power")

}