package me.reminisce.service.stats

import akka.actor.Props
import me.reminisce.database.{DatabaseService, MongoDatabaseService}
import me.reminisce.fetcher.common.GraphResponses.Post
import me.reminisce.mongodb.MongoDBEntities._
import me.reminisce.mongodb.StatsEntities.{ItemStats, UserStats}
import me.reminisce.service.gameboardgen.GameboardEntities.QuestionKind.Order
import me.reminisce.service.gameboardgen.questiongen.QuestionGenerationConfig
import me.reminisce.service.stats.StatsDataTypes.{PostGeolocation, _}
import me.reminisce.service.stats.StatsHandler.{FinalStats, TransientPostsStats, _}
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.BSONDocument
import reactivemongo.core.commands.Count

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object StatsHandler {

  case class FinalStats(fbPosts: Set[String], fbPages: Set[String])

  case class TransientPostsStats(fbPosts: List[Post])

  def props(userId: String, db: DefaultDB): Props =
    Props(new StatsHandler(userId, db))

  private def addTypeToMap(typeAndCount: (String, Int), oldMap: Map[String, Int]): Map[String, Int] = typeAndCount match {
    case (tpe, count) =>
      if (oldMap.contains(tpe)) {
        oldMap.updated(tpe, oldMap(tpe) + count)
      } else {
        oldMap.updated(tpe, count)
      }
  }

  @tailrec
  def addTypesToMap(typesAndCounts: List[(String, Int)], oldMap: Map[String, Int]): Map[String, Int] = {
    typesAndCounts match {
      case Nil => oldMap
      case x :: xs => addTypesToMap(xs, addTypeToMap(x, oldMap))
    }
  }

  // This method is meant to be used to compute transient stats on posts
  def availableDataTypes(post: Post): List[DataType] = {
    List(hasTimeData(post), hasGeolocationData(post), hasWhoCommentedData(post),
      hasCommentNumber(post), hasLikeNumber(post)).flatten
  }

  private def hasTimeData(post: Post): Option[DataType] = {
    if ((post.message.exists(!_.isEmpty) || post.story.exists(!_.isEmpty)) && post.created_time.nonEmpty)
      Some(Time)
    else
      None
  }

  private def hasGeolocationData(post: Post): Option[DataType] = {
    post.place.flatMap(place => place.location.flatMap(
      location => location.latitude.flatMap(lat => location.longitude.map(
        long => PostGeolocation
      ))
    ))
  }

  private def hasWhoCommentedData(post: Post): Option[DataType] = {
    val fbComments = post.comments.flatMap(root => root.data.map(comments => comments.map { c =>
      FBComment(c.id, FBFrom(c.from.id, c.from.name), c.like_count, c.message)
    }))
    if (post.message.exists(!_.isEmpty) || post.story.exists(!_.isEmpty)) {
      for {
        comments <- fbComments
        fromSet = comments.map(comm => comm.from).toSet
        if fromSet.size > 3
      } yield PostWhoCommented
    } else {
      None
    }
  }

  private def hasCommentNumber(post: Post): Option[DataType] = {
    val fbComments = post.comments.flatMap(root => root.data.map(comments => comments.map { c =>
      FBComment(c.id, FBFrom(c.from.id, c.from.name), c.like_count, c.message)
    }))
    if ((post.message.exists(!_.isEmpty) || post.story.exists(!_.isEmpty)) && fbComments.nonEmpty) {
      fbComments.withFilter(comments => comments.size > 3).map(comments => PostCommentsNumber)
    } else {
      None
    }
  }

  private def hasLikeNumber(post: Post): Option[DataType] = {
    val likeCount = post.likes.flatMap(root => root.data).getOrElse(List()).size
    if (likeCount > 0) {
      Some(LikeNumber)
    } else {
      None
    }
  }

  def getItemStats(userId: String, itemId: String, itemType: String, itemsStats: List[ItemStats]): ItemStats = {
    itemsStats.filter(is => is.userId == userId && is.itemId == itemId) match {
      case Nil =>
        ItemStats(None, userId, itemId, itemType, List(), 0)
      case head :: tail =>
        //Normally only one match is possible
        head
    }
  }

  def userStatsWithNewCounts(newLikers: Set[FBLike], newItemsStats: List[ItemStats], userStats: UserStats): UserStats = {
    val newDataTypes = newItemsStats.foldLeft(userStats.dataTypeCounts) {
      case (acc, itemStats) => addTypesToMap(itemStats.dataTypes.map(dType => (dType, 1)), acc)
    }

    // One has to be careful as the count for order is just the count of items that have a data type suited for ordering
    // Ordering have to be a multiple of the number of items to order
    val newQuestionCounts: Map[String, Int] = newDataTypes.foldLeft(Map[String, Int]()) {
      case (acc, (tpe, cnt)) =>
        val kinds = possibleKind(stringToType(tpe))
        val newCounts = kinds.map {
          kind =>
            val count = kind match {
              case Order =>
                val excess = cnt % QuestionGenerationConfig.orderingItemsNumber
                cnt - excess
              case _ =>
                cnt
            }
            (kind.toString, count)
        }
        addTypesToMap(newCounts, acc)
    }

    UserStats(userStats.id, userStats.userId, newDataTypes, newQuestionCounts, newLikers)
  }

}

class StatsHandler(userId: String, db: DefaultDB) extends DatabaseService {

  def receive = {
    case FinalStats(fbPosts, fbPages) =>
      val userStatsCollection = db[BSONCollection](MongoDatabaseService.userStatisticsCollection)
      val itemsStatsCollection = db[BSONCollection](MongoDatabaseService.itemsStatsCollection)
      val postCollection = db[BSONCollection](MongoDatabaseService.fbPostsCollection)
      val pagesCollection = db[BSONCollection](MongoDatabaseService.fbPagesCollection)
      val selector = BSONDocument("userId" -> userId)
      userStatsCollection.find(selector).one[UserStats].onComplete {
        case Success(userStatsOpt) =>
          lazy val emptyUserStats = UserStats(None, userId, Map(), Map(), Set())
          finalizeStats(fbPosts.toList, fbPages.toList, userStatsOpt.getOrElse(emptyUserStats), postCollection,
            pagesCollection, userStatsCollection, itemsStatsCollection)
        case Failure(e) =>
          log.error(s"Database could not be reached : $e.")
        case any =>
          log.error(s"Unknown database error: $any.")
      }
    case TransientPostsStats(fbPosts) =>
      saveTransientPostsStats(fbPosts, db[BSONCollection](MongoDatabaseService.itemsStatsCollection))
    case any =>
      log.error("StatsHandler received unhandled message : " + any)
  }


  def saveTransientPostsStats(fbPosts: List[Post], itemsStatsCollection: BSONCollection): Unit = {
    fbPosts.foreach {
      post =>
        val selector = BSONDocument("userId" -> userId, "itemId" -> post.id)
        val availableData = availableDataTypes(post).map(dType => dType.name)
        val itemStats = ItemStats(None, userId, post.id, "Post", availableData, availableData.length)
        itemsStatsCollection.update(selector, itemStats, upsert = true)
    }

  }

  private def finalizeStats(fbPostsIds: List[String], fbPagesIds: List[String], userStats: UserStats,
                            postCollection: BSONCollection, pagesCollection: BSONCollection, userStatsCollection: BSONCollection,
                            itemsStatsCollection: BSONCollection): Unit = {
    if ((fbPagesIds ++ fbPostsIds).nonEmpty) {
      val postSelector = BSONDocument("userId" -> userId, "postId" -> BSONDocument("$in" -> fbPostsIds))
      val postsCursor = postCollection.find(postSelector).cursor[FBPost]
      (for {
        fbPosts <- postsCursor.collect[List](fbPostsIds.length, stopOnError = true)

        pageSelector = BSONDocument("pageId" -> BSONDocument("$in" -> fbPagesIds))
        pagesCursor = pagesCollection.find(pageSelector).cursor[FBPage]
        fbPages <- pagesCursor.collect[List](fbPagesIds.length, stopOnError = true)

        itemStatsSelector = BSONDocument("userId" -> userId, "itemId" -> BSONDocument("$in" -> fbPostsIds))
        itemsStatsCursor = itemsStatsCollection.find(itemStatsSelector).cursor[ItemStats]
        itemsStats <- itemsStatsCursor.collect[List](fbPostsIds.length, stopOnError = true)

        queryNotLiked = BSONDocument("userId" -> userId, "pageId" -> BSONDocument("$nin" -> fbPagesIds))
        notLikedPagesCount <- db.command(Count(MongoDatabaseService.fbPageLikesCollection, Some(queryNotLiked)))
      } yield finalizeStats(fbPosts, fbPages, notLikedPagesCount, userStats, itemsStats, itemsStatsCollection, userStatsCollection)
        ) onFailure {
        case e =>
          log.error(s"Could not reach database : $e")
      }
    } else {
      val selectOldItems = BSONDocument("userId" -> userId, "readForStats" -> false)
      val itemsStatsCursor = itemsStatsCollection.find(selectOldItems).cursor[ItemStats]
      (for {
        itemsStats <- itemsStatsCursor.collect[List](stopOnError = true)

        postSelector = BSONDocument("userId" -> userId, "postId" -> BSONDocument("$in" -> itemsStats.map(is => is.itemId)))
        postsCursor = postCollection.find(postSelector).cursor[FBPost]
        fbPosts <- postsCursor.collect[List](itemsStats.length, stopOnError = true) if itemsStats.nonEmpty
      } yield dealWithOldStats(fbPosts, itemsStats, userStats, itemsStatsCollection, userStatsCollection)
        ) onFailure {
        case e =>
          e.getMessage match {
            case "Future.filter predicate is not satisfied" =>
              log.info("There was no element in old stats.")
            case any =>
              log.error(s"Could not reach database : $e")
          }
      }
    }
  }

  private def finalizeStats(fbPosts: List[FBPost], fbPages: List[FBPage], notLikedPagesCount: Int, userStats: UserStats,
                            itemsStats: List[ItemStats], itemsStatsCollection: BSONCollection,
                            userStatsCollection: BSONCollection): Unit = {
    val newLikers = accumulateLikes(fbPosts) ++ userStats.likers

    val updatedPosts: List[ItemStats] = updatePostsStats(fbPosts, newLikers, itemsStats)

    val updatedPages: List[ItemStats] = updatePagesStats(fbPages, notLikedPagesCount)

    val untouchedPosts = itemsStats.filterNot(
      is => updatedPosts.exists(ui => ui.userId == is.userId && ui.itemId == is.itemId)
    )

    val newItemsStats = (updatedPosts ++ untouchedPosts ++ updatedPages).map {
      is =>
        ItemStats(is.id, is.userId, is.itemId, is.itemType, is.dataTypes, is.dataCount, readForStats = true)
    }

    newItemsStats.foreach {
      itemStats =>
        val selector = BSONDocument("userId" -> userId, "itemId" -> itemStats.itemId)
        itemsStatsCollection.update(selector, itemStats, upsert = true)
    }

    val newUserStats = userStatsWithNewCounts(newLikers, newItemsStats, userStats)
    val selector = BSONDocument("userId" -> userId)
    userStatsCollection.update(selector, newUserStats, upsert = true)
  }

  // The stats in question can only be posts. Pages are only generated with the read flag to true as those items are
  // only generated during stats finalization.
  private def dealWithOldStats(fbPosts: List[FBPost], itemsStats: List[ItemStats], userStats: UserStats,
                               itemsStatsCollection: BSONCollection, userStatsCollection: BSONCollection): Unit = {

    val newLikers = accumulateLikes(fbPosts) ++ userStats.likers

    val updatedPosts: List[ItemStats] = updatePostsStats(fbPosts, newLikers, itemsStats)


    val untouchedPosts = itemsStats.filterNot(
      is => updatedPosts.exists(ui => ui.userId == is.userId && ui.itemId == is.itemId)
    )

    val newItemsStats = (updatedPosts ++ untouchedPosts).map {
      is =>
        ItemStats(is.id, is.userId, is.itemId, is.itemType, is.dataTypes, is.dataCount, readForStats = true)
    }

    newItemsStats.foreach {
      itemStats =>
        val selector = BSONDocument("userId" -> userId, "itemId" -> itemStats.itemId)
        itemsStatsCollection.update(selector, itemStats, upsert = true)
    }

    val newUserStats = userStatsWithNewCounts(newLikers, newItemsStats, userStats)
    val selector = BSONDocument("userId" -> userId)
    userStatsCollection.update(selector, newUserStats, upsert = true)

  }

  private def accumulateLikes(fbPosts: List[FBPost]): Set[FBLike] = {
    fbPosts.foldLeft(Set[FBLike]()) {
      (acc: Set[FBLike], post: FBPost) => {
        post.likes match {
          case Some(likes) => acc ++ likes.toSet
          case None => acc
        }
      }
    }
  }

  private def updatePostsStats(fbPosts: List[FBPost], likers: Set[FBLike],
                               itemsStats: List[ItemStats]): List[ItemStats] = {
    fbPosts.flatMap {
      fbPost =>
        val likeNumber = fbPost.likesCount.getOrElse(0)
        if (likers.size - likeNumber >= 3 && likeNumber > 0) {
          val oldItemStat = getItemStats(userId, fbPost.postId, "Post", itemsStats)
          val newDataListing = oldItemStat.dataTypes.toSet + PostWhoLiked.name
          Some(ItemStats(None, userId, oldItemStat.itemId, "Post", newDataListing.toList, newDataListing.size))
        } else {
          None
        }
    }
  }

  private def updatePagesStats(fbPages: List[FBPage], notLikedPagesCount: Int): List[ItemStats] = {
    val newDataListing =
      if (notLikedPagesCount >= 3) {
        List(Time.name, LikeNumber.name, PageWhichLiked.name)
      } else {
        List(Time.name, LikeNumber.name)
      }
    fbPages.map {
      fbPage =>
        ItemStats(None, userId, fbPage.pageId, "Page", newDataListing, newDataListing.size)
    }
  }
}
