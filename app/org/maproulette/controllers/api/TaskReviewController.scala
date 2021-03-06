/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.controllers.api

import javax.inject.Inject
import org.maproulette.Config
import org.maproulette.data.ActionManager
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.model.{Challenge, User, Tag}
import org.maproulette.framework.service.{ServiceManager, TagService}
import org.maproulette.models.Task
import org.maproulette.models.dal._
import org.maproulette.provider.osm.ChangesetProvider
import org.maproulette.provider.websockets.WebSocketProvider
import org.maproulette.session.{SearchParameters, SessionManager}
import org.maproulette.utils.Utils
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc._

/**
  * TaskReviewController is responsible for handling functionality related to
  * task reviews.
  *
  * @author krotstan
  */
class TaskReviewController @Inject() (
    override val sessionManager: SessionManager,
    override val actionManager: ActionManager,
    override val dal: TaskDAL,
    override val tagService: TagService,
    taskReviewDAL: TaskReviewDAL,
    serviceManager: ServiceManager,
    dalManager: DALManager,
    wsClient: WSClient,
    webSocketProvider: WebSocketProvider,
    config: Config,
    components: ControllerComponents,
    changeService: ChangesetProvider,
    override val bodyParsers: PlayBodyParsers
) extends TaskController(
      sessionManager,
      actionManager,
      dal,
      tagService,
      serviceManager,
      dalManager,
      wsClient,
      webSocketProvider,
      config,
      components,
      changeService,
      bodyParsers
    ) {

  /**
    * Gets and claims a task that needs to be reviewed.
    *
    * @param id Task id to work on
    * @return
    */
  def startTaskReview(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val task = this.dal.retrieveById(id) match {
        case Some(t) => t
        case None    => throw new NotFoundException(s"Task with $id not found, cannot start review.")
      }

      val result = this.taskReviewDAL.startTaskReview(user, task)
      Ok(Json.toJson(result))
    }
  }

  /**
    * Releases a claim on a task that needs to be reviewed.
    *
    * @param id Task id to work on
    * @return
    */
  def cancelTaskReview(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val task = this.dal.retrieveById(id) match {
        case Some(t) => t
        case None    => throw new NotFoundException(s"Task with $id not found, cannot cancel review.")
      }

      val result = this.taskReviewDAL.cancelTaskReview(user, task)
      Ok(Json.toJson(result))
    }
  }

  /**
    * Gets and claims the next task that needs to be reviewed.
    *
    * Valid search parameters include:
    * cs => "my challenge name"
    * o => "mapper's name"
    * r => "reviewer's name"
    *
    * @return Task
    */
  def nextTaskReview(
      onlySaved: Boolean = false,
      sort: String,
      order: String,
      lastTaskId: Long = -1,
      excludeOtherReviewers: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        val result = this.taskReviewDAL.nextTaskReview(
          user,
          params,
          onlySaved,
          sort,
          order,
          (if (lastTaskId == -1) None else Some(lastTaskId)),
          excludeOtherReviewers
        )
        val nextTask = result match {
          case Some(task) =>
            Ok(Json.toJson(this.taskReviewDAL.startTaskReview(user, task)))
          case None =>
            throw new NotFoundException("No tasks found to review.")
        }

        nextTask
      }
    }
  }

  /**
    * Gets tasks where a review is requested
    *
    * @param limit The number of tasks to return
    * @param page The page number for the results
    * @param sort The column to sort
    * @param order The order direction to sort
    * @param excludeOtherReviewers exclude tasks that have been reviewed by someone else
    * @return
    */
  def getReviewRequestedTasks(
      onlySaved: Boolean = false,
      limit: Int,
      page: Int,
      sort: String,
      order: String,
      excludeOtherReviewers: Boolean = false,
      includeTags: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        //cs => "my challenge name"
        //o => "mapper's name"
        //r => "reviewer's name"
        val (count, result) = this.taskReviewDAL.getReviewRequestedTasks(
          User.userOrMocked(user),
          params,
          onlySaved,
          limit,
          page,
          sort,
          order,
          true,
          excludeOtherReviewers
        )
        Ok(Json.obj("total" -> count, "tasks" -> _insertExtraJSON(result, includeTags)))
      }
    }
  }

  /**
    * Gets reviewed tasks where the user has reviewed or requested review
    *
    * @param allowReviewNeeded Whether we should return tasks where status is review requested also
    * @param limit The number of tasks to return
    * @param page The page number for the results
    * @param sort The column to sort
    * @param order The order direction to sort
    * @return
    */
  def getReviewedTasks(
      allowReviewNeeded: Boolean = false,
      limit: Int,
      page: Int,
      sort: String,
      order: String,
      includeTags: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        val (count, result) = this.taskReviewDAL.getReviewedTasks(
          User.userOrMocked(user),
          params,
          allowReviewNeeded,
          limit,
          page,
          sort,
          order
        )
        Ok(Json.obj("total" -> count, "tasks" -> _insertExtraJSON(result, includeTags)))
      }
    }
  }

  /**
    * Fetches the matching parent object and inserts it (id, name, status)
    * into the JSON data returned. Also fetches and inserts usernames for
    * 'reviewRequestedBy' and 'reviewBy'
    */
  private def _insertExtraJSON(tasks: List[Task], includeTags: Boolean = false): JsValue = {
    if (tasks.isEmpty) {
      Json.toJson(List[JsValue]())
    } else {
      val fetchedChallenges =
        this.dalManager.challenge.retrieveListById(-1, 0)(tasks.map(t => t.parent))

      val projects = Some(
        this.serviceManager.project
          .list(fetchedChallenges.map(c => c.general.parent))
          .map(p => p.id -> Json.obj("id" -> p.id, "name" -> p.name, "displayName" -> p.displayName)
          )
          .toMap
      )

      val challenges = Some(
        fetchedChallenges
          .map(c =>
            c.id ->
              Json.obj(
                "id"     -> c.id,
                "name"   -> c.name,
                "status" -> c.status,
                "parent" -> Json.toJson(projects.get(c.general.parent)).as[JsObject]
              )
          )
          .toMap
      )

      val mappers = Some(
        this.serviceManager.user
          .retrieveListById(tasks.map(t => t.review.reviewRequestedBy.getOrElse(0L)))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val reviewers = Some(
        this.serviceManager.user
          .retrieveListById(tasks.map(t => t.review.reviewedBy.getOrElse(0L)))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val tagsMap: Map[Long, List[Tag]] = includeTags match {
        case true => this.serviceManager.tag.listByTasks(tasks.map(t => t.id))
        case _    => null
      }

      val jsonList = tasks.map { task =>
        val challengeJson = Json.toJson(challenges.get(task.parent)).as[JsObject]
        var updated =
          Utils.insertIntoJson(Json.toJson(task), Challenge.KEY_PARENT, challengeJson, true)
        if (task.review.reviewRequestedBy.getOrElse(0) != 0) {
          val mapperJson = Json.toJson(mappers.get(task.review.reviewRequestedBy.get)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "reviewRequestedBy", mapperJson, true)
        }
        if (task.review.reviewedBy.getOrElse(0) != 0) {
          val reviewerJson = Json.toJson(reviewers.get(task.review.reviewedBy.get)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "reviewedBy", reviewerJson, true)
        }
        if (includeTags && tagsMap.contains(task.id)) {
          val tagsJson = Json.toJson(tagsMap(task.id))
          updated = Utils.insertIntoJson(updated, "tags", tagsJson, true)
        }
        updated
      }
      Json.toJson(jsonList)
    }
  }

  /**
    * Gets clusters of review tasks. Uses kmeans method in postgis.
    *
    * @param reviewTasksType Type of review tasks (1: To Be Reviewed 2: User's reviewed Tasks 3: All reviewed by users)
    * @param numberOfPoints Number of clustered points you wish to have returned
    * @param onlySaved include challenges that have been saved
    * @param excludeOtherReviewers exclude tasks that have been reviewed by someone else
    *
    * @return A list of ClusteredPoint's that represent clusters of tasks
    */
  def getReviewTaskClusters(
      reviewTasksType: Int,
      numberOfPoints: Int,
      onlySaved: Boolean = false,
      excludeOtherReviewers: Boolean = false
  ): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        Ok(
          Json.toJson(
            this.taskReviewDAL.getReviewTaskClusters(
              User.userOrMocked(user),
              reviewTasksType,
              params,
              numberOfPoints,
              onlySaved,
              excludeOtherReviewers
            )
          )
        )
      }
    }
  }
}
