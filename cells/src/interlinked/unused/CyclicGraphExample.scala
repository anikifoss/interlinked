package interlinked.unused

import com.rallyhealth.weejson.v1.jackson.*
import com.rallyhealth.weepickle.v1.WeePickle.*
import interlinked.core.*
import interlinked.util.AsyncLlmClient
import interlinked.util.AsyncLlmClient.ResponseFormat
import org.apache.pekko.Done
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.*

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration


object CyclicGraphExample:
  case class ListRequest(current_items: List[ListItem])
  case class ListResponse(response_status: String, new_items: List[ListItem])
  case class ListItem(name: String, details: String)

  object ListRequest:
    given rw: FromTo[ListRequest] = macroFromTo
  object ListResponse:
    given rw: FromTo[ListResponse] = macroFromTo
  object ListItem:
    given rw: FromTo[ListItem] = macroFromTo

  private val NoMoreItemsStatus = "nothing_to_add_because_all_items_are_exhaustively_enumerated"
  private val addItemsExample = ListResponse(
    response_status = "appended_new_items",
    new_items = List(
      ListItem(name = "Name1", details = "Name1Details"),
      ListItem(name = "Name2", details = "Name2Details")
    )
  )
  private val noMoreItemsExample = ListResponse(
    response_status = NoMoreItemsStatus,
    new_items = List()
  )

  object GenerateListPrompt extends PromptTemplate(
    """
      |{{{userPrompt}}}
      |
      |So far we have this list:
      |{{{items}}}
      |
      |If there are more items to append to the list, then return new items as JSON, following this exact format:
      |{{{addItemsExample}}}
      |
      |Otherwise, if there are no more items to add, then return this exact JSON:
      |{{{noMoreItemsExample}}}
      |""".stripMargin
  ):
    def render(userPrompt: UserPrompt, items: List[ListItem]): String = render(
      Map(
        "userPrompt" -> userPrompt.task,
        "items" -> FromScala(ListRequest(items)).transform(ToPrettyJson.string),
        "addItemsExample" -> FromScala(addItemsExample).transform(ToPrettyJson.string),
        "noMoreItemsExample" -> FromScala(noMoreItemsExample).transform(ToPrettyJson.string),
      )
    )

  private case class Context[T](userPrompt: UserPrompt, value: T)

  def apply(client: AsyncLlmClient): Flow[UserPrompt, List[ListItem], NotUsed] =
    Flow.fromGraph:
      GraphDSL.create(): builder =>
        given GraphDSL.Builder[NotUsed] = builder
        import GraphDSL.Implicits.given

        val inputAdapter = builder.add(Flow[UserPrompt].map(userPrompt => Context(userPrompt, List[ListItem]())))
        val mergeToLoop = builder.add(MergePreferred[Context[List[ListItem]]](1, eagerComplete = false))
        val genList = builder.add(genListSingleIteration(client))
        val routeCompletedTo1 = builder.add(Partition[Context[ListResponse]](
          2,
          ctx =>
            val listResponse = ctx.value
            listResponse.response_status match
              case NoMoreItemsStatus => 1
              case _ => 0
        ))
        val incompleteItems = builder.add(Flow[Context[ListResponse]].map(ctx => ctx.copy(value = ctx.value.new_items)))
        val completeItems = builder.add(Flow[Context[ListResponse]].map(ctx => ctx.value.new_items))
        
        inputAdapter.out ~> mergeToLoop.in(0)
        mergeToLoop.out ~> genList ~> routeCompletedTo1.in
        routeCompletedTo1.out(0) ~> incompleteItems ~> mergeToLoop.preferred
        routeCompletedTo1.out(1) ~> completeItems.in

        FlowShape(inputAdapter.in, completeItems.out)
    .named("GenerateListFlow")

  private def genListSingleIteration(
    client: AsyncLlmClient
  ): Flow[Context[List[ListItem]], Context[ListResponse], NotUsed] =
    Flow[Context[List[ListItem]]]
      .mapAsync(parallelism = 1):
        case Context(userPrompt, currentList) =>
          val fullPrompt = GenerateListPrompt.render(userPrompt, currentList)
          callModelWithRetries(client, fullPrompt, currentList)
            .map: listResponse =>
              Context(userPrompt, listResponse)

  private def callModelWithRetries(
    client: AsyncLlmClient,
    fullPrompt: String,
    currentList: List[ListItem]
  ): Future[ListResponse] =
    // TODO error handling and retries
    client
      .callModel(fullPrompt) //XXX, streamTo = Some(System.err))
      .map: llmResponse =>
        val listResponse = FromJson(llmResponse.maybeJson).transform(ToScala[ListResponse])
        ListResponse(
          listResponse.response_status,
          mergeItems(currentList, listResponse.new_items)
        )

  private def mergeItems(
    currentList: List[ListItem],
    newItems: List[ListItem]
  ): List[ListItem] =
    val currentNames = currentList.map(_.name).toSet
    val filteredNewItems = newItems.filterNot(item => currentNames.contains(item.name))
    currentList ++ filteredNewItems


  def run(): Unit =
    given system: ActorSystem = ActorSystem("Cells")

    val client = AsyncLlmClient(
      baseUrl = "http://localhost:8090/v1"
    )

    val userPrompts = List(
      UserPrompt(context = "", task = "What are the items in the list [1, 2]?"),
    )

    val graph: RunnableGraph[Future[Done]] =
      Source(userPrompts)
        .via(CyclicGraphExample(client))
        .toMat(Sink.foreach(println))(Keep.right)

    val done: Future[Done] = graph.run()
    done.onComplete(_ => system.terminate())
    Await.result(done, Duration.Inf)
