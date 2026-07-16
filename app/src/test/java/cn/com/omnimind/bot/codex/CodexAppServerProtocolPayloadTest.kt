package cn.com.omnimind.bot.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerProtocolPayloadTest {

    @Test
    fun sanitizeCodexAbsolutePathKeepsLastCleanAbsolutePath() {
        val path = sanitizeCodexAbsolutePath(
            """
            init-host: shell warmup
            /workspace
            warning: ignored trailing log
            """.trimIndent()
        )

        assertEquals("/workspace", path)
    }

    @Test
    fun sanitizeCodexAbsolutePathRejectsRelativeOutput() {
        assertNull(sanitizeCodexAbsolutePath("workspace"))
    }

    @Test
    fun buildCodexTextInputMatchesAppServerTextShape() {
        val input = buildCodexTextInput(" hello ")

        assertEquals(1, input.size)
        assertEquals("text", input[0]["type"])
        assertEquals("hello", input[0]["text"])
        assertTrue(input[0].containsKey("text_elements"))
    }

    @Test
    fun buildDefaultCodexSandboxPolicyUsesAbsoluteWritableRoot() {
        val policy = buildDefaultCodexSandboxPolicy("noise\n/workspace")

        assertEquals("workspaceWrite", policy["type"])
        assertEquals(listOf("/workspace"), policy["writableRoots"])
        assertEquals(true, policy["networkAccess"])
        assertEquals(false, policy["excludeTmpdirEnvVar"])
        assertEquals(false, policy["excludeSlashTmp"])
    }

    @Test
    fun addCodexOptionalRunParamsForwardsModelAndPlanMode() {
        val params = linkedMapOf<String, Any?>("threadId" to "thread-1")

        addCodexOptionalRunParams(
            params,
            mapOf(
                "model" to "gpt-5-codex",
                "effort" to "high",
                "collaborationMode" to "plan",
                "serviceTier" to "auto"
            )
        )

        assertEquals("gpt-5-codex", params["model"])
        assertEquals("high", params["effort"])
        val collaborationMode = params["collaborationMode"] as? Map<*, *>
        val settings = collaborationMode?.get("settings") as? Map<*, *>
        assertEquals("plan", collaborationMode?.get("mode"))
        assertEquals("gpt-5-codex", settings?.get("model"))
        assertEquals("high", settings?.get("reasoning_effort"))
        assertEquals("auto", params["serviceTier"])
    }

    @Test
    fun resolveCodexCollaborationModeFillsStructuredModeSettings() {
        val mode = resolveCodexCollaborationMode(
            mapOf(
                "model" to "gpt-5-codex",
                "collaborationMode" to mapOf(
                    "mode" to "plan",
                    "settings" to mapOf("developer_instructions" to "Use a checklist.")
                )
            )
        )
        val settings = mode?.get("settings") as? Map<*, *>

        assertEquals("plan", mode?.get("mode"))
        assertEquals("gpt-5-codex", settings?.get("model"))
        assertEquals("Use a checklist.", settings?.get("developer_instructions"))
    }

    @Test
    fun resolveCodexCollaborationModeRequiresModel() {
        val params = linkedMapOf<String, Any?>("threadId" to "thread-1")

        addCodexOptionalRunParams(
            params,
            mapOf("collaborationMode" to "plan")
        )

        assertEquals(false, params.containsKey("collaborationMode"))
    }

    @Test
    fun resolveCodexReviewTargetDefaultsToUncommittedChanges() {
        val target = resolveCodexReviewTarget(null)

        assertEquals("uncommittedChanges", target["type"])
    }

    @Test
    fun resolveCodexReviewTargetPreservesExplicitTarget() {
        val target = resolveCodexReviewTarget(
            mapOf(
                "type" to "baseBranch",
                "branch" to "main"
            )
        )

        assertEquals("baseBranch", target["type"])
        assertEquals("main", target["branch"])
    }

    @Test
    fun remoteBridgeConfigRequiresUrlAndCwd() {
        assertTrue(
            CodexRemoteBridgeConfig(
                bridgeUrl = "ws://127.0.0.1:17321/codex",
                cwd = "/Users/ocean/code/project"
            ).isConfigured
        )
        assertEquals(
            false,
            CodexRemoteBridgeConfig(
                bridgeUrl = "ws://127.0.0.1:17321/codex",
                cwd = ""
            ).isConfigured
        )
    }

    @Test
    fun normalizeBridgeUrlsAcceptHostPortAndDefaultPaths() {
        assertEquals(
            "ws://192.168.1.10:17321/codex",
            normalizeCodexBridgeWebSocketUrl("192.168.1.10:17321")
        )
        assertEquals(
            "http://192.168.1.10:17321/health",
            normalizeCodexBridgeHealthUrl("ws://192.168.1.10:17321/codex")
        )
        assertEquals(
            "http://192.168.1.10:17321/fs/list",
            normalizeCodexBridgeFsListUrl("ws://192.168.1.10:17321/codex")
        )
    }

    @Test
    fun defaultThreadSourceKindsUseCurrentCodexAppServerVariants() {
        assertTrue(DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("cli"))
        assertTrue(DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("appServer"))
        assertTrue(DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("subAgentOther"))
        assertEquals(false, DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("interactive"))
        assertEquals(false, DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("background"))
        assertEquals(false, DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("subAgentInteractive"))
    }

    @Test
    fun withLocalIdsInjectsActiveAndActiveTurnIdWhenActive() {
        val response = mapOf<String, Any?>("thread" to mapOf("id" to "thread-1"))

        val enriched = response.withLocalIds(
            threadId = "thread-1",
            conversationId = 42L,
            turnId = "turn-7",
            active = true,
        )

        assertEquals("thread-1", enriched["threadId"])
        assertEquals(42L, enriched["conversationId"])
        assertEquals("turn-7", enriched["turnId"])
        assertEquals("turn-7", enriched["activeTurnId"])
        assertEquals(true, enriched["active"])
    }

    @Test
    fun withLocalIdsSurfacesInactiveWithoutActiveTurnId() {
        val response = mapOf<String, Any?>("thread" to mapOf("id" to "thread-1"))

        val enriched = response.withLocalIds(
            threadId = "thread-1",
            conversationId = 99L,
            turnId = null,
            active = false,
        )

        assertEquals(false, enriched["active"])
        assertNull(enriched["turnId"])
        assertNull(enriched["activeTurnId"])
    }

    @Test
    fun withLocalIdsOmitsActiveFieldsWhenNotProvided() {
        val response = mapOf<String, Any?>("thread" to mapOf("id" to "thread-1"))

        val enriched = response.withLocalIds(
            threadId = "thread-1",
            conversationId = null,
        )

        assertEquals("thread-1", enriched["threadId"])
        assertEquals(false, enriched.containsKey("active"))
        assertEquals(false, enriched.containsKey("activeTurnId"))
        assertEquals(false, enriched.containsKey("turnId"))
    }

    @Test
    fun buildChatGptCodexConfigUsesBuiltInOpenAiProvider() {
        val config = buildCodexConfigToml(
            authMode = CodexLocalAuthMode.CHATGPT,
            baseUrl = "",
            model = "gpt-5.5"
        )

        assertTrue(config.contains("model_provider = \"openai\""))
        assertTrue(config.contains("model = \"gpt-5.5\""))
        assertTrue(config.contains("cli_auth_credentials_store = \"file\""))
        assertFalse(config.contains("[model_providers.omnimind]"))
        assertFalse(config.contains("OMNIBOT_CODEX_API_KEY"))
    }

    @Test
    fun buildCustomApiCodexConfigUsesDedicatedEnvironmentKey() {
        val config = buildCodexConfigToml(
            authMode = CodexLocalAuthMode.API,
            baseUrl = "https://example.com/v1",
            model = "custom-codex"
        )

        assertTrue(config.contains("model_provider = \"omnimind\""))
        assertTrue(config.contains("model = \"custom-codex\""))
        assertTrue(config.contains("base_url = \"https://example.com/v1\""))
        assertTrue(config.contains("env_key = \"OMNIBOT_CODEX_API_KEY\""))
        assertTrue(config.contains("requires_openai_auth = false"))
    }

    @Test
    fun buildLocalCodexEnvironmentOnlyExposesCustomApiKeyInApiMode() {
        assertEquals(
            mapOf("OMNIBOT_CODEX_API_KEY" to "secret"),
            buildCodexLocalEnvironment(
                authMode = CodexLocalAuthMode.API,
                apiKey = " secret "
            )
        )
        assertTrue(
            buildCodexLocalEnvironment(
                authMode = CodexLocalAuthMode.CHATGPT,
                apiKey = "secret"
            ).isEmpty()
        )
    }

    @Test
    fun migrateLegacyCodexConfigRecognizesChatGptTokensWithoutConfigToml() {
        val config = migrateLegacyCodexLocalConfig(
            configToml = "",
            authJson = """
                {
                  "OPENAI_API_KEY": null,
                  "tokens": {
                    "access_token": "access-token",
                    "refresh_token": "refresh-token"
                  }
                }
            """.trimIndent()
        )

        assertEquals(CodexLocalAuthMode.CHATGPT, config.authMode)
        assertEquals("", config.apiKey)
    }

    @Test
    fun migrateLegacyCodexConfigKeepsCustomApiProviderAndKey() {
        val legacyConfigToml = """
                model_provider = "omnimind"
                model = " custom-model "

                [model_providers.omnimind]
                base_url = " https://example.com/v1 "
            """.trimIndent()
        val config = migrateLegacyCodexLocalConfig(
            configToml = legacyConfigToml,
            authJson = """
                {
                  "OPENAI_API_KEY": " custom-key ",
                  "tokens": {"access_token": "stale-chatgpt-token"}
                }
            """.trimIndent()
        )

        assertEquals(CodexLocalAuthMode.API, config.authMode)
        assertEquals("https://example.com/v1", config.baseUrl)
        assertEquals("custom-model", config.apiModel)
        assertEquals("custom-key", config.apiKey)
        assertTrue(shouldRewriteMigratedCustomApiConfig(legacyConfigToml, config))
        assertFalse(
            shouldRewriteMigratedCustomApiConfig(
                buildCodexConfigToml(
                    authMode = config.authMode,
                    baseUrl = config.baseUrl,
                    model = config.apiModel
                ),
                config
            )
        )
    }

    @Test
    fun migrateLegacyCodexConfigIgnoresUnusedProviderBaseUrl() {
        val config = migrateLegacyCodexLocalConfig(
            configToml = """
                model_provider = "openai"
                model = "gpt-5.5-codex"

                [model_providers.omnimind]
                base_url = "https://unused.example.com/v1"
            """.trimIndent(),
            authJson = """
                {"tokens":{"access_token":"chatgpt-token"}}
            """.trimIndent()
        )

        assertEquals(CodexLocalAuthMode.CHATGPT, config.authMode)
        assertEquals("", config.baseUrl)
        assertEquals("gpt-5.5-codex", config.officialModel)
    }

    @Test
    fun removeLegacyOpenAiApiKeyPreservesChatGptTokens() {
        val sanitized = removeLegacyOpenAiApiKey(
            """
                {
                  "OPENAI_API_KEY": "legacy-key",
                  "tokens": {
                    "access_token": "access-token",
                    "refresh_token": "refresh-token"
                  },
                  "last_refresh": "2026-07-10T00:00:00Z"
                }
            """.trimIndent()
        )

        assertTrue(sanitized != null)
        assertFalse(sanitized!!.contains("OPENAI_API_KEY"))
        assertTrue(sanitized.contains("access-token"))
        assertTrue(sanitized.contains("refresh-token"))
        assertTrue(sanitized.contains("last_refresh"))
    }

    @Test
    fun removeLegacyOpenAiApiKeySkipsAuthWithoutApiKey() {
        assertNull(
            removeLegacyOpenAiApiKey(
                """{"tokens":{"access_token":"access-token"}}"""
            )
        )
    }
}
