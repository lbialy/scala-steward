/*
 * Copyright 2018-2023 Scala Steward contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scalasteward.core.git

import cats.syntax.all._
import org.scalasteward.core.data.Update
import org.scalasteward.core.update.show
import org.scalasteward.core.util.Nel

final case class CommitMsg(
    title: String,
    body: List[String] = Nil,
    coAuthoredBy: List[Author] = Nil
) {
  def paragraphs: Nel[String] =
    Nel(title, body)

  def appendParagraph(paragraph: String): CommitMsg =
    copy(body = body :+ paragraph)

  def trailers: List[(String, String)] =
    coAuthoredBy.map(author => ("Co-authored-by", author.show))
}

object CommitMsg {
  def replaceVariables(s: String)(update: Update, baseBranch: Option[Branch]): CommitMsg =
    update.on(
      u => {
        val artifactNameValue = show.oneLiner(u)
        val nextVersionValue = u.nextVersion.value
        val defaultValue = s"Update $artifactNameValue to $nextVersionValue" +
          baseBranch.fold("")(branch => s" in ${branch.name}")
        val title = s
          .replace("${default}", defaultValue)
          .replace("${artifactName}", artifactNameValue)
          .replace("${currentVersion}", u.currentVersion.value)
          .replace("${nextVersion}", nextVersionValue)
          .replace("${branchName}", baseBranch.map(_.name).orEmpty)
        CommitMsg(title = title)
      },
      g => {
        val artifactsWithVersions = g.updates
          .groupBy(_.nextVersion)
          .map { case (version, updates) =>
            s"${updates.map(u => s"${u.artifactId.name}").mkString(", ")} to ${version.value}"
          }
          .mkString(" - ")

        val defaultTitle =
          s"Update for group ${g.name} ${baseBranch.fold("")(branch => s"in ${branch.name} ")}$${artifactVersions}"

        val title = g.title
          .getOrElse(defaultTitle)
          .replace("${artifactVersions}", artifactsWithVersions)
        CommitMsg(title = title)
      }
    )
}
