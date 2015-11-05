package me.reminisce.database

import akka.testkit.TestActorRef
import me.reminisce.database.MongoDatabaseService.{SaveFBPage, SaveFBPost, SaveLastFetchedTime}
import me.reminisce.mongodb.MongoDBEntities._
import me.reminisce.testutils.Retry
import org.joda.time.DateTime
import org.scalatest.DoNotDiscover
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.BSONDocument

@DoNotDiscover
class MongoDatabaseServiceSpec extends DatabaseTester("MongoDatabaseServiceSpec") {

  "MongoDatabaseService" must {
    "save post to database." in {
      val db = newDb()
      val userId = PostTestsData.userId
      val post = PostTestsData.post

      val dbService = TestActorRef(MongoDatabaseService.props(userId, db))

      dbService ! SaveFBPost(List(post))

      val collection = db[BSONCollection](MongoDatabaseService.fbPostsCollection)

      val postId = PostTestsData.postId

      val selector = BSONDocument("userId" -> userId, "postId" -> postId)

      Retry.find[FBPost](collection, selector, 0)(_ => true) match {
        case Some(fbPost) => assert(fbPost.postId == postId)
        case None =>
          fail("Too many attempts at retrieving post, maybe not saved. Check the log generated by the " +
            "MongoDatabaseService to see if the insertion went wrong.")
      }
    }

    "save page to database." in {
      val db = newDb()
      val userId = PageTestsData.userId
      val page = PageTestsData.page

      val dbService = TestActorRef(MongoDatabaseService.props(userId, db))

      dbService ! SaveFBPage(List(page))

      val collectionPages = db[BSONCollection](MongoDatabaseService.fbPagesCollection)

      val pageId = PageTestsData.pageId

      val selectorPage = BSONDocument("pageId" -> pageId)

      Retry.find[FBPage](collectionPages, selectorPage, 0)(_ => true) match {
        case Some(fbPage) =>
          assert(fbPage.pageId == pageId)
        case None =>
          fail("Too many attempts at retrieving page, maybe not saved." +
            " Check the log generated by the MongoDatabaseService to see if the insertion went wrong.")
      }

      val collectionPageLikes = db[BSONCollection](MongoDatabaseService.fbPageLikesCollection)

      val selectorLikes = BSONDocument("userId" -> userId, "pageId" -> pageId)

      Retry.find[FBPageLike](collectionPageLikes, selectorLikes, 0)(_ => true) match {
        case Some(fBPageLike) =>
          assert(fBPageLike.pageId == pageId)
          assert(fBPageLike.userId == userId)
        case None =>
          fail("Too many attempts at retrieving page like, maybe not saved." +
            " Check the log generated by the MongoDatabaseService to see if the insertion went wrong.")
      }
    }

    "save last fetched time to database." in {
      val db = newDb()
      val now = DateTime.now

      val userId = "MongoDatabaseServiceSpecUser"

      val dbService = TestActorRef(MongoDatabaseService.props(userId, db))

      dbService ! SaveLastFetchedTime

      val collection = db[BSONCollection](MongoDatabaseService.lastFetchedCollection)

      val selector = BSONDocument("userId" -> userId)

      Retry.find[LastFetched](collection, selector, 0)(_ => true) match {
        case Some(fbLastFetched) =>
          assert(fbLastFetched.userId == userId)
          assert(fbLastFetched.date.isAfter(now.getMillis) || fbLastFetched.date == now)
        case None =>
          fail(s"Too many attempts at retrieving last fetched time, maybe not saved. " +
            s"Check the log generated by the MongoDatabaseService to see if the insertion went wrong.")
      }
    }
  }
}