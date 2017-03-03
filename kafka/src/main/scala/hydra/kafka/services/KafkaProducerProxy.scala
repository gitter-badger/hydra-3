package hydra.kafka.services

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props, Stash}
import akka.kafka.ProducerSettings
import com.typesafe.config.Config
import hydra.common.config.ConfigSupport
import hydra.common.logging.LoggingAdapter
import hydra.core.protocol.{Produce, RecordNotProduced, RecordProduced}
import hydra.kafka.config.KafkaConfigSupport
import hydra.kafka.producer.{KafkaRecord, KafkaRecordMetadata, PropagateExceptionCallback}
import hydra.kafka.services.KafkaProducerProxy.ProducerInitialized
import org.apache.kafka.clients.producer.KafkaProducer

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
  * Created by alexsilva on 10/7/16.
  */
class KafkaProducerProxy(parent: ActorRef, producerConfig: Config) extends Actor
  with ConfigSupport with LoggingAdapter with Stash {

  /**
    * We make this a var so that it can be re-assigned during preRestart() if needed.
    */
  private[services] var producer: KafkaProducer[Any, Any] = _

  implicit val ec = context.dispatcher

  def receive = initializing

  def initializing: Receive = {
    case ProducerInitialized =>
      context.become(receiving)
      unstashAll()
    case _ => stash()
  }

  def receiving: Receive = {
    case Produce(r: KafkaRecord[Any, Any]) =>
      val path = self.path
      //we have to wrap this in a try because the producer tries to serialize
      // the message synchronously before sending it
      Try(producer.send(r, new PropagateExceptionCallback(context.actorSelection(path), r))).recover {
        case e: Exception => parent ! RecordNotProduced[Any, Any](r, e)
      }

    case kmd: KafkaRecordMetadata =>
      parent ! RecordProduced(kmd)

    case err: RecordNotProduced[Any, Any] =>
      parent ! err
      throw err.error
  }

  def initProducer(): Unit = {
    Future(createProducer()) onComplete {
      case Success(prod) =>
        log.debug(s"Initialized producer with settings $producerConfig")
        producer = prod
        self ! ProducerInitialized
      case Failure(ex) =>
        log.error("Unable to initialize producer.", ex)
        throw new InvalidProducerSettingsException(ex.getMessage)
    }
  }

  override def preStart(): Unit = {
    initProducer()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    initProducer()
    message collect {
      //try sending it again
      case Some(msg: RecordNotProduced[_, _]) => self ! Produce(msg.record)
    }
  }

  private def createProducer(): KafkaProducer[Any, Any] = {
    val akkaConfigs = rootConfig.getConfig("akka.kafka.producer")
    val configs = akkaConfigs.withFallback(producerConfig.atKey("kafka-clients"))
    ProducerSettings[Any, Any](configs, None, None).createKafkaProducer()
  }

  override def postStop(): Unit = {
    producer.flush()
    producer.close(10000, TimeUnit.MILLISECONDS)
  }
}

object KafkaProducerProxy extends KafkaConfigSupport {

  def props(parent: ActorRef, producerConfig: Config): Props =
    Props(classOf[KafkaProducerProxy], parent, producerConfig)

  def props(parent: ActorRef, format: String): Props = {
    kafkaProducerFormats.get(format) match {
      case Some(config) => Props(classOf[KafkaProducerProxy], parent, config)
      case None => throw new InvalidProducerSettingsException(s"No configuration found for format '$format'.")
    }
  }

  case object ProducerInitialized

}

case class InvalidProducerSettingsException(msg: String) extends RuntimeException(msg)
