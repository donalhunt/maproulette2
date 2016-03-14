package org.maproulette.actions

import java.sql.Timestamp
import javax.inject.{Inject, Singleton}

import anorm.SqlParser._
import anorm._
import org.maproulette.Config
import play.api.{Application, Logger}
import play.api.db.Database
import org.maproulette.exception.InvalidException
import scala.collection.mutable.ListBuffer
import org.maproulette.models.Task

/**
  * This file handles retrieving the action summaries from the database. This primarily revolves
  * around how task status' have changed, but would also include creation, updating and deletion
  * of any of the objects in the system. Is primarily used for statistics.
  *
  * @author cuthbertm
  */
case class ActionSummary(count:Int, userId:Option[Long]=None, typeId:Option[String]=None, itemId:Option[Long]=None, action:Option[String]=None, status:Option[String]=None)

@Singleton
class ActionManager @Inject()(config: Config, db:Database)(implicit application:Application) {

  // Columns
  val userId = 0
  val typeId = 1
  val itemId = 2
  val action = 3
  val status = 4
  // timeframe
  val HOUR = 0
  val DAY = 1
  val WEEK = 2
  val MONTH = 3
  val YEAR = 4

  /**
    * A anorm row parser for the actions table
    */
  implicit val parser: RowParser[ActionSummary] = {
    get[Int]("count") ~
      get[Option[Long]]("actions.user_id") ~
      get[Option[Int]]("actions.type_id") ~
      get[Option[Long]]("actions.item_id") ~
      get[Option[Int]]("actions.action") ~
      get[Option[Int]]("actions.status") map {
      case count ~ userId ~ typeId ~ itemId ~ action ~ status => {
        val asType = typeId match {
          case Some(t) => Actions.getTypeName(t)
          case None => None
        }
        val asAction = action match {
          case Some(a) => Actions.getActionName(a)
          case None => None
        }
        val asStatus = action match {
          case Some(s) => Task.getStatusName(s)
          case None => None
        }
        new ActionSummary(count, userId, asType, itemId, asAction, asStatus)
      }
    }
  }

  /**
    * Creates an action in the database
    *
    * @param userId The id of the user that performed the action
    * @param item The item that the action was performed on
    * @param action The action that was performed
    * @param extra And extra information that you want to send along with the creation of the action
    * @return true if created
    */
  def setAction(userId:Long, item:Item with ItemType, action:ActionType, extra:String) : Boolean = {
    if (action.getLevel > config.actionLevel) {
      Logger.trace("Action not logged, action level higher than threshold in configuration.")
      false
    } else {
      db.withTransaction { implicit c =>
        val statusId = action match {
          case t:TaskStatusSet => t.status
          case _ => 0
        }
        SQL"""INSERT INTO actions (user_id, type_id, item_id, action, status, extra)
                VALUES ($userId, ${item.typeId}, ${item.itemId}, ${action.getId},
                          $statusId, $extra)""".execute()
      }
    }
  }

  /**
    * A helper function that gets the full action summary, this is generally not a good idea to call
    * this function, as it will pretty much list every action available which could be very large.
    * Generally this would be used for testing purposes only.
    *
    * @return A list of action summaries that will pretty much group by everything, which means that
    *         the group by won't make much of a difference
    */
  def getFullSummary() = getActionSummary(List(userId, typeId, itemId, action, status))

  /**
    * This is probably not the best way to approach this particular problem. However it will take
    * a bunch of parameters and build a query that get summary data from the actions table. This
    * function is built so that it can give you flexibility to query the table however you want,
    * however might have been better simply to create a separate query function for each type of
    * "report" that you wanted
    *
    * @param columns The columns that you want returned limited to UserId = 0, typeId = 1, itemId = 2, action = 3 and status = 4
    * @param timeframe You can specify whether you want the data returned per Hour = 0, Day = 1, Week = 2, Month = 3 and Year = 4
    * @param userLimit Filter the query based on a set of user id's
    * @param typeLimit Filter the query based on the type of item Project = 0, Challenge = 1, Task = 2
    * @param itemLimit Filter the query based on a set of item id's
    * @param statusLimit Filter the query based on status Created = 0, Fixed = 1, FalsePositive = 2, Skipped = 3, Deleted = 4
    * @param startTime Filter where created time for action is greater than the provided start time
    * @param endTime Filter where created time for action is less than the provided end time
    * @return A list of action summaries that match the given criteria, by default will return everything
    */
  def getActionSummary(columns:List[Int]=List.empty, timeframe:Option[Int]=None,
                               userLimit:List[Long]=List.empty, typeLimit:List[Int]=List.empty,
                               itemLimit:List[Long]=List.empty, statusLimit:List[Int]=List.empty,
                               startTime:Option[Timestamp]=None, endTime:Option[Timestamp]=None) : List[ActionSummary] = {
    val groupByClause = new StringBuilder
    groupByClause ++= "GROUP BY "
    val whereList = new ListBuffer[String]()
    val selectClause = new StringBuilder
    selectClause ++= "SELECT COUNT(*) as count, "

    // validate columns
    if (columns.isEmpty) {
      throw new InvalidException("At least one column needs to be provided for summary query.")
    }
    val distinctColumns = columns.distinct
    val newColumns = distinctColumns.map(col => {
      if (col < userId || col > status) {
        throw new InvalidException(s"Invalid column ID [$col] provided")
      }
      col match {
        case `userId` => "user_id"
        case `typeId` => "type_id"
        case `itemId` => "item_id"
        case `action` => "action"
        case `status` => "status"
      }
    })
    selectClause ++= newColumns.mkString(",")
    groupByClause ++= newColumns.mkString(",")

    // validate timeframe
    timeframe match {
      case Some(frame) =>
        if (frame < HOUR || frame > YEAR)
          throw new InvalidException(s"Invalid time frame [$frame] provided")
      case None => //don't have to worry about it
    }

    // validate types
    val distinctTypes = typeLimit.distinct
    distinctTypes.foreach(typeId =>
      if (!Actions.validActionType(typeId))
        throw new InvalidException(s"Invalid action type [$typeId] provided")
    )
    if (distinctTypes.nonEmpty) {
      whereList += s"type_id IN (${distinctTypes.mkString(",")})"
    }

    val distinctItems = itemLimit.distinct
    if (distinctItems.nonEmpty) {
      whereList += s"item_id IN(${distinctItems.mkString(",")})"
    }

    // valid status
    val distinctStatus = statusLimit.distinct
    distinctStatus.foreach(status =>
      if (!Task.isValidStatus(status))
        throw new InvalidException(s"Invalid status [$status] provided")
    )
    if (distinctStatus.nonEmpty) {
      whereList += s"status IN (${distinctStatus.mkString(",")})"
    }
    val whereClause = if (whereList.nonEmpty) s"WHERE ${whereList.mkString(" AND ")}" else ""
    db.withConnection { implicit c =>
      SQL(s"${selectClause.toString} FROM actions $whereClause ${groupByClause.toString}").as(parser.*)
    }
  }
}
