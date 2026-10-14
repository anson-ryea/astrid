package run

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Returns scripted replies without making provider calls. */
internal class ScriptedPromptExecutor(
    replies: List<String>,
    private val responseFactory: suspend (String) -> Message.Assistant = {
        Message.Assistant(it, ResponseMetaInfo.Empty)
    },
) : PromptExecutor() {
    private val remainingReplies = ArrayDeque(replies)
    private val replyMutex = Mutex()

    val prompts = mutableListOf<Prompt>()
    val offeredTools = mutableListOf<List<ToolDescriptor>>()

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        val reply = replyMutex.withLock {
            prompts += prompt
            offeredTools += tools
            check(remainingReplies.isNotEmpty()) {
                "no scripted model reply remains"
            }
            remainingReplies.removeFirst()
        }

        return responseFactory(reply)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> =
        error("streaming is not expected in these tests")

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult =
        error("moderation is not expected in these tests")

    override fun close() = Unit
}
