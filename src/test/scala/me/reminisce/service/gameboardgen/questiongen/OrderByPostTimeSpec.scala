package me.reminisce.service.gameboardgen.questiongen

import java.util.concurrent.TimeUnit

import akka.testkit.TestActorRef
import com.github.nscala_time.time.Imports._
import me.reminisce.database.{DatabaseTester, MongoDatabaseService}
import me.reminisce.mongodb.MongoDBEntities.FBPost
import me.reminisce.service.gameboardgen.GameboardEntities.{OrderQuestion, TextPostSubject}
import me.reminisce.service.gameboardgen.questiongen.QuestionGenerator.{CreateQuestionWithMultipleItems, FinishedQuestionCreation, NotEnoughData}
import org.scalatest.DoNotDiscover
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.BSONDocument

import scala.concurrent.Await
import scala.concurrent.duration.Duration

@DoNotDiscover
class OrderByPostTimeSpec extends DatabaseTester {

  import scala.concurrent.ExecutionContext.Implicits.global

  val userId = "TestUserOrderByPostTime"

  "OrderByPostTime" must {
    "not create question when there is not enough data." in {
      val itemIds = List("This user does not exist")

      val actorRef = TestActorRef(OrderByPostTime.props(db))
      actorRef ! CreateQuestionWithMultipleItems(userId, itemIds)
      expectMsg(NotEnoughData(s"Not enough posts in list."))
    }

    "create a valid question when the data is there." in {
      val postsCollection = db[BSONCollection](MongoDatabaseService.fbPostsCollection)

      val postsNumber = QuestionGenerationConfig.orderingItemsNumber

      val itemIds: List[String] = (1 to postsNumber).map {
        case nb => s"Post$nb"
      }.toList

      val posts = (0 until postsNumber).map {
        case nb =>
          val formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(DateTimeZone.UTC)
          val date = new DateTime(nb + 1)
          val dateAsString = date.toString(formatter)
          FBPost(None, userId, itemIds(nb), Some(s"Cool post $nb"), createdTime = Some(dateAsString))
      }.toList

      (0 until postsNumber) foreach {
        case nb =>
          val selector = BSONDocument("userId" -> userId, "postId" -> itemIds(nb))
          Await.result(postsCollection.update(selector, posts(nb), upsert = true), Duration(10, TimeUnit.SECONDS))
      }

      val actorRef = TestActorRef(OrderByPostLikesNumber.props(db))
      actorRef ! CreateQuestionWithMultipleItems(userId, itemIds)

      val finishedCreation = receiveOne(Duration(10, TimeUnit.SECONDS))
      assert(finishedCreation != null)
      assert(finishedCreation.isInstanceOf[FinishedQuestionCreation])

      val question = finishedCreation.asInstanceOf[FinishedQuestionCreation].question
      assert(question.isInstanceOf[OrderQuestion])

      val subjectWithIds = question.asInstanceOf[OrderQuestion].choices
      val answer = question.asInstanceOf[OrderQuestion].answer

      (0 until postsNumber).foreach {
        case nb =>
          val a = answer(nb)
          val subject = subjectWithIds.filter(elm => elm.uId == a).head.subject
          assert(subject.isInstanceOf[TextPostSubject])
          assert(subject.asInstanceOf[TextPostSubject].text == posts(nb).message.getOrElse(""))
      }
    }
  }

}