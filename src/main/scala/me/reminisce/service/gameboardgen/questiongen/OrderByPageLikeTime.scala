package me.reminisce.service.gameboardgen.questiongen

import akka.actor.Props
import me.reminisce.database.MongoDatabaseService
import me.reminisce.service.gameboardgen.GameboardEntities.OrderQuestion
import me.reminisce.service.gameboardgen.GameboardEntities.QuestionKind._
import me.reminisce.service.gameboardgen.GameboardEntities.SpecificQuestionType._
import me.reminisce.service.gameboardgen.questiongen.QuestionGenerator._
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.default.BSONCollection


object OrderByPageLikeTime {

  def props(database: DefaultDB): Props =
    Props(new OrderByPageLikeTime(database))
}

class OrderByPageLikeTime(db: DefaultDB) extends OrderQuestionGenerator {
  def receive = {
    case CreateQuestionWithMultipleItems(userId, itemIds) =>
      val client = sender()
      val pageLikesCollection = db[BSONCollection](MongoDatabaseService.fbPageLikesCollection)
      val pagesCollection = db[BSONCollection](MongoDatabaseService.fbPagesCollection)
      fetchLikedPages(pageLikesCollection, userId, client, Some(itemIds)) {
        pageLikes =>
          if (pageLikes.length < itemsToOrder) {
            client ! NotEnoughData(s"Did not find enough page-likes.")
          } else {
            fetchPages(pagesCollection, itemIds, client) {
              pages =>
                if (pages.length < itemsToOrder) {
                  client ! NotEnoughData(s"Did not find enough pages.")
                } else {
                  val timedPages = pages.map {
                    p => (p, pageLikes.filter(pl => pl.pageId == p.pageId).head.likeTime)
                  }
                  val ordered = timedPages.take(itemsToOrder).sortBy(_._2.getMillis).map(cpl => subjectFromPage(cpl._1))
                  val (subjectsWithId, answer) = generateSubjectsWithId(ordered)
                  val gameQuestion = OrderQuestion(userId, Order, ORDPageLikeTime, None, subjectsWithId, answer)
                  client ! FinishedQuestionCreation(gameQuestion)
                }
            }
          }
      }
  }

}
