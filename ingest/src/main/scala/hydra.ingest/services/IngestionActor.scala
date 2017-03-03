/*
 * Copyright (C) 2016 Pluralsight, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package hydra.ingest.services

import akka.actor.SupervisorStrategy._
import akka.actor._
import akka.util.Timeout
import hydra.common.config.ActorConfigSupport
import hydra.core.protocol.InitiateRequest
import hydra.ingest.protocol.IngestionCompleted
import hydra.ingest.services.IngestionSupervisor.InitiateIngestion

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by alexsilva on 12/22/15.
  */
class IngestionActor(registryPath: String) extends Actor with ActorConfigSupport with ActorLogging {

  import configs.syntax._

  val ingestionTimeout = applicationConfig.get[FiniteDuration]("ingestion.timeout").valueOrElse(3.seconds)

  implicit val timeout = Timeout(ingestionTimeout)

  val registry: ActorRef = Await.result(context.actorSelection(registryPath).resolveOne(),
    timeout.duration)

  override def receive: Receive = {
    case InitiateRequest(request) =>
      context.actorOf(IngestionSupervisor.props(registry, request, ingestionTimeout)) ! InitiateIngestion
    case IngestionCompleted(summary) => {
      context.stop(sender)
      //      summary.callback.foreach { c =>
      //        Try(context.actorSelection(c) ! IngestionCompleted(summary)) recover {
      //          case e => log.error(s"Unable to send reply back to ${receive}: ${e.getMessage}")
      //        }
      //      }
    }
    case ReceiveTimeout => {
      log.error("Received a timeout from " + sender)
    }
    case other => {
      throw new IllegalArgumentException(s"${other} is not expected at this state.")
    }
  }

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 10.seconds) {
      case _: Exception => {
        Stop
      }
    }
}
