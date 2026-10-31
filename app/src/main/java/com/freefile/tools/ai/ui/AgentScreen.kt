package com.freefile.tools.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freefile.R
import com.freefile.tools.ai.model.AgentPhase
import kotlinx.coroutines.launch

private enum class AgentSubPage { CHAT, SETTINGS, LOGS }

/** 输入框下方的附件条目：label 用于展示，payload 实际发给模型。 */
private data class AgentAttachment(
    val label: String,
    val payload: String,
    val withContent: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    initialPaths: List<String> = emptyList(),
    initialPrompt: String? = null,
    vm: AgentViewModel = viewModel()
) {
    var sub by remember { mutableStateOf(AgentSubPage.CHAT) }
    var input by remember { mutableStateOf(initialPrompt.orEmpty()) }
    // 从文件管理器带入的路径也放进附件列表，用户可见、可移除，而不是静默发送
    val attachments = remember {
        mutableStateListOf<AgentAttachment>().apply {
            initialPaths.filter { it.isNotBlank() }.distinct().forEach {
                add(AgentAttachment(it, it, false))
            }
        }
    }
    var showConvDialog by remember { mutableStateOf(false) }
    var showAttachDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.bootstrap(initialPaths, null)
        launch {
            vm.events.collect { snackbar.showSnackbar(it) }
        }
    }

    val messages by vm.messages.collectAsState()
    val phase by vm.phase.collectAsState()
    val mode by vm.mode.collectAsState()
    val progress by vm.progress.collectAsState()
    val liveDelta by vm.liveDelta.collectAsState()
    val liveThinking by vm.liveThinking.collectAsState()
    val preview by vm.pendingPreview.collectAsState()
    val settings by vm.settings.collectAsState()
    val conversations by vm.conversations.collectAsState()

    // 智能跟随：AI 输出时只有用户停在底部才自动滚动；用户上滑后暂停跟随，
    // 直到手动滚回底部（或点“回到底部”）才恢复，不再强制归位。
    var autoFollow by remember { mutableStateOf(true) }
    val scrollScope = rememberCoroutineScope()
    val isDragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragging) {
        if (isDragging) {
            autoFollow = false
        } else if (!listState.canScrollForward) {
            autoFollow = true
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && autoFollow) listState.animateScrollToItem(messages.size - 1)
    }
    LaunchedEffect(liveDelta, liveThinking) {
        if (phase == AgentPhase.STREAMING && autoFollow) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last)
        }
    }
    val showJumpToBottom = listState.canScrollForward
    // 当前轮对话的起始位置：最后一条用户消息在列表中的索引（与 LazyColumn item 一一对应）。
    val currentRoundStart = messages.indexOfLast { it.role == "user" }.coerceAtLeast(0)

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (sub == AgentSubPage.CHAT) {
                        // 标题位改为附件入口，点开即附件管理对话框；会话切换仍由右上角历史按钮提供
                        TextButton(
                            onClick = { showAttachDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (attachments.isEmpty()) stringResource(R.string.ai_attach_entry)
                                else stringResource(R.string.ai_attach_entry_count, attachments.size),
                                maxLines = 1
                            )
                        }
                    } else {
                        Text(
                            when (sub) {
                                AgentSubPage.SETTINGS -> stringResource(R.string.ai_settings_title)
                                AgentSubPage.LOGS -> stringResource(R.string.ai_log_title)
                                AgentSubPage.CHAT -> {
                                    val cur = conversations.firstOrNull { it.id == vm.currentConvId }
                                    cur?.title ?: stringResource(R.string.ai_onboard_title)
                                }
                            },
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (sub != AgentSubPage.CHAT) sub = AgentSubPage.CHAT
                        else (context as? android.app.Activity)?.finish()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ai_back))
                    }
                },
                actions = {
                    if (sub == AgentSubPage.CHAT) {
                        IconButton(onClick = { showConvDialog = true }) {
                            Icon(Icons.Rounded.History, stringResource(R.string.ai_conv_list_title))
                        }
                        IconButton(onClick = { vm.undo() }) {
                            Icon(Icons.Rounded.Undo, stringResource(R.string.ai_undo_last))
                        }
                        IconButton(onClick = { vm.newChat() }) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.ai_new_chat))
                        }
                        IconButton(onClick = { sub = AgentSubPage.LOGS }) {
                            Icon(Icons.Rounded.ReceiptLong, stringResource(R.string.ai_op_log))
                        }
                        IconButton(onClick = { sub = AgentSubPage.SETTINGS }) {
                            Icon(Icons.Rounded.Settings, stringResource(R.string.ai_settings_title))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (sub) {
                AgentSubPage.SETTINGS -> AgentSettingsPane(
                    vm = vm,
                    onBack = { sub = AgentSubPage.CHAT }
                )

                AgentSubPage.LOGS -> OpLogPane(vm = vm)

                AgentSubPage.CHAT -> Column(Modifier.fillMaxSize()) {
                    ModeTabs(current = mode, onSelect = vm::setMode)

                    if (phase == AgentPhase.NEEDS_SETUP) {
                        SetupNeededCard(onSetup = { sub = AgentSubPage.SETTINGS })
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (messages.isEmpty()) {
                                item {
                                    Text(
                                        stringResource(R.string.ai_empty_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(20.dp)
                                    )
                                }
                            }
                            items(messages, key = { it.id }) { m ->
                                ChatMessageItem(m, settings.showToolDetails, settings.showThinking)
                            }
                            if (phase == AgentPhase.STREAMING &&
                                (liveDelta.isNotEmpty() || liveThinking.isNotEmpty())
                            ) {
                                item {
                                    androidx.compose.foundation.text.selection.SelectionContainer {
                                        Column(Modifier.fillMaxWidth()) {
                                            if (liveThinking.isNotEmpty() && settings.showThinking) {
                                                ThinkingBlock(liveThinking)
                                            }
                                            if (liveDelta.isNotEmpty()) {
                                                ChatMessageItem(
                                                    com.freefile.tools.ai.data.AgentMessageEntity(
                                                        id = "live", convId = "", role = "assistant",
                                                        content = liveDelta, sortKey = -1, createdAt = 0
                                                    ),
                                                    showDetails = settings.showToolDetails,
                                                    showThinking = false
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(
                            state = listState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(vertical = 6.dp, horizontal = 2.dp)
                        )
                        if (showJumpToBottom) {
                            SmallFloatingActionButton(
                                onClick = {
                                    autoFollow = true
                                    scrollScope.launch {
                                        // 定位到当前轮对话的起点，而不是整个列表的最底部
                                        listState.animateScrollToItem(currentRoundStart)
                                    }
                                },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.KeyboardArrowDown,
                                    stringResource(R.string.ai_jump_to_bottom)
                                )
                            }
                        }
                    }

                    ProgressStrip(progress)

                    preview?.let { p ->
                        ConfirmPlanCard(
                            preview = p,
                            onApprove = { selected, dest, conflict, overwrite ->
                                vm.approve(p, selected, dest, conflict, overwrite)
                            },
                            onCancel = { vm.cancel(p) }
                        )
                    }

                    InputBar(
                        value = input,
                        busy = phase == AgentPhase.STREAMING ||
                            phase == AgentPhase.EXECUTING_TOOL ||
                            phase == AgentPhase.AWAITING_CONFIRMATION,
                        awaiting = phase == AgentPhase.AWAITING_CONFIRMATION,
                        onValueChange = { input = it },
                        onSend = {
                            autoFollow = true
                            vm.send(input, attachments.map { it.payload })
                            input = ""
                            attachments.clear()
                        },
                        onStop = { vm.stop() }
                    )
                }
            }

            if (!settings.onboardingAccepted && sub == AgentSubPage.CHAT) {
                OnboardingDialog(
                    onAccept = { vm.acceptOnboarding() },
                    onGoSetup = {
                        vm.acceptOnboarding()
                        sub = AgentSubPage.SETTINGS
                    }
                )
            }

            if (showConvDialog) {
                ConversationListDialog(
                    conversations = conversations,
                    currentId = vm.currentConvId,
                    onSelect = { vm.switchConversation(it) },
                    onRename = { id, title -> vm.renameConversation(id, title) },
                    onDelete = { vm.deleteConversation(it) },
                    onDismiss = { showConvDialog = false }
                )
            }

            if (showAttachDialog) {
                AttachmentDialog(
                    attachments = attachments,
                    onRemove = { attachments.removeAt(it) },
                    onAdd = { raw ->
                        vm.prepareAttachment(raw) { prepared, note ->
                            if (prepared == null) {
                                vm.notify(note)
                            } else {
                                val marker = "\n[附件内容]\n"
                                val at = prepared.indexOf(marker)
                                val label = if (at >= 0) prepared.substring(0, at) else prepared
                                val withContent = at >= 0
                                if (attachments.none { it.label == label }) {
                                    attachments.add(AgentAttachment(label, prepared, withContent))
                                }
                            }
                        }
                    },
                    onDismiss = { showAttachDialog = false }
                )
            }
        }
    }
}

@Composable
private fun SetupNeededCard(onSetup: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.ai_setup_needed_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.size(6.dp))
        Text(
            stringResource(R.string.ai_setup_needed_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.size(10.dp))
        Button(onClick = onSetup) {
            Text(stringResource(R.string.ai_setup_needed_button))
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    busy: Boolean,
    awaiting: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        if (awaiting) {
            Text(
                stringResource(R.string.ai_pending_confirmation_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = !awaiting,
                placeholder = { Text(stringResource(R.string.ai_input_hint)) },
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = if (busy) onStop else onSend,
                enabled = busy || (value.isNotBlank() && !awaiting)
            ) {
                Icon(
                    if (busy) Icons.Rounded.Stop else Icons.Rounded.Send,
                    if (busy) stringResource(R.string.ai_stop) else stringResource(R.string.ai_send)
                )
            }
        }
    }
}

/**
 * 附件管理对话框：上方列出已有附件（可逐条移除），下方输入框添加新的路径或链接。
 */
@Composable
private fun AttachmentDialog(
    attachments: List<AgentAttachment>,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ai_attach_close))
            }
        },
        title = {
            Text(
                if (attachments.isEmpty()) stringResource(R.string.ai_attach_title)
                else stringResource(R.string.ai_attach_entry_count, attachments.size)
            )
        },
        text = {
            Column {
                if (attachments.isEmpty()) {
                    Text(
                        stringResource(R.string.ai_attach_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        attachments.forEachIndexed { idx, item ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (item.withContent) Icons.Rounded.Description else Icons.Rounded.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (item.withContent) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        stringResource(R.string.ai_attach_with_content),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                IconButton(
                                    onClick = { onRemove(idx) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        stringResource(R.string.ai_attach_remove),
                                        Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    stringResource(R.string.ai_attach_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ai_attach_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (text.isNotBlank()) {
                                    onAdd(text.trim())
                                    text = ""
                                }
                            },
                            enabled = text.isNotBlank()
                        ) {
                            Icon(Icons.Rounded.Add, stringResource(R.string.ai_attach_add))
                        }
                    }
                )
            }
        }
    )
}
