package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GelabZeroParserTest {
    private val parser = GelabZeroParser()

    @Test
    fun `parses click response with thinking and summary`() {
        val result = parser.parseResponse(
            """
            <THINK>需要点击确认按钮</THINK>
            explain:点确认	action:CLICK	point:500,600	summary:已点击确认
            """.trimIndent()
        )

        assertTrue(result.success)
        assertEquals("需要点击确认按钮", result.step?.observation)
        assertEquals("点确认", result.step?.thought)
        assertEquals("已点击确认", result.step?.summary)
        val action = result.step?.action as ClickAction
        assertEquals(500f, action.x, 0f)
        assertEquals(600f, action.y, 0f)
    }

    @Test
    fun `parses type response`() {
        val result = parser.parseResponse(
            """
            <THINK>输入用户指定内容</THINK>
            explain:输入	action:TYPE	value:你好，小万	summary:已输入文本
            """.trimIndent()
        )

        assertTrue(result.success)
        assertEquals("已输入文本", result.step?.summary)
        val action = result.step?.action as TypeAction
        assertEquals("你好，小万", action.content)
    }

    @Test
    fun `parses complete response`() {
        val result = parser.parseResponse(
            """
            <THINK>任务已经完成</THINK>
            explain:完成	action:COMPLETE	return:已经完成	summary:任务完成
            """.trimIndent()
        )

        assertTrue(result.success)
        val action = result.step?.action as FinishedAction
        assertEquals("已经完成", action.content)
    }

    @Test
    fun `returns failure for missing action`() {
        val result = parser.parseResponse(
            """
            <THINK>缺少动作</THINK>
            explain:无动作	summary:未执行
            """.trimIndent()
        )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("Missing action field"))
    }
}
