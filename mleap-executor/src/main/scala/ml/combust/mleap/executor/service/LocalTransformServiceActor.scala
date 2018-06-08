package ml.combust.mleap.executor.service

import akka.actor.{Actor, ActorRef, Props, Status, Terminated}
import akka.stream.{ActorMaterializer, Materializer}
import ml.combust.mleap.executor.repository.RepositoryBundleLoader
import ml.combust.mleap.executor._

object LocalTransformServiceActor {
  def props(loader: RepositoryBundleLoader): Props = {
    Props(new LocalTransformServiceActor(loader))
  }

  object Messages {
    case object Close
  }
}

class LocalTransformServiceActor(loader: RepositoryBundleLoader) extends Actor {
  import LocalTransformServiceActor.Messages
  private var id: Int = 0

  private implicit val materializer: Materializer = ActorMaterializer()(context.system)

  private var lookup: Map[String, ActorRef] = Map()
  private var modelNameLookup: Map[ActorRef, String] = Map()

  override def postStop(): Unit = {
    for (child <- context.children) {
      context.unwatch(child)
      context.stop(child)
    }
  }

  override def receive: Receive = {
    case request: GetBundleMetaRequest => handleModelRequest(request)
    case request: GetModelRequest => handleModelRequest(request)
    case request: TransformFrameRequest => handleModelRequest(request)
    case request: CreateFrameStreamRequest => handleModelRequest(request)
    case request: CreateRowStreamRequest => handleModelRequest(request)
    case request: CreateFrameFlowRequest => handleModelRequest(request)
    case request: CreateRowFlowRequest => handleModelRequest(request)
    case request: UnloadModelRequest => handleModelRequest(request)
    case request: LoadModelRequest => loadModel(request)
    case Messages.Close => context.stop(self)

    case Terminated(actor) => terminated(actor)
  }

  def handleModelRequest(request: ModelRequest): Unit = {
    lookup.get(request.modelName) match {
      case Some(actor) => actor.tell(request, sender)
      case None => sender ! Status.Failure(new NoSuchElementException(s"no model with name ${request.modelName}"))
    }
  }

  def loadModel(request: LoadModelRequest): Unit = {
    lookup.get(request.modelName) match {
      case Some(_) => sender ! Status.Failure
      case None =>
        val actor = context.actorOf(BundleActor.props(request, loader), s"bundle-$id")
        id += 1
        lookup += (request.modelName -> actor)
        modelNameLookup += (actor -> request.modelName)
        context.watch(actor)
        actor.tell(request, sender)
    }
  }

  private def terminated(ref: ActorRef): Unit = {
    val uri = modelNameLookup(ref)
    modelNameLookup -= ref
    lookup -= uri
  }
}