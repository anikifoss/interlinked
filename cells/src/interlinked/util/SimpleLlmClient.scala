package interlinked.util

import com.rallyhealth.weejson.v1.jackson.*
import com.rallyhealth.weepickle.v1.WeePickle.*

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import scala.util.control.NonFatal


private object SimpleLlmClient:
  case class Message(role: String, content: String)
  case class ChatRequest(
      model: Option[String],
      messages: List[Message],
      temperature: Option[Double] = None,
      response_format: Option[ResponseFormat] = None,
      stream: Boolean = false
  )
  case class ResponseFormat(`type`: String)
  case class Choice(message: Message)
  case class ChatResponse(choices: List[Choice])
  case class StreamDelta(content: Option[String])
  case class StreamChoice(delta: StreamDelta)
  case class StreamResponse(choices: List[StreamChoice])

  object Message:
    implicit val rw: FromTo[Message] = macroFromTo
  object ChatRequest:
    implicit val rw: FromTo[ChatRequest] = macroFromTo
  object ResponseFormat:
    implicit val rw: FromTo[ResponseFormat] = macroFromTo
  object Choice:
    implicit val rw: FromTo[Choice] = macroFromTo
  object ChatResponse:
    implicit val rw: FromTo[ChatResponse] = macroFromTo
  object StreamDelta:
    implicit val rw: FromTo[StreamDelta] = macroFromTo
  object StreamChoice:
    implicit val rw: FromTo[StreamChoice] = macroFromTo
  object StreamResponse:
    implicit val rw: FromTo[StreamResponse] = macroFromTo
end SimpleLlmClient


private[util] class SimpleLlmClient(
    val baseUrl: String,
    val apiKey: Option[String] = None,
    val model: Option[String] = None,
    val connectTimeoutMs: Int = 30000,
    val readTimeoutMs: Int = 300000,
):
  import SimpleLlmClient.*

  def callModel(
    prompt: String,
    responseFormat: Option[String] = None,
    streamTo: Option[OutputStream] = None
  ): String =
    val payload = ChatRequest(
      model = model,
      messages = List(Message("user", prompt)),
      response_format = responseFormat.map(v => ResponseFormat(v)),
      stream = streamTo.isDefined
    )

    streamTo match
      case Some(out) => callStreaming(out, payload)
      case None => callNonStreaming(payload)

  private def callStreaming(out: OutputStream, payload: ChatRequest): String =
    val responseBytes = ByteArrayOutputStream()
    val data = FromScala(payload).transform(ToJson.string)
    val readable: geny.Readable = requests1.post.stream(
      s"$baseUrl/chat/completions",
      data = data,
      headers = Map(
        "Content-Type" -> "application/json",
        "Authorization" -> s"Bearer ${apiKey.getOrElse("None")}"
      ),
      readTimeout = readTimeoutMs,
      connectTimeout = connectTimeoutMs
    )

    readable.readBytesThrough: in =>
      val buffer = new Array[Byte](1024)
      val leftover = new StringBuilder()

      var bytesRead = in.read(buffer)
      while bytesRead != -1 do
        leftover.append(new String(buffer, 0, bytesRead))
        // limit = -1 guarantees an empty string at the end if '\n' is the last char.
        val lines = leftover.toString.split("\n", -1)

        for i <- 0 until lines.length - 1 do
          handleStreamingChunk(out, lines(i), responseBytes)

        if lines.size > 1 then
          leftover.clear()
          leftover.append(lines.last)

        bytesRead = in.read(buffer)
      end while

      // Flush the remaining data.
      handleStreamingChunk(out, leftover.toString, responseBytes)

    responseBytes.toString("UTF-8")
  end callStreaming

  private def handleStreamingChunk(
    out: OutputStream,
    chunk: String,
    responseBytes: ByteArrayOutputStream
  ): Unit =
    if chunk.startsWith("data: ") then
      val maybeJson = chunk.stripPrefix("data: ")
      if maybeJson.trim != "[DONE]" then
        try
          val parsed = FromJson(maybeJson).transform(ToScala[StreamResponse])
          parsed.choices.headOption
            .flatMap(_.delta.content)
            .foreach: content =>
              out.write(content.getBytes())
              out.flush()
              responseBytes.write(content.getBytes("UTF-8"))
        catch
          case NonFatal(_) => // Ignore malformed JSON.

  private def callNonStreaming(payload: ChatRequest): String =
    val data: String = FromScala(payload).transform(ToJson.string)
    val resp = requests1.post(
      s"$baseUrl/chat/completions",
      data = data,
      headers = Map(
        "Content-Type" -> "application/json",
        "Authorization" -> s"Bearer ${apiKey.getOrElse("None")}"
      ),
      readTimeout = readTimeoutMs,
      connectTimeout = connectTimeoutMs
    )

    val parsed = FromJson(resp.text()).transform(ToScala[ChatResponse])
    parsed.choices.headOption.map(_.message.content).getOrElse("")

end SimpleLlmClient
