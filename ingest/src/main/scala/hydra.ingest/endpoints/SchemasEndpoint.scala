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

package hydra.ingest.endpoints

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import com.github.vonnagy.service.container.http.routing.RoutedEndpoints
import hydra.common.config.ConfigSupport
import hydra.common.logging.LoggingAdapter
import hydra.core.avro.registry.ConfluentSchemaRegistry
import hydra.core.marshallers.HydraJsonSupport
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import org.apache.avro.Schema.Parser
import org.apache.avro.SchemaParseException


/**
  * Created by alexsilva on 2/13/16.
  */
class SchemasEndpoint(implicit system: ActorSystem, implicit val actorRefFactory: ActorRefFactory)
  extends RoutedEndpoints with ConfigSupport with LoggingAdapter with HydraJsonSupport
    with ConfluentSchemaRegistry {

  implicit val endpointFormat = jsonFormat3(SchemasEndpointResponse)

  override def route: Route =
    get {
      pathPrefix("schemas" / Segment) { subject =>
        parameters('pretty ? "false") { pretty =>
          handleExceptions(excptHandler) {
            val schemaSubject = subject + "-value"
            val schemaMeta = registry.getLatestSchemaMetadata(schemaSubject)
            val schema = registry.getByID(schemaMeta.getId)
            val txt = schema.toString(pretty.toBoolean)
            complete(OK, txt)
          }
        }
      }
    } ~
      post {
        pathPrefix("schemas") {
          entity(as[String]) { json =>
            handleExceptions(excptHandler) {
              val schema = new Parser().parse(json)
              val name = schema.getNamespace() + "." + schema.getName()
              log.debug(s"Registering schema $name: $json")
              val id = registry.register(name + "-value", schema)
              val response = SchemasEndpointResponse(200, "Schema registered.", id.toString)
              complete(response)
            }
          }
        }
      }


  val excptHandler = ExceptionHandler {
    case e: RestClientException =>
      extractUri { uri =>
        complete(BadRequest, s"Unable to find schema for ${uri.path.tail} : ${e.getMessage}")
      }

    case e: SchemaParseException =>
      complete(BadRequest, s"Unable to parse avro schema: ${e.getMessage}")

    case e: Exception =>
      extractUri { uri =>
        log.warn(s"Request to $uri could not be handled normally")
        complete(BadRequest, s"Unable to complete request for ${uri.path.tail} : ${e.getMessage}")
      }
  }
}

case class SchemasEndpointResponse(status: Int, description: String, id: String)