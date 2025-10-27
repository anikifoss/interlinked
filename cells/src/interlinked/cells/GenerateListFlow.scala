package interlinked.cells

import com.rallyhealth.weejson.v1.jackson.*
import com.rallyhealth.weepickle.v1.WeePickle.*
import interlinked.core.PromptTemplate
import interlinked.core.UserPrompt
import interlinked.util.AsyncLlmClient
import interlinked.util.AsyncLlmClient.ResponseFormat
import org.apache.pekko.Done
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.*
import org.apache.pekko.stream.scaladsl.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal


object GenerateListFlow:
  private val logger = LoggerFactory.getLogger(this.getClass.getName)

  case class ListRequest(current_items: List[ListItem])
  case class ListResponse(response_status: String, new_items: List[ListItem])
  case class ListItem(name: String, details: String)

  object ListRequest:
    given rw: FromTo[ListRequest] = macroFromTo
  object ListResponse:
    given rw: FromTo[ListResponse] = macroFromTo
  object ListItem:
    given rw: FromTo[ListItem] = macroFromTo

  private[cells] val InconsistentLlmResultStatus = "internal_inconsistent_status"
  private[cells] val AddedItemsStatus = "added_new_items"
  private[cells] val NoMoreItemsStatus = "nothing_to_add_because_all_items_are_exhaustively_enumerated"
  private[cells] val addItemsExample = ListResponse(
    response_status = AddedItemsStatus,
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
      |{{{userPrompt.context}}}
      |
      |{{{userPrompt.task}}}
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
        "userPrompt" -> Map(
          "context" -> userPrompt.context,
          "task" -> userPrompt.task,
        ),
        "items" -> FromScala(ListRequest(items)).transform(ToPrettyJson.string),
        "addItemsExample" -> FromScala(addItemsExample).transform(ToPrettyJson.string),
        "noMoreItemsExample" -> FromScala(noMoreItemsExample).transform(ToPrettyJson.string),
      )
    )

  def apply(client: AsyncLlmClient, maxAttempts: Int): Flow[UserPrompt, List[ListItem], NotUsed] =
    Flow[UserPrompt]
      .mapAsync(parallelism = 1): userPrompt =>
        val currentList = List[ListItem]()
        callModelWhileMoreItems(client, userPrompt, currentList, maxAttempts, maxAttempts)

  private def callModelWhileMoreItems(
    client: AsyncLlmClient,
    userPrompt: UserPrompt,
    currentList: List[ListItem],
    maxAttempts: Int,
    remainingAttempts: Int
  ): Future[List[ListItem]] =
    val fullPrompt = GenerateListPrompt.render(userPrompt, currentList)
    if logger.isDebugEnabled then logger.debug("GenerateListFlow prompt\n" + fullPrompt)
    callModelWithRetries(client, fullPrompt, currentList, maxAttempts)
      .flatMap: listResponse =>
        listResponse.response_status match
          case NoMoreItemsStatus =>
            Future.successful(listResponse.new_items)
          case AddedItemsStatus =>
            callModelWhileMoreItems(client, userPrompt, listResponse.new_items, maxAttempts, maxAttempts)
          case _ if remainingAttempts > 1 =>
            callModelWhileMoreItems(client, userPrompt, listResponse.new_items, maxAttempts, remainingAttempts - 1)
          case _ =>
            Future.failed(new RuntimeException(s"Inconsistent LLM responses exhausted $maxAttempts attempts."))

  private def callModelWithRetries(
    client: AsyncLlmClient,
    fullPrompt: String,
    currentList: List[ListItem],
    remainingAttempts: Int
  ): Future[ListResponse] =
    client
      .callModel(fullPrompt) //DEBUG, streamTo = Some(System.err))
      .map: llmResponse =>
        val listResponse = FromJson(llmResponse.maybeJson).transform(ToScala[ListResponse])
        val mergedList = mergeItems(currentList, listResponse.new_items)
        val hasUpdates = mergedList != currentList
        val result_status = (listResponse.response_status, hasUpdates) match
          case (NoMoreItemsStatus, false) => NoMoreItemsStatus
          case (AddedItemsStatus, true) => AddedItemsStatus
          case _ => InconsistentLlmResultStatus
        ListResponse(result_status, mergedList)
      .recoverWith:
        case NonFatal(e) =>
          if remainingAttempts > 1 then
            logger.error("Error while calling LLM model.", e)
            callModelWithRetries(client, fullPrompt, currentList, remainingAttempts - 1)
          else
            Future.failed(e)

  private def mergeItems(
    currentList: List[ListItem],
    newItems: List[ListItem]
  ): List[ListItem] =
    val currentNames = currentList.map(_.name).toSet
    val filteredNewItems = newItems.filterNot(item => currentNames.contains(item.name))
    currentList ++ filteredNewItems
