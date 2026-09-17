@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.nuomisp.englishbook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuomisp.englishbook.StudyViewModel
import com.nuomisp.englishbook.LearningUi
import kotlinx.coroutines.launch

private data class Destination(val id: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("home", "凛 · 学习搭档", Icons.Outlined.AutoAwesome),
    Destination("words", "单词练习", Icons.Outlined.Style),
    Destination("reading", "阅读小屋", Icons.AutoMirrored.Outlined.MenuBook),
    Destination("listening", "听力与听写", Icons.Outlined.Headphones),
    Destination("progress", "学习记录", Icons.Outlined.Insights),
    Destination("memory", "它记住了什么", Icons.Outlined.Psychology),
    Destination("settings", "设置", Icons.Outlined.Tune))

@Composable fun EnglishApp(model: StudyViewModel) {
    val state by model.ui.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    var draft by rememberSaveable { mutableStateOf("") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun navigate(value: String) { page = value; model.pageChanged(value) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.message.value = null } }
    BackHandler(page != "home" || drawer.isOpen) {
        if (drawer.isOpen) scope.launch { drawer.close() } else navigate("home")
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Spacer(Modifier.height(30.dp))
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CompanionAvatar(48.dp); Spacer(Modifier.width(14.dp))
                Column { Text("葱伴英语", style=MaterialTheme.typography.titleLarge); Text("一点一点，走到四级。", style=MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.height(12.dp))
            destinations.forEach { item ->
                NavigationDrawerItem(label={Text(item.label)}, selected=page==item.id,
                    onClick={ navigate(item.id); scope.launch { drawer.close() } },
                    icon={Icon(item.icon,null)}, modifier=Modifier.padding(horizontal=12.dp,vertical=3.dp).testTag("nav_${item.id}"))
            }
            Spacer(Modifier.weight(1f))
            Text("初中基础 → CET-4\n每天 120 分钟 · 词汇优先", Modifier.padding(26.dp),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }) {
        Scaffold(
            topBar = { TopAppBar(title = {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(if(page=="home") "凛" else destinations.first { it.id==page }.label, style=MaterialTheme.typography.titleLarge)
                    if(page=="home") { Spacer(Modifier.width(8.dp)); Text("你的英语搭档",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }, navigationIcon={IconButton(onClick={scope.launch{drawer.open()}},modifier=Modifier.testTag("menu")){Icon(Icons.Outlined.Menu,"打开导航")}},
                actions={if(page=="home") IconButton(onClick={navigate("progress")}){Icon(Icons.Outlined.Insights,"学习记录")}
                else IconButton(onClick={navigate("home")}){Icon(Icons.Outlined.AutoAwesome,"找凛聊聊")}},
                colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background)) },
            snackbarHost={SnackbarHost(snackbar)}, containerColor=MaterialTheme.colorScheme.background
        ) { inset ->
            Box(Modifier.fillMaxSize().padding(inset).pointerInput(Unit) {
                awaitPointerEventScope { while(true) { awaitPointerEvent(); model.interact() } }
            }) {
                if(!state.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else when(page) {
                    "home" -> ChatHome(model,state,draft,{draft=it},{navigate(it)})
                    "words" -> WordsScreen(model,state) { word ->
                        draft="请用适合初中基础的方式讲解 ${word.word}，说明用法并出一道小题。例句：${word.example}"
                        navigate("home")
                    }
                    "reading" -> ReadingScreen(model,state) { text -> draft=text; navigate("home") }
                    "listening" -> ListeningScreen(model,state) { text -> draft=text; navigate("home") }
                    "progress" -> ProgressScreen(state) { navigate(it) }
                    "memory" -> MemoryScreen(model,state)
                    "settings" -> SettingsScreen(model)
                }
            }
        }
    }
}

@Composable private fun ChatHome(model: StudyViewModel, state: LearningUi, draft: String, onDraft:(String)->Unit, navigate:(String)->Unit) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.chatError.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var clearDialog by remember { mutableStateOf(false) }
    LaunchedEffect(state.messages.size,busy) { if(state.messages.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1)-1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(state=listState,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
            if(state.messages.isEmpty()) {
                item { Spacer(Modifier.height(18.dp)); CompanionAvatar(62.dp); Spacer(Modifier.height(22.dp))
                    Text("今天，也要进步一点。",style=TextStyle(fontSize=31.sp,lineHeight=43.sp,fontWeight=FontWeight.Medium,
                        brush=Brush.linearGradient(listOf(Color(0xFF668BCE),Color(0xFF9878C2),Color(0xFFB17B9C)))))
                    Spacer(Modifier.height(10.dp)); Text("我才不是特意等你的。\n单词书已经打开了，先学几个？",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { TodayCard(state,navigate) }
                item {
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        ActionTile("单词练习","把基础一点点补上",Icons.Outlined.Style,Modifier.weight(1f)){navigate("words")}
                        ActionTile("听一小段","从简单的句子开始",Icons.Outlined.Headphones,Modifier.weight(1f)){navigate("listening")}
                    }
                    Spacer(Modifier.height(12.dp))
                    ActionTile("阅读小屋","读懂一段，比硬背一页更有用。",Icons.AutoMirrored.Outlined.MenuBook,Modifier.fillMaxWidth()){navigate("reading")}
                }
                if(settings.apiKey.isBlank()) item {
                    Surface(onClick={navigate("settings")},shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){ Icon(Icons.Outlined.Key,null,Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("填好 AI 接口，就可以和凛聊天。\n离线单词和练习现在就能用。",style=MaterialTheme.typography.bodySmall) }
                    }
                }
            } else {
                item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Text("陪你从基础走向四级",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick={clearDialog=true}){Icon(Icons.Outlined.DeleteOutline,"清空对话")}
                } }
                items(state.messages,key={it.id}) { message ->
                    if(message.role=="user") Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        Surface(shape=RoundedCornerShape(22.dp,22.dp,6.dp,22.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.widthIn(max=310.dp)) {
                            SelectionContainer { Text(message.content,Modifier.padding(16.dp),style=MaterialTheme.typography.bodyLarge) }
                        }
                    } else Column {
                        Row(verticalAlignment=Alignment.CenterVertically){CompanionAvatar(28.dp); Spacer(Modifier.width(8.dp)); Text("凛",style=MaterialTheme.typography.labelMedium)}
                        Spacer(Modifier.height(12.dp)); SelectionContainer { Text(message.content,style=MaterialTheme.typography.bodyLarge) }
                        Row { TextButton(onClick={model.speak(message.content)}){Icon(Icons.Outlined.VolumeUp,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("朗读")}
                            TextButton(onClick={model.stopSpeech()}){Text("停止")}}
                    }
                }
                if(busy) item { Row(verticalAlignment=Alignment.CenterVertically){CompanionAvatar(28.dp);Spacer(Modifier.width(12.dp));Text("凛正在想怎么讲清楚…",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(10.dp));CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)} }
                if(error!=null) item { Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(16.dp)){
                    Column(Modifier.padding(16.dp)){Text(error!!,color=MaterialTheme.colorScheme.onErrorContainer);TextButton(onClick=model::retry){Text("重试")}}
                } }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)) {
            Surface(shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(start=12.dp,end=8.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.Bottom) {
                    TextField(value=draft,onValueChange={onDraft(it.take(4000))},placeholder={Text("问凛，或者说说今天的学习…")},modifier=Modifier.weight(1f).testTag("chat_input"),maxLines=5,
                        colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={if(!busy&&draft.isNotBlank()){model.send(draft);onDraft("")}}))
                    FilledIconButton(onClick={if(busy)model.cancelChat() else {model.send(draft);onDraft("")}},enabled=busy||draft.isNotBlank(),modifier=Modifier.padding(bottom=6.dp).testTag("chat_send")){
                        Icon(if(busy)Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,if(busy)"停止生成" else "发送")
                    }
                }
            }
            Text("学习进度会随问题一起提供给 AI · 回答可能有误",Modifier.align(Alignment.CenterHorizontally).padding(top=6.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(clearDialog) AlertDialog(onDismissRequest={clearDialog=false},title={Text("清空这段对话？")},text={Text("聊天记录和文字记忆会删除，单词进度和练习记录会保留。")},confirmButton={TextButton(onClick={model.clearChat();clearDialog=false}){Text("清空")}},dismissButton={TextButton(onClick={clearDialog=false}){Text("取消")}})
}

@Composable fun TodayCard(state: LearningUi, navigate:(String)->Unit) {
    Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("今日的小目标",style=MaterialTheme.typography.titleSmall);Text("${state.stats.studyMinutes} / 120 分钟",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
            Spacer(Modifier.height(14.dp));LinearProgressIndicator(progress={(state.stats.studyMinutes/120f).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(5.dp),trackColor=MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text("新词 ${state.stats.newWords}   ·   复习 ${state.stats.reviewedWords}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("去学习 →",color=MaterialTheme.colorScheme.primary,modifier=Modifier.clickable{navigate("words")})
            }
        }
    }
}
@Composable fun ActionTile(title:String,subtitle:String,icon:ImageVector,modifier:Modifier=Modifier,onClick:()->Unit) {
    Surface(onClick=onClick,modifier=modifier,shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp)) { Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(25.dp));Spacer(Modifier.height(18.dp));Text(title,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(4.dp));Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
