/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package org.apache.spark.sql.sources

import scala.collection.mutable

import com.gemstone.gemfire.internal.cache.{AbstractRegion, ColocationHelper, PartitionedRegion}
import com.pivotal.gemfirexd.internal.engine.Misc
import io.snappydata.QueryHint

import org.apache.spark.sql.SnappySession
import org.apache.spark.sql.catalyst.analysis.EliminateSubqueryAliases
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, BinaryComparison, Expression, PredicateHelper}
import org.apache.spark.sql.catalyst.planning.ExtractEquiJoinKeys
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, SubqueryAlias}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.columnar.impl.{BaseColumnFormatRelation, ColumnFormatRelation, IndexColumnFormatRelation}
import org.apache.spark.sql.execution.datasources.LogicalRelation

case class ResolveIndex(snappySession: SnappySession) extends Rule[LogicalPlan]
    with PredicateHelper {

  lazy val catalog = snappySession.sessionState.catalog

  lazy val analyzer = snappySession.sessionState.analyzer

  override def apply(plan: LogicalPlan): LogicalPlan = {

    val indexHint = QueryHint.WithIndex.toString
    val explicitIndexHint = snappySession.queryHints.collect {
      case (hint, value) if hint.startsWith(indexHint) =>
        val tableOrAlias = hint.substring(indexHint.length)
        val key = catalog.lookupRelationOption(
          catalog.newQualifiedTableName(tableOrAlias)) match {
          case Some(relation@LogicalRelation(cf: BaseColumnFormatRelation, _, _)) =>
            cf.table
          case _ => tableOrAlias
        }

        val index = catalog.lookupRelation(
          snappySession.getIndexTable(catalog.newQualifiedTableName(value)))

        (key, index)
    }

    val replacementMap = if (explicitIndexHint.isEmpty) {
      val optimizedPlan = snappySession.sessionState.optimizer.execute(plan)
      optimizedPlan.flatMap {
        case ExtractEquiJoinKeys(joinType,
        leftKeys, rightKeys, extraConditions, left, right) =>
          (left, right, leftKeys, rightKeys) match {
            case HasColocatedReplacement(replacements) => replacements
            case _ => Nil
          }
        case f@org.apache.spark.sql.catalyst.plans.logical.Filter(cond, child) =>
          val tabToIdxReplacementMap = extractIndexTransformations(cond, child)
          Nil
        case _ => Nil
      }
    } else {
      plan flatMap {
        case table@LogicalRelation(colRelation: ColumnFormatRelation, _, _) =>
          explicitIndexHint.get(colRelation.table) match {
            case Some(index) => Seq((table, index))
            case _ => Nil
          }
        case subQuery@SubqueryAlias(alias, child) =>
          explicitIndexHint.get(alias) match {
            case Some(index) => Seq((subQuery, SubqueryAlias(alias, index)))
            case _ => Nil
          }
        case _ => Nil
      }
    }

    replacementMap.nonEmpty match {
      case true =>
        val newPlan = plan transformUp {
          case q: LogicalPlan =>
            val replacement = replacementMap.collect {
              case (plan, replaceWith) if plan.fastEquals(q) => replaceWith
            }.reduceLeftOption((acc, p_) => if (!p_.fastEquals(acc)) p_ else acc)

            replacement.getOrElse(q)
        }

        newPlan transformUp {
          case q: LogicalPlan =>
            q transformExpressionsUp {
              case a: AttributeReference =>
                q.resolveChildren(Seq(a.qualifier.getOrElse(""), a.name),
                  analyzer.resolver).getOrElse(a)
            }
        }
      case false => plan
    }

  }

  object HasColocatedReplacement {

    type ReturnType = (LogicalPlan, LogicalPlan)

    def unapply(tables: (LogicalPlan, LogicalPlan, Seq[Expression], Seq[Expression])):
    Option[Seq[ReturnType]] = {
      val (left, right, leftKeys, rightKeys) = tables

      val leftIndexes = fetchIndexes2(left)
      val rightIndexes = fetchIndexes2(right)

      val colocatedEntities = leftIndexes.zip(Seq.fill(leftIndexes.length)(rightIndexes)).
          flatMap { case (l, r) => r.flatMap((l, _) :: Nil) }.
          collect {
            case plans@ExtractBaseColumnFormatRelation(leftEntity, rightEntity)
              if isColocated(leftEntity, rightEntity) =>
              val (leftPlan, rightPlan) = plans
              ((leftEntity, leftPlan), (rightEntity, rightPlan))
          }

      colocatedEntities.nonEmpty match {
        case true =>
          val joinRelationPairs = leftKeys.zip(rightKeys).map {
            case (left, right) => (unwrapReference(left).get, unwrapReference(right).get)
          }

          val satisfyingJoinCond = colocatedEntities.zipWithIndex.map {
            case (((leftT, _), (rightT, _)), i) =>
              val partitioningColPairs = leftT.partitionColumns.zip(rightT.partitionColumns)

              if (matchColumnSets(partitioningColPairs, joinRelationPairs)) {
                (i, partitioningColPairs.length)
              } else {
                (-1, 0)
              }
          }.filter(v => v._1 >= 0 && v._2 > 0).sortBy(_._2)(Ordering[Int].reverse)

          satisfyingJoinCond.nonEmpty match {
            case true =>
              val ((_, leftReplacement), (_, rightReplacement)) =
                colocatedEntities(satisfyingJoinCond(0) match {
                  case (idx, _) => idx
                })

              Some(Seq((leftIndexes(0), leftReplacement),
                (rightIndexes(0), rightReplacement)))

            case false =>
              logDebug("join condition insufficient for matching any colocation columns ")
              None
          }

        case _ =>
          logDebug(s"Nothing is colocated between the tables $leftIndexes and $rightIndexes")
          None
      }
    }
  }


  object ExtractBaseColumnFormatRelation {

    type pairOfTables = (BaseColumnFormatRelation, BaseColumnFormatRelation)

    def unapply(arg: (LogicalPlan, LogicalPlan)): Option[pairOfTables] = {
      (extractBoth _).tupled(arg)
    }

    def unapply(arg: LogicalPlan): Option[BaseColumnFormatRelation] = {
      arg collectFirst {
        case entity@LogicalRelation(relation: BaseColumnFormatRelation, _, _) =>
          relation
      }
    }

    private def extractBoth(left: LogicalPlan, right: LogicalPlan) = {
      unapply(left) match {
        case Some(v1) => unapply(right) match {
          case Some(v2) => Some(v1, v2)
          case _ => None
        }
        case _ => None
      }
    }

  }


  private def replaceTableWithIndex(tabToIdxReplacementMap: Map[String, Option[LogicalPlan]],
      plan: LogicalPlan) = {
    plan transformUp {
      case l@LogicalRelation(b: ColumnFormatRelation, _, _) =>
        tabToIdxReplacementMap.find {
          case (t, Some(index)) =>
            b.table.indexOf(t) >= 0
          case (t, None) => false
        }.flatMap(_._2).getOrElse(l)

      case s@SubqueryAlias(alias, child@LogicalRelation(p: ParentRelation, _, _)) =>
        tabToIdxReplacementMap.withDefaultValue(None)(alias) match {
          case Some(i) => s.copy(child = i)
          case _ => s
        }
    }

  }

  private def extractIndexTransformations(cond: Expression, child: LogicalPlan) = {
    val tableToIndexes = fetchIndexes(child)
    val cols = splitConjunctivePredicates(cond)
    val columnGroups = segregateColsByTables(cols)

    getBestMatchingIndex(columnGroups, tableToIndexes)
  }

  private def isColocated(left: BaseColumnFormatRelation, right: BaseColumnFormatRelation) = {

    val leftRegion = Misc.getRegionForTable(left.resolvedName, true)
    val leftLeader = leftRegion.asInstanceOf[AbstractRegion] match {
      case pr: PartitionedRegion => ColocationHelper.getLeaderRegionName(pr)
    }

    val rightRegion = Misc.getRegionForTable(right.resolvedName, true)
    val rightLeader = rightRegion.asInstanceOf[AbstractRegion] match {
      case pr: PartitionedRegion => ColocationHelper.getLeaderRegionName(pr)
    }

    leftLeader.equals(rightLeader)
  }

  private def matchColumnSets(partitioningColPairs: Seq[(String, String)],
      joinRelationPairs: Seq[(AttributeReference, AttributeReference)]): Boolean = {

    if (partitioningColPairs.length != joinRelationPairs.length) {
      return false
    }

    partitioningColPairs.filter({
      case (left, right) =>
        joinRelationPairs.exists {
          case (jleft, jright) =>
            left.equalsIgnoreCase(jleft.name) && right.equalsIgnoreCase(jright.name) ||
                left.equalsIgnoreCase(jright.name) && right.equalsIgnoreCase(jleft.name)
        }
    }).length == partitioningColPairs.length

  }

  def applyRefreshTables(plan: LogicalPlan, replaceWith: LogicalPlan): Option[LogicalPlan] = {
    if (EliminateSubqueryAliases(plan) == replaceWith) {
      return None
    }

    plan match {
      case LogicalRelation(cf: ColumnFormatRelation, _, _) =>
        Some(replaceTableWithIndex(Map(cf.table -> Some(replaceWith)), plan))
      case SubqueryAlias(alias, _) =>
        Some(replaceTableWithIndex(Map(alias -> Some(replaceWith)), plan))
      case _ => None
    }
  }

  private def fetchIndexes(plan: LogicalPlan): mutable.HashMap[LogicalPlan, Seq[LogicalPlan]] =
    plan.children.foldLeft(mutable.HashMap.empty[LogicalPlan, Seq[LogicalPlan]]) {
      case (m, table) =>
        val newVal: Seq[LogicalPlan] = m.getOrElse(table,
          table match {
            case l@LogicalRelation(p: ParentRelation, _, _) =>
              val catalog = snappySession.sessionCatalog
              p.getDependents(catalog).map(idx =>
                catalog.lookupRelation(catalog.newQualifiedTableName(idx)))
            case SubqueryAlias(alias, child@LogicalRelation(p: ParentRelation, _, _)) =>
              val catalog = snappySession.sessionCatalog
              p.getDependents(catalog).map(idx =>
                catalog.lookupRelation(catalog.newQualifiedTableName(idx)))
            case _ => Nil
          }
        )
        // we are capturing same set of indexes for one or more
        // SubqueryAlias. This comes handy while matching columns
        // that is aliased for lets say self join.
        // select x.*, y.* from tab1 x, tab2 y where x.parentID = y.id
        m += (table -> newVal)
    }

  private def fetchIndexes2(plan: LogicalPlan): Seq[LogicalPlan] =
    EliminateSubqueryAliases(plan) match {
      case l@LogicalRelation(p: ParentRelation, _, _) =>
        val catalog = snappySession.sessionCatalog
        Seq(l) ++ p.getDependents(catalog).map(idx =>
          catalog.lookupRelation(catalog.newQualifiedTableName(idx)))
      case q: LogicalPlan => q.children.flatMap(fetchIndexes2(_))
      case _ => Nil
    }

  private def unwrapReference(input: Expression) = input.references.collectFirst {
    case a: AttributeReference => a
  }

  private def segregateColsByTables(cols: Seq[Expression]) = cols.
      foldLeft(mutable.MutableList.empty[AttributeReference]) {
        case (m, col@BinaryComparison(left, _)) =>
          unwrapReference(left).foldLeft(m) {
            case (m, leftA: AttributeReference) =>
              m.find(_.fastEquals(leftA)) match {
                case None => m += leftA
                case _ => m
              }
          }
        case (m, _) => m
      }.groupBy(_.qualifier.get)

  private def getBestMatchingIndex(
      columnGroups: Map[String, mutable.MutableList[AttributeReference]],
      tableToIndexes: mutable.HashMap[LogicalPlan, Seq[LogicalPlan]]) = columnGroups.map {
    case (table, colList) =>
      val indexes = tableToIndexes.find { case (tableRelation, _) => tableRelation match {
        case l@LogicalRelation(b: ColumnFormatRelation, _, _) =>
          b.table.indexOf(table) >= 0
        case SubqueryAlias(alias, _) => alias.equals(table)
        case _ => false
      }
      }.map(_._2).getOrElse(Nil)

      val index = indexes.foldLeft(Option.empty[LogicalPlan]) {
        case (max, i) => i match {
          case l@LogicalRelation(idx: IndexColumnFormatRelation, _, _) =>
            idx.partitionColumns.filter(c => colList.exists(_.name.equalsIgnoreCase(c))) match {
              case predicatesMatched
                if predicatesMatched.length == idx.partitionColumns.length =>
                val existing = max.getOrElse(l)
                existing match {
                  case LogicalRelation(eIdx: IndexColumnFormatRelation, _, _)
                    if (idx.partitionColumns.length > eIdx.partitionColumns.length) =>
                    Some(l)
                  case _ => Some(existing)
                }
              case _ => max
            }
        }
      }

      if (index.isDefined) {
        logInfo(s"Picking up $index for $table")
      }

      (table, index)
  }.filter({ case (_, idx) => idx.isDefined })


}
