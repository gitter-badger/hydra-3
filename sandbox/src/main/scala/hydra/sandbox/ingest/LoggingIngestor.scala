package hydra.sandbox.ingest

import hydra.core.ingest.{HydraRequest, Ingestor}
import hydra.core.protocol._
import hydra.core.transport.{RecordFactory, StringRecord}

import scala.util.Success

/**
  * A simple example transport that writes requests with a certain attribute to a log.
  *
  * Created by alexsilva on 2/27/17.
  */
// $COVERAGE-OFF$
class LoggingIngestor extends Ingestor {
  override val recordFactory = new RecordFactory[String, String] {
    override def build(request: HydraRequest) = Success(StringRecord("", None, request.payload))
  }
  ingest {
    case Publish(request) =>
      sender ! (if (request.metadataValueEquals("logging-enabled", "true")) Join else Ignore)

    case Ingest(record) =>
      log.info(record.payload.toString)
      sender ! IngestorCompleted
  }
}
// $COVERAGE-ON
