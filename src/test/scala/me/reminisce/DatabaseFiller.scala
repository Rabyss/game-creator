package me.reminisce

import com.github.nscala_time.time.Imports._
import me.reminisce.analysis.DataTypes.{DataType, stringToType, strToItemType}
import me.reminisce.database.AnalysisEntities.{ItemSummary, UserSummary}
import me.reminisce.database.MongoCollections
import me.reminisce.database.MongoDBEntities.{FBPage, FBPageLike, FBPost, FBReaction}
import me.reminisce.gameboard.board.GameboardEntities.strToKind
import me.reminisce.testutils.database.DatabaseTestHelper._
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods._
import org.json4s.{CustomSerializer, DefaultFormats}
import reactivemongo.api.DefaultDB

import scala.io.Source

object DatabaseFiller {

  implicit val formats = DefaultFormats + new DataTypeSerializer + new ListDTypeSerializer

  class DataTypeSerializer extends CustomSerializer[DataType](implicit formats => ( {
    case dType: JString =>
      stringToType(dType.values)
  }, {
    case dType: DataType =>
      JString(dType.name)
  }))

  class ListDTypeSerializer extends CustomSerializer[Set[DataType]](implicit formats => ( {
    case dTypes: JArray =>
      dTypes.arr.map(x => x.extract[DataType]).toSet
  }, {
    case dTypes: Set[DataType] =>
      JArray(dTypes.map(x => JString(x.name)).toList)
  }))

  def parseUserSummaries(lines: Iterator[String]): Iterator[UserSummary] = lines.map {
    l =>
      val json = parse(l)

      json match {
        case summary: JObject =>
          val id = None
          val userId = (summary \ "userId").extract[String]
          val dataTypeCounts = (summary \ "dataTypeCounts").extract[Map[String, Int]].map {
            case (key, value) => stringToType(key) -> value
          }
          val questionCounts = (summary \ "questionCounts").extract[Map[String, Int]].map {
            case (key, value) => strToKind(key) -> value
          }

          val reactioners = (summary \ "reactioners").extract[List[FBReaction]]

          UserSummary(id, userId, dataTypeCounts, questionCounts, reactioners.toSet)
        case _ =>
          throw new IllegalArgumentException("Impossible match case.")
      }
  }


  def parseItemSummaries(lines: Iterator[String]): Iterator[ItemSummary] = lines.map {
    l =>
      val json = parse(l)

      json match {
        case summary: JObject =>
          val id = None
          val userId = (summary \ "userId").extract[String]
          val itemId = (summary \ "itemId").extract[String]
          val itemType = strToItemType((summary \ "itemType").extract[String])
          val dataTypes = (summary \ "dataTypes").extract[Set[DataType]]
          val dataCount = (summary \ "dataCount").extract[Int]
          ItemSummary(id, userId, itemId, itemType, dataTypes, dataCount)
        case _ =>
          throw new IllegalArgumentException("Impossible match case.")
      }
  }

  def parsePageLikes(lines: Iterator[String]): Iterator[FBPageLike] = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(DateTimeZone.UTC)
    lines.map {
      l =>
        val json = parse(l)
        // this allows the extract method to work properly
        val converted = json.mapField {
          case (field, value) =>
            if (field == "likeTime") {
              value.children.headOption match {
                case Some(str) =>
                  (field, str)
                case None =>
                  (field, value)
              }
            } else {
              (field, value)
            }
        }
        converted.extract[FBPageLikeWithoutDate] match {
          case FBPageLikeWithoutDate(id, userId, pageId, likeTime) =>
            FBPageLike(id, userId, pageId, formatter.parseDateTime(likeTime))
          case _ =>
            throw new IllegalArgumentException("Impossible match case.")
        }
    }
  }

  def populateWithTestData(db: DefaultDB, folder: String): Unit = {

    implicit val formats = DefaultFormats + new DataTypeSerializer + new ListDTypeSerializer

    val pages = simpleExtract[FBPage](folder + "/fbPages.json")
    storeObjects(db, "fbPages", pages)

    val pageLikesLines = Source.fromURL(getClass.getResource(folder + "/fbPageLikes.json")).getLines()
    val pageLikes = parsePageLikes(pageLikesLines)
    storeObjects(db, MongoCollections.fbPageLikes, pageLikes)

    val posts = simpleExtract[FBPost](folder + "/fbPosts.json")
    storeObjects(db, MongoCollections.fbPosts, posts)

    val userSummariesLines = Source.fromURL(getClass.getResource(folder + "/userSummaries.json")).getLines()
    val userSummaries = parseUserSummaries(userSummariesLines)
    storeObjects(db, MongoCollections.userSummaries, userSummaries)

    val itemsSummariesLines = Source.fromURL(getClass.getResource(folder + "/itemsSummaries.json")).getLines()
    val itemsSummaries = parseItemSummaries(itemsSummariesLines)
    storeObjects(db, MongoCollections.itemsSummaries, itemsSummaries)
  }
}
