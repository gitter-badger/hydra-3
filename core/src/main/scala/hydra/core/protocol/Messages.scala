package hydra.core.protocol

import akka.actor.ActorRef
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import hydra.core.ingest.HydraRequest
import hydra.core.transport.{HydraRecord, RecordMetadata}
import org.joda.time.DateTime

/**
  * Created by alexsilva on 2/22/17.
  */
trait HydraMessage

trait HydraError extends HydraMessage {
  def error: Throwable

}

case class IngestionError(error: Throwable) extends HydraError

case class Validate(req: HydraRequest) extends HydraMessage

trait MessageValidationResult extends HydraMessage

case class Publish(request: HydraRequest) extends HydraMessage

case class Ingest[K, V](record: HydraRecord[K, V]) extends HydraMessage

case object Join extends HydraMessage

case object Ignore extends HydraMessage

case class InitiateRequest(request: HydraRequest) extends HydraMessage


case class Produce[K, V](record: HydraRecord[K, V], ingestor: ActorRef, supervisor: ActorRef,
                         deliveryId: Long = 0) extends HydraMessage

/**
  * Produces a message without having to track ingestor and supervisors.
  * No acknowledgment logic is provided either.
  *
  * @param kafkaRecord
  */
case class ProduceOnly[K, V](kafkaRecord: HydraRecord[K, V]) extends HydraMessage

case class RecordProduced(md: RecordMetadata, supervisor: Option[ActorRef] = None) extends HydraMessage

case class RecordNotProduced[K, V](record: HydraRecord[K, V], error: Throwable,
                                   supervisor: Option[ActorRef] = None) extends HydraMessage

//case class ProducerAck(supervisor: ActorRef, error: Option[Throwable])

//todo:rename this class
case class HydraIngestionError(ingestor: String, error: Throwable,
                               request: HydraRequest, time: DateTime = DateTime.now) extends HydraError


sealed trait IngestorStatus extends HydraMessage with Product {
  val name: String = productPrefix

  /**
    * Whether this Transport is completed, either by completing the ingestion or error.
    */
  val completed: Boolean = false

  def statusCode: StatusCode

  def message: String = statusCode.reason()
}


case object IngestorJoined extends IngestorStatus {
  val statusCode = StatusCodes.Accepted
}

case object WaitingForAck extends IngestorStatus {
  val statusCode = StatusCodes.Processing
}

case object IngestorIgnored extends IngestorStatus {
  val statusCode = StatusCodes.NotAcceptable
}

case object RequestPublished extends IngestorStatus {
  val statusCode = StatusCodes.Created
}

case class IngestorError(error: Throwable) extends IngestorStatus with HydraError {
  override val completed = true
  override val message = Option(error.getMessage).getOrElse("")
  val statusCode = StatusCodes.ServiceUnavailable
}

case class InvalidRequest(error: Throwable) extends IngestorStatus with HydraError with MessageValidationResult {
  def this(msg: String) = this(new IllegalArgumentException(msg))

  override val completed = true
  override val message = Option(error.getMessage).getOrElse("")
  val statusCode = StatusCodes.BadRequest
}

case class ValidRequest[K, V](record: HydraRecord[K, V]) extends IngestorStatus with MessageValidationResult {
  override val completed = true
  val statusCode = StatusCodes.Continue
}

case object IngestorTimeout extends IngestorStatus {
  override val completed = true
  val statusCode = StatusCodes.RequestTimeout
}

case object IngestorCompleted extends IngestorStatus {
  override val completed = true
  val statusCode = StatusCodes.OK
}



