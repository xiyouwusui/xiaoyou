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
            verify:上一步已生效，因此我判断 符合 上一步预期	note:确认按钮可见	explain:点确认	action:CLICK	point:500,600	key_process:已点击确认
            """.trimIndent()
        )

        assertTrue(result.success)
        assertTrue(result.step?.observation.orEmpty().contains("需要点击确认按钮"))
        assertTrue(result.step?.observation.orEmpty().contains("确认按钮可见"))
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
    fun `parses gelab adv action aliases`() {
        val callUser = parser.parseResponse(
            """
            <think>需要用户处理登录</think>
            verify:登录页出现，因此我判断 符合 上一步预期	note:当前是登录页	explain:请求接管	action:CALL_USER	value:请完成登录	key_process:等待用户登录
            """.trimIndent()
        )

        assertTrue(callUser.success)
        assertEquals("等待用户登录", callUser.step?.summary)
        assertEquals("请完成登录", (callUser.step?.action as InfoAction).value)

        val longPress = parser.parseResponse(
            """
            verify:none	note:列表项可见	explain:长按列表	action:LONG_PRESS	point:100 200	key_process:准备打开菜单
            """.trimIndent()
        )

        assertTrue(longPress.success)
        val action = longPress.step?.action as LongPressAction
        assertEquals(100f, action.x, 0f)
        assertEquals(200f, action.y, 0f)
    }

    @Test
    fun `parses direct back and home actions`() {
        val back = parser.parseResponse(
            "verify:none\tnote:none\texplain:返回\taction:BACK\tkey_process:返回上一页"
        )
        assertTrue(back.success)
        assertTrue(back.step?.action is PressBackAction)

        val home = parser.parseResponse(
            "verify:none\tnote:none\texplain:回首页\taction:HOME\tkey_process:回到桌面"
        )
        assertTrue(home.success)
        assertTrue(home.step?.action is PressHomeAction)
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
