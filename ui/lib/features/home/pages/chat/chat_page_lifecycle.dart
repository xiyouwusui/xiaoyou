part of 'chat_page.dart';

ConversationThreadTarget _newThreadTargetForConversationMode(
  ConversationMode mode,
) {
  return ConversationThreadTarget.newConversation(
    mode: mode,
    requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
  );
}

ConversationThreadTarget _newCodexThreadTarget() {
  return _newThreadTargetForConversationMode(ConversationMode.codex);
}

mixin _ChatPageLifecycleMixin on _ChatPageStateBase {
  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addObserver(this);
    _loadHdPadPanePreferences();

    SchedulerBinding.instance.addPostFrameCallback((_) {
      checkConversationExists();
      Future.delayed(const Duration(milliseconds: 700), () {
        if (!mounted) return;
        unawaited(_initializeHalfScreenEngineIfNeeded());
      });
    });

    _runtimeCoordinator.ensureInitialized();
    _runtimeCoordinator.addListener(_handleRuntimeCoordinatorChanged);
    HomeGreetingSettingsService.notifier.addListener(
      _handleHomeGreetingSettingsChanged,
    );
    unawaited(HomeGreetingSettingsService.load());
    AppUpdateService.statusNotifier.addListener(_handleAppUpdateStatusChanged);
    _appUpdateStatus = AppUpdateService.statusNotifier.value;
    unawaited(AppUpdateService.initialize());
    _conversationListChangedSubscription = AssistsMessageService
        .conversationListChangedStream
        .listen((_) {
          unawaited(_handleExternalConversationListChanged());
        });
    _conversationMessagesChangedSubscription = AssistsMessageService
        .conversationMessagesChangedStream
        .listen((event) {
          unawaited(_handleExternalConversationMessagesChanged(event));
        });
    _browserSessionSnapshotChangedSubscription = AssistsMessageService
        .browserSessionSnapshotChangedStream
        .listen(_handleBrowserSessionSnapshotChanged);
    _codexEventSubscription = CodexAppServerService.events.listen(
      _handleCodexAppServerEvent,
    );
    unawaited(_refreshCodexStatus());

    _inputFocusNode.addListener(_onFocusChange);
    _messageController.addListener(_handleSlashCommandInput);
    unawaited(_bootstrapConversationThread());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final mediaQuery = MediaQuery.maybeOf(context);
    if (mediaQuery != null) {
      final isHdPadLandscape = _isHdPadLandscapeForMediaQuery(mediaQuery);
      if (_wasHdPadLandscape == true && !isHdPadLandscape) {
        _drawerKey.currentState?.unfocusSearch();
      }
      _wasHdPadLandscape = isHdPadLandscape;
      if (isHdPadLandscape && _activeSurfaceMode == ChatSurfaceMode.workspace) {
        _activeSurfaceMode = ChatSurfaceMode.normal;
      }
    }
    final route = ModalRoute.of(context);
    if (route is PageRoute && route != _subscribedRoute) {
      if (_subscribedRoute != null) {
        GoRouterManager.routeObserver.unsubscribe(this);
      }
      _subscribedRoute = route;
      GoRouterManager.routeObserver.subscribe(this, route);
    }
  }

  @override
  void didUpdateWidget(covariant ChatPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_threadTargetChanged(oldWidget.threadTarget, widget.threadTarget)) {
      debugPrint(
        '[ChatPage] thread target changed: '
        '${oldWidget.threadTarget} -> ${widget.threadTarget}',
      );
      unawaited(_reloadConversationForCurrentTarget());
    }
  }

  @override
  void didChangeMetrics() {
    super.didChangeMetrics();
    _syncEmptyGreetingKeyboardLiftFromView();
  }

  void _syncEmptyGreetingKeyboardLiftFromView() {
    if (!mounted) return;
    final view = View.of(context);
    final bottomInset = view.viewInsets.bottom / view.devicePixelRatio;
    if (_emptyGreetingKeyboardLiftTracker.update(bottomInset)) {
      setState(() {});
    }
  }

  @override
  bool _threadTargetChanged(
    ConversationThreadTarget? oldTarget,
    ConversationThreadTarget? newTarget,
  ) {
    return oldTarget != newTarget;
  }

  @override
  Future<ConversationThreadTarget> _resolveConversationThreadTarget(
    ConversationThreadTarget? incomingTarget, {
    ConversationMode? preferredMode,
  }) async {
    final normalizedPreferredMode = preferredMode == ConversationMode.openclaw
        ? ConversationMode.normal
        : preferredMode;
    final normalizedIncomingTarget = _normalizeVisibleThreadTarget(
      incomingTarget,
    );
    if (normalizedIncomingTarget != null) {
      return normalizedIncomingTarget;
    }

    if (normalizedPreferredMode == null &&
        StorageService.getChatStartupBehavior() ==
            ChatStartupBehavior.newConversation) {
      return _newThreadTargetForConversationMode(ConversationMode.normal);
    }

    if (normalizedPreferredMode == null) {
      final lastVisible =
          await ConversationHistoryService.getLastVisibleThreadTarget();
      final normalizedLastVisible = _normalizeVisibleThreadTarget(lastVisible);
      if (normalizedLastVisible != null) {
        return normalizedLastVisible;
      }
    }

    final resolvedMode = normalizedPreferredMode ?? ConversationMode.normal;
    final savedTarget =
        await ConversationHistoryService.getCurrentConversationTarget(
          mode: resolvedMode,
        );
    final normalizedSavedTarget = _normalizeVisibleThreadTarget(savedTarget);
    if (normalizedSavedTarget != null) {
      return normalizedSavedTarget;
    }

    final latestTarget = await ConversationService.getLatestConversationTarget(
      mode: resolvedMode,
    );
    final normalizedLatestTarget = _normalizeVisibleThreadTarget(latestTarget);
    if (normalizedLatestTarget != null) {
      return normalizedLatestTarget;
    }

    return ConversationThreadTarget.newConversation(mode: resolvedMode);
  }

  ConversationThreadTarget? _normalizeVisibleThreadTarget(
    ConversationThreadTarget? target,
  ) {
    if (target == null) {
      return null;
    }
    if (target.mode == ConversationMode.openclaw) {
      return null;
    }
    return target;
  }

  @override
  Future<void> _bootstrapConversationThread() async {
    final requestId = _beginConversationTargetRequest();
    await _loadOpenClawConfig();
    if (!_isConversationTargetRequestCurrent(requestId)) return;
    await _loadTerminalEnvironmentVariables();
    if (!_isConversationTargetRequestCurrent(requestId)) return;
    final target = await _resolveConversationThreadTarget(widget.threadTarget);
    if (!_isConversationTargetRequestCurrent(requestId)) return;
    await _applyConversationThreadTarget(
      target,
      syncPage: false,
      requestId: requestId,
    );
    if (!_isConversationTargetRequestCurrent(requestId)) return;
    unawaited(_loadNormalChatModelContext());
    unawaited(_refreshLiveBrowserSessionSnapshot(syncRuntime: true));
  }

  @override
  Future<void> _reloadConversationForCurrentTarget() async {
    final requestId = _beginConversationTargetRequest();
    final target = await _resolveConversationThreadTarget(widget.threadTarget);
    if (!_isConversationTargetRequestCurrent(requestId)) return;
    await _applyConversationThreadTarget(target, requestId: requestId);
    if (!_isConversationTargetRequestCurrent(requestId)) return;
  }

  @override
  Future<void> _applyConversationThreadTarget(
    ConversationThreadTarget target, {
    bool syncPage = true,
    int? requestId,
  }) async {
    final activeRequestId = requestId ?? _beginConversationTargetRequest();
    bool isStaleRequest() =>
        !_isConversationTargetRequestCurrent(activeRequestId);
    invalidateConversationLifecycle();
    final lifecycleToken = captureConversationLifecycleToken();
    final effectiveTarget = await _overrideTargetWithSharedDraftIfNeeded(
      target,
    );
    if (isStaleRequest()) return;
    final targetMode = _pageModeForConversationMode(effectiveTarget.mode);
    _storeDraftForActiveConversationMode();
    if (effectiveTarget.isNewConversation) {
      _draftMessageByMode[targetMode] = '';
      _pendingAttachmentsByMode[targetMode]?.clear();
    }
    if (isStaleRequest()) return;
    setState(() {
      _resolvedThreadTarget = effectiveTarget;
      _activeConversationMode = targetMode;
      _activeSurfaceMode = _surfaceForConversationMode(effectiveTarget.mode);
      _showSlashCommandPanel = false;
      _showModelMentionPanel = false;
      _activeModelMentionToken = null;
      _openClawPanelExpanded = false;
      _isBrowserOverlayVisible = false;
      _isSurfacePageScrolling = false;
    });
    _resetLocalConversationState(targetMode);
    _restoreLocalCodexThreadIdFromTarget(effectiveTarget);
    _applyDraftForConversationMode(targetMode);
    if (effectiveTarget.isRemoteCodexSessionTarget) {
      await _prepareRemoteCodexSessionTarget(effectiveTarget);
    } else {
      await initializeConversation(lifecycleToken: lifecycleToken);
    }
    if (isStaleRequest()) return;
    if (_activeConversationMode == ChatPageMode.codex) {
      await _refreshCodexCommandPreferences();
      if (isStaleRequest()) return;
    }
    await _applyStagedSharedDraftIfNeeded(effectiveTarget);
    if (isStaleRequest()) return;
    await _persistVisibleThreadTargetIfNeeded();
    unawaited(_syncVisibleChatConversation());
    if (isStaleRequest()) return;
    if (syncPage) {
      _jumpToCurrentModePage(animate: false);
    }
  }

  void _restoreLocalCodexThreadIdFromTarget(ConversationThreadTarget target) {
    if (target.mode != ConversationMode.codex ||
        target.isRemoteCodexSessionTarget) {
      return;
    }
    final threadId = target.codexThreadId?.trim();
    if (threadId == null || threadId.isEmpty) {
      return;
    }
    _activeCodexThreadId = threadId;
  }

  @override
  Future<void> _ensureConversationModeReady(ChatPageMode mode) async {
    if (_hasPreparedConversationState(mode)) {
      return;
    }
    final target = await _resolveConversationThreadTarget(
      null,
      preferredMode: _conversationModeForPageMode(mode),
    );
    if (!mounted) return;
    await _prepareConversationModeState(mode, target);
  }

  bool _hasPreparedConversationState(ChatPageMode mode) {
    final runtime = _runtimeForMode(mode);
    final draft = _draftMessageByMode[mode] ?? '';
    return _currentConversationIdByMode[mode] != null ||
        _currentConversationByMode[mode] != null ||
        _messagesByMode[mode]!.isNotEmpty ||
        (runtime?.messages.isNotEmpty ?? false) ||
        draft.isNotEmpty ||
        _pendingAttachmentsByMode[mode]!.isNotEmpty;
  }

  @override
  Future<void> _prepareConversationModeState(
    ChatPageMode mode,
    ConversationThreadTarget target,
  ) async {
    final lifecycleToken = captureConversationLifecycleToken();
    if (target.isNewConversation) {
      return;
    }

    final conversationId = target.conversationId;
    if (conversationId == null) {
      return;
    }

    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _modeKey(mode),
    );
    final inMemoryConversation = runtime?.conversation;
    final inMemoryMessages = runtime == null || runtime.messages.isEmpty
        ? null
        : List<ChatMessageModel>.from(runtime.messages);
    final conversations = await ConversationService.getAllConversations(
      includeArchived: true,
    );
    if (!mounted || !isConversationLifecycleTokenCurrent(lifecycleToken)) {
      return;
    }

    ConversationModel? conversation;
    try {
      conversation = conversations.firstWhere(
        (item) =>
            item.id == conversationId &&
            item.mode == _conversationModeForPageMode(mode),
      );
    } catch (_) {
      conversation = null;
    }

    final resolvedConversation = inMemoryConversation ?? conversation;
    final resolvedMessages =
        inMemoryMessages ??
        await ConversationHistoryService.getConversationMessages(
          conversationId,
          mode: _conversationModeForPageMode(mode),
          expectedMessageCount: resolvedConversation?.messageCount,
        );
    if (!mounted || !isConversationLifecycleTokenCurrent(lifecycleToken)) {
      return;
    }

    _currentConversationIdByMode[mode] = conversationId;
    _currentConversationByMode[mode] = resolvedConversation;
    _messagesByMode[mode]!
      ..clear()
      ..addAll(resolvedMessages);

    if (runtime == null) {
      _runtimeCoordinator.ensureRuntime(
        conversationId: conversationId,
        mode: _modeKey(mode),
        initialMessages: resolvedMessages,
        conversation: resolvedConversation,
        initialChatIslandDisplayLayer: _chatIslandDisplayLayerForMode(mode),
      );
    } else if (resolvedConversation != null) {
      runtime.conversation = resolvedConversation;
    }
    _syncRuntimeSnapshotForMode(
      mode,
      conversation: resolvedConversation,
      messages: resolvedMessages,
    );
  }

  @override
  Future<void> _persistVisibleThreadTargetIfNeeded() async {
    final visibleTarget = _visibleThreadTarget;
    if (visibleTarget == null) {
      return;
    }
    _resolvedThreadTarget = visibleTarget;
    if (visibleTarget.isRemoteCodexSessionTarget ||
        (_activeConversationMode == ChatPageMode.codex &&
            _isRemoteCodexRuntimeActiveForMode(ChatPageMode.codex))) {
      return;
    }
    await ConversationHistoryService.saveLastVisibleThreadTarget(visibleTarget);
    await ConversationHistoryService.saveCurrentConversationTarget(
      visibleTarget,
      mode: visibleTarget.mode,
    );
    await ConversationService.setCurrentConversationTarget(visibleTarget);
  }

  @override
  Future<void> _initializeHalfScreenEngineIfNeeded() async {
    if (_hasInitializedHalfScreen) return;
    _hasInitializedHalfScreen = true;
    await AppStateService.initHalfScreenEngine();
  }

  @override
  void dispose() {
    unawaited(_clearVisibleChatConversation());
    unawaited(_conversationModelSelectorHandle?.dismiss());
    _conversationModelSelectorHandle = null;
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_runtimeCoordinator.flushAllPendingPersistence());
    _conversationListChangedSubscription?.cancel();
    _conversationMessagesChangedSubscription?.cancel();
    _browserSessionSnapshotChangedSubscription?.cancel();
    if (_subscribedRoute != null) {
      GoRouterManager.routeObserver.unsubscribe(this);
      _subscribedRoute = null;
    }
    _runtimeCoordinator.removeListener(_handleRuntimeCoordinatorChanged);
    HomeGreetingSettingsService.notifier.removeListener(
      _handleHomeGreetingSettingsChanged,
    );
    AppUpdateService.statusNotifier.removeListener(
      _handleAppUpdateStatusChanged,
    );
    _messageController.removeListener(_handleSlashCommandInput);
    _messageController.dispose();
    _normalMessageScrollController.dispose();
    _openClawMessageScrollController.dispose();
    _codexMessageScrollController.dispose();
    _modePageController.dispose();
    _inputFocusNode.dispose();
    _openClawBaseUrlController.dispose();
    _openClawTokenController.dispose();
    _openClawUserIdController.dispose();
    _stopRemoteCodexSessionSync();
    _codexEventSubscription?.cancel();
    super.dispose();
  }

  @override
  void didPopNext() {
    unawaited(_handleDidPopNext());
    unawaited(_syncVisibleChatConversation());
  }

  @override
  void didPush() {
    unawaited(_syncVisibleChatConversation());
  }

  @override
  void didPop() {
    unawaited(_clearVisibleChatConversation());
  }

  @override
  void didPushNext() {
    _dismissChatInputFocus();
    unawaited(_clearVisibleChatConversation());
  }

  Future<void> _handleDidPopNext() async {
    final lifecycleToken = captureConversationLifecycleToken();
    await checkConversationExists(lifecycleToken: lifecycleToken);
    if (!mounted || !isConversationLifecycleTokenCurrent(lifecycleToken)) {
      return;
    }
    await _loadNormalChatModelContext();
    if (!mounted || !isConversationLifecycleTokenCurrent(lifecycleToken)) {
      return;
    }
    await _refreshLiveBrowserSessionSnapshot(syncRuntime: true);
  }

  Future<void> _handleExternalConversationListChanged() async {
    final lifecycleToken = captureConversationLifecycleToken();
    final conversationId = _currentConversationId;
    await checkConversationExists(lifecycleToken: lifecycleToken);
    if (!mounted ||
        !isConversationLifecycleTokenCurrent(lifecycleToken) ||
        conversationId == null ||
        conversationId != _currentConversationId) {
      return;
    }
    final runtime = _runtimeForMode(_activeMode);
    await loadConversation(
      conversationId,
      preferInMemory: runtime?.hasInFlightTask == true,
      lifecycleToken: lifecycleToken,
    );
    if (!mounted ||
        !isConversationLifecycleTokenCurrent(lifecycleToken) ||
        conversationId != _currentConversationId) {
      return;
    }
    setState(() {});
    unawaited(_syncVisibleChatConversation());
  }

  Future<void> _handleExternalConversationMessagesChanged(
    Map<String, dynamic> event,
  ) async {
    final lifecycleToken = captureConversationLifecycleToken();
    final conversationId = _currentConversationId;
    if (conversationId == null) {
      return;
    }
    final changedConversationId = (event['conversationId'] as num?)?.toInt();
    final changedMode = ConversationMode.fromStorageValue(
      event['mode'] as String?,
    );
    if (changedConversationId != conversationId ||
        changedMode != activeConversationModeValue) {
      return;
    }
    final runtime = _runtimeForMode(_activeMode);
    // IM 等外部入口写入用户消息时，原生侧用 reason=external_user_message 通知前端：
    // 这条消息只在 DB 里、还没进入 runtime.messages，必须强制从 DB 重载，
    // 否则 agent 流事件先到时 hasInFlightTask=true 会让 in-memory 分支吞掉它。
    final isExternalUserMessage =
        event['reason']?.toString() == 'external_user_message';
    await loadConversation(
      conversationId,
      preferInMemory:
          !isExternalUserMessage && runtime?.hasInFlightTask == true,
      lifecycleToken: lifecycleToken,
    );
    if (!mounted ||
        !isConversationLifecycleTokenCurrent(lifecycleToken) ||
        conversationId != _currentConversationId) {
      return;
    }
    setState(() {});
    unawaited(_syncVisibleChatConversation());
  }

  @override
  void _onFocusChange() {
    if (!mounted) return;
    if (_inputFocusNode.hasFocus) {
      _armComposerLiftIntent();
    }
    setState(() {});
  }

  void _handleHomeGreetingSettingsChanged() {
    if (!mounted) return;
    setState(() {});
  }

  @override
  void _handleAppUpdateStatusChanged() {
    if (!mounted) return;
    setState(() {
      _appUpdateStatus = AppUpdateService.statusNotifier.value;
    });
  }

  @override
  double _popupMenuBottomOffset() {
    final renderObject = _inputAreaKey.currentContext?.findRenderObject();
    if (renderObject is! RenderBox || !renderObject.hasSize) {
      return 72;
    }
    final offset = renderObject.size.height - 8;
    return offset < 72 ? 72 : offset;
  }

  @override
  Future<void> _handleAppUpdateBannerTap() async {
    final status = _appUpdateStatus;
    if (status == null || !status.hasUpdate || !mounted) return;
    await showAppUpdateDialog(context, status);
  }

  @override
  int _pageIndexForSurface(ChatSurfaceMode mode) => switch (mode) {
    ChatSurfaceMode.normal => 0,
    ChatSurfaceMode.workspace => 1,
    ChatSurfaceMode.openclaw => 0,
  };

  @override
  ChatSurfaceMode _surfaceForPageIndex(int pageIndex) => switch (pageIndex) {
    1 => ChatSurfaceMode.workspace,
    _ => ChatSurfaceMode.normal,
  };

  @override
  ScrollController _scrollControllerForMode(ChatPageMode mode) {
    return mode == ChatPageMode.openclaw
        ? _openClawMessageScrollController
        : mode == ChatPageMode.codex
        ? _codexMessageScrollController
        : _normalMessageScrollController;
  }

  @override
  void _jumpToCurrentModePage({bool animate = true}) {
    final targetPage = _pageIndexForSurface(_activeSurfaceMode);
    if (!_modePageController.hasClients) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _jumpToCurrentModePage(animate: animate);
      });
      return;
    }
    final currentPage = _modePageController.page?.round();
    if (currentPage == targetPage) return;
    if (animate) {
      _modePageController.animateToPage(
        targetPage,
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
      );
    } else {
      _modePageController.jumpToPage(targetPage);
    }
  }

  @override
  Future<void> _switchChatMode(
    ChatSurfaceMode targetMode, {
    bool syncPage = true,
  }) async {
    final resolvedTargetMode = targetMode == ChatSurfaceMode.openclaw
        ? ChatSurfaceMode.normal
        : targetMode;
    final requestId = ++_surfaceSwitchRequestId;
    bool isStaleRequest() => !mounted || requestId != _surfaceSwitchRequestId;
    if (!mounted) return;
    if (_activeSurfaceMode == resolvedTargetMode) {
      if (syncPage) _jumpToCurrentModePage();
      return;
    }

    _storeDraftForActiveConversationMode();
    await _persistVisibleThreadTargetIfNeeded();
    if (isStaleRequest()) return;

    if (resolvedTargetMode == ChatSurfaceMode.workspace) {
      _inputFocusNode.unfocus();
      final workspacePathsFuture =
          _workspacePathsLoadFuture ??
          OmnibotResourceService.ensureWorkspacePathsLoaded();
      setState(() {
        _activeSurfaceMode = ChatSurfaceMode.workspace;
        _workspacePathsLoadFuture = workspacePathsFuture;
        _messageController.clear();
        _setChatIslandDisplayLayerForMode(
          ChatPageMode.normal,
          ChatIslandDisplayLayer.mode,
        );
        _isBrowserOverlayVisible = false;
      });
      _hideSlashCommandPanel();
      if (syncPage) _jumpToCurrentModePage();
      return;
    }

    final targetConversationMode = _activeConversationMode == ChatPageMode.codex
        ? ChatPageMode.codex
        : ChatPageMode.normal;
    await _ensureConversationModeReady(targetConversationMode);
    if (isStaleRequest()) return;
    setState(() {
      _activeSurfaceMode = ChatSurfaceMode.normal;
      _activeConversationMode = targetConversationMode;
    });
    _applyDraftForConversationMode(targetConversationMode);
    await _persistVisibleThreadTargetIfNeeded();
    unawaited(_syncVisibleChatConversation());
    if (isStaleRequest()) return;
    _hideSlashCommandPanel();
    if (targetConversationMode == ChatPageMode.normal) {
      unawaited(_loadNormalChatModelContext());
    }
    if (syncPage) _jumpToCurrentModePage();
  }

  @override
  void _handleModePageChanged(int pageIndex) {
    final targetMode = _surfaceForPageIndex(pageIndex);
    unawaited(_switchChatMode(targetMode, syncPage: false));
  }

  @override
  void _storeDraftForActiveConversationMode() {
    _draftMessageByMode[_activeConversationMode] = _messageController.text;
  }

  @override
  void _applyDraftForConversationMode(ChatPageMode mode) {
    final draft = _draftMessageByMode[mode] ?? '';
    _messageController.value = TextEditingValue(
      text: draft,
      selection: TextSelection.collapsed(offset: draft.length),
    );
  }

  Future<ConversationThreadTarget> _overrideTargetWithSharedDraftIfNeeded(
    ConversationThreadTarget target,
  ) async {
    final staged = _activeStagedSharedOpenDraft();
    if (staged != null && staged.hasContent) {
      return ConversationThreadTarget.newConversation(
        mode: ConversationMode.normal,
        fromNativeRoute: true,
        requestKey: staged.requestKey,
      );
    }

    final payload = await SharedOpenDraftService.getPendingDraft();
    if (payload == null || !payload.hasContent) {
      return target;
    }
    _stagedSharedOpenDraft = payload;
    _stagedSharedOpenDraftExpiresAt =
        DateTime.now().millisecondsSinceEpoch + 5000;
    return ConversationThreadTarget.newConversation(
      mode: ConversationMode.normal,
      fromNativeRoute: true,
      requestKey: payload.requestKey,
    );
  }

  Future<void> _applyStagedSharedDraftIfNeeded(
    ConversationThreadTarget target,
  ) async {
    final payload = _activeStagedSharedOpenDraft();
    if (payload == null ||
        !payload.hasContent ||
        !target.isNewConversation ||
        target.mode != ConversationMode.normal) {
      return;
    }

    final attachments = payload.attachments
        .map(
          (item) => ChatInputAttachment(
            id: item.id.isNotEmpty ? item.id : item.path,
            name: item.name.isNotEmpty ? item.name : item.path.split('/').last,
            path: item.path,
            size: item.size,
            mimeType: item.mimeType,
            isImage: item.isImage,
            promptPath: item.promptPath,
            sendToModel: item.sendToModel,
          ),
        )
        .toList();

    if (!mounted) {
      return;
    }
    setState(() {
      _draftMessageByMode[ChatPageMode.normal] = payload.text ?? '';
      _pendingAttachmentsByMode[ChatPageMode.normal]!
        ..clear()
        ..addAll(attachments);
    });
    if (_activeConversationMode == ChatPageMode.normal) {
      _applyDraftForConversationMode(ChatPageMode.normal);
    }
    await SharedOpenDraftService.clearPendingDraft();
  }

  SharedOpenDraftPayload? _activeStagedSharedOpenDraft() {
    final payload = _stagedSharedOpenDraft;
    final expiresAt = _stagedSharedOpenDraftExpiresAt;
    if (payload == null) {
      return null;
    }
    if (expiresAt != null &&
        DateTime.now().millisecondsSinceEpoch > expiresAt) {
      _stagedSharedOpenDraft = null;
      _stagedSharedOpenDraftExpiresAt = null;
      return null;
    }
    return payload;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    if ((state == AppLifecycleState.resumed ||
            state == AppLifecycleState.inactive) &&
        _currentConversationId != null) {
      Future.delayed(const Duration(milliseconds: 100), () async {
        if (!mounted) return;
        await checkConversationExists();
      });
    }
    if (state == AppLifecycleState.resumed) {
      unawaited(_syncVisibleChatConversation());
      unawaited(AppUpdateService.refreshIfNeeded());
      unawaited(_loadNormalChatModelContext());
      unawaited(_refreshLiveBrowserSessionSnapshot(syncRuntime: true));
    }
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      unawaited(_clearVisibleChatConversation());
      unawaited(_runtimeCoordinator.flushAllPendingPersistence());
      unawaited(_persistVisibleThreadTargetIfNeeded());
    }
  }

  Future<void> _syncVisibleChatConversation() async {
    if (!mounted) return;
    final route = ModalRoute.of(context);
    if (route is PageRoute && !route.isCurrent) {
      await _clearVisibleChatConversation();
      return;
    }
    final target = _visibleThreadTarget;
    if (target == null || target.isNewConversation) {
      await AssistsMessageService.setVisibleChatConversation(
        conversationMode: activeConversationModeValue.storageValue,
      );
      return;
    }
    await AssistsMessageService.setVisibleChatConversation(
      conversationId: target.conversationId,
      conversationMode: target.mode.storageValue,
    );
  }

  Future<void> _clearVisibleChatConversation() {
    return AssistsMessageService.setVisibleChatConversation(visible: false);
  }
}
