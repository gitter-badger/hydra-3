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

import akka.actor.Status.Failure
import akka.actor.{Props, _}
import akka.routing.{FromConfig, RoundRobinPool}
import com.typesafe.config.Config
import hydra.common.config.ActorConfigSupport
import hydra.core.ingest.{Ingestor, IngestorInfo}
import hydra.core.protocol.Publish
import hydra.ingest.services.IngestorRegistry._
import org.joda.time.DateTime

import scala.collection.parallel.mutable.ParHashMap
import scala.util.Try

/**
  * Created by alexsilva on 1/12/16.
  */
class IngestorRegistry extends Actor with ActorLogging with ActorConfigSupport {
  val ingestors: ParHashMap[String, RegisteredIngestor] = new ParHashMap()

  private object RegistrationLock

  override def receive: Receive = {
    case RegisterIngestor(name, clazz) =>
      val ingestor = doRegister(name, clazz)
      context.watch(ingestor.ref)
      sender ! IngestorInfo(name, ingestor.ref.path.toString, ingestor.registrationTime)

    case UnregisterIngestor(name) =>
      ingestors.remove(name) match {
        case Some(handler) =>
          context.unwatch(handler.ref)
          log.debug(s"Removed ingestor $name")
          context.stop(handler.ref)

        case None => log.info(s"Handler $name not found")
      }

    case GetIngestors =>
      val info = ingestors.values.map(r => IngestorInfo(r.name, r.ref.path.toString, r.registrationTime))
      sender ! RegisteredIngestors(info.toList)

    case p@Publish(_) =>
      ingestors.values.foreach(_.ref forward p)

    case Lookup(name) => sender ! IngestorLookupResult(name, ingestors.get(name).map(_.ref))

    case Terminated(handler) => {
      //todo: ADD RESTART
      log.error(s"Handler ${handler} terminated.")
    }
  }

  def doRegister(name: String, clazz: Class[_ <: Ingestor]): RegisteredIngestor = {
    RegistrationLock.synchronized {
      if (ingestors.contains(name))
        sender ! Failure(IngestorAlreadyRegisteredException(s"Ingestor $name is already registered."))
      val ingestor = RegisteredIngestor(name, context.actorOf(ingestorProps(name, clazz), name), DateTime.now())
      ingestors + (name -> ingestor)
      ingestor
    }
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e: Exception => {
        //todo: Keep track of errors and start backing off handlers if too many errors
        SupervisorStrategy.Restart
      }
    }

  private def ingestorProps(name: String, ingestor: Class[_ <: Ingestor]): Props = {
    import configs.syntax._
    val path = self.path / name
    val routerPath = path.elements.drop(1).mkString("/", "/", "")
    val ip = Props(ingestor)
    rootConfig.get[Config](s"akka.actor.deployment.$routerPath")
      .map { cfg =>
        log.debug(s"Using router $routerPath for ingestor $name")
        FromConfig.props(ip)
      }
      .valueOrElse {
        log.debug(s"Using default round robin router for ingestor $name")
        Try(new RoundRobinPool(rootConfig.getConfig("hydra.ingest.default-ingestor-router"))).map(_.props(ip))
          .recover { case e: Exception =>
            log.error(e, s"Ingestor $name won't be routed. Unable to instantiate default router: ${e.getMessage}")
            ip
          }.get
      }
  }

}

case class RegisteredIngestor(name: String, ref: ActorRef, registrationTime: DateTime)

object IngestorRegistry {

  case class RegisterIngestor(name: String, clazz: Class[_ <: Ingestor])

  case class UnregisterIngestor(name: String)

  case object GetIngestors

  case class Lookup(name: String)

  case class IngestorLookupResult(name: String, ref: Option[ActorRef])

  case class RegisteredIngestors(ingestors: Seq[IngestorInfo])

  case class IngestorAlreadyRegisteredException(msg: String) extends RuntimeException(msg)

}