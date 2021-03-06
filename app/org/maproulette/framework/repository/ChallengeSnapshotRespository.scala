/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import java.sql.Connection
import scala.concurrent.duration.FiniteDuration

import anorm.SqlParser.get
import anorm.ToParameterValue
import anorm.{RowParser, ~}
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.model.{User, ChallengeSnapshot}
import play.api.db.Database
import play.api.libs.json.JsValue
@Singleton
class ChallengeSnapshotRepository @Inject() (override val db: Database) extends RepositoryMixin {
  implicit val baseTable: String = ChallengeSnapshot.TABLE

  /**
    * Deletes a challenge snapshot
    *
    * @param snapshotId  The snapshot id to delete
    */
  def delete(snapshotId: Long): Unit = {
    this.withMRTransaction { implicit c =>
      val query = Query.simple(List(BaseParameter(ChallengeSnapshot.FIELD_ID, snapshotId)))
      query.build(s"DELETE FROM $baseTable").executeUpdate()
    }
  }
}
