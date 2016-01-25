package me.reminisce.gameboard.questions

import akka.actor.Props
import me.reminisce.database.MongoDBEntities.FBPost
import me.reminisce.database.MongoDatabaseService
import me.reminisce.gameboard.board.GameboardEntities.QuestionKind._
import me.reminisce.gameboard.board.GameboardEntities.SpecificQuestionType._
import me.reminisce.gameboard.board.GameboardEntities.{GeolocationQuestion, Location}
import me.reminisce.gameboard.questions.QuestionGenerator.{CreateQuestion, FinishedQuestionCreation, MongoDBError, NotEnoughData}
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Random, Success}

/**
  * Factory for [[me.reminisce.gameboard.questions.WhichCoordinatesWereYouAt]]
  */
object WhichCoordinatesWereYouAt {

  /**
    * Creates a WhichCoordinatesWereYouAt question generator
    * @param database database from which to take the data
    * @return props for the created actor
    */
  def props(database: DefaultDB): Props =
    Props(new WhichCoordinatesWereYouAt(database))
}

/**
  * WhichCoordinatesWereYouAt question generator
  * @param db database from which to take the data
  */
class WhichCoordinatesWereYouAt(db: DefaultDB) extends QuestionGenerator {

  /**
    * Entry point for this actor, handles the CreateQuestionWithMultipleItems(userId, itemIds) message by getting the
    * necessary items from the database and creating a question. If some items are non conform to what is expected,
    * missing or there is an error while contacting the database, the error is reported to the client.
    * @return Nothing
    */
  def receive = {
    case CreateQuestion(userId, itemId) =>
      val client = sender()
      val query = BSONDocument(
        "userId" -> userId,
        "postId" -> itemId
      )
      val postCollection = db[BSONCollection](MongoDatabaseService.fbPostsCollection)
      postCollection.find(query).one[FBPost].onComplete {
        case Success(postOpt) =>
          val questionOpt =
            for {
              post <- postOpt
              postSubject = QuestionGenerator.subjectFromPost(post)
              place <- post.place
            } yield {
              val answer = Location(place.location.latitude, place.location.longitude)
              //magic numbers
              val maxDisplacement = 0.03166666666
              val minDisplacement = 0.02743473384
              val t = Random.nextDouble() * (maxDisplacement - minDisplacement) + minDisplacement
              val theta = Random.nextDouble() * 2 * math.Pi
              val defaultLocation = Location(answer.latitude + t * math.sin(theta), answer.longitude + t * math.cos(theta))
              //magic number, around 2 kilometers
              val range = 0.02612831795
              GeolocationQuestion(userId, Geolocation, GeoWhatCoordinatesWereYouAt, Some(postSubject),
                answer, defaultLocation, range)
            }
          questionOpt match {
            case Some(q) =>
              client ! FinishedQuestionCreation(q)
            case None =>
              client ! NotEnoughData(s"Post has no place or post does not exist : $itemId")
          }
        case Failure(e) =>
          client ! MongoDBError(s"${e.getMessage}")
        case any =>
          client ! MongoDBError(s"$any")
      }
    case any =>
      log.error(s"Wrong message received $any.")
  }

}