@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.nuomisp.englishbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuomisp.englishbook.LearningUi
import com.nuomisp.englishbook.StudyViewModel
import com.nuomisp.englishbook.data.*

@Composable fun WordsScreen(model: StudyViewModel, state: LearningUi, ask:(Word)->Unit) {
    var browsing by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.queue,activeId) { if(activeId==null) activeId=state.queue.firstOrNull()?.id }
    val word = (selectedId ?: activeId)?.let { id->state.words.find{it.id==id} }
    LazyColumn(Modifier.fillMaxSize().testTag("words_screen"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {
            Text("把每一个词，\n变成老朋友。",style=MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp));Text("先回忆，再揭晓。今天的努力会被记住。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                StatChip("新学",state.stats.newWords.toString(),Modifier.weight(1f))
                StatChip("复习",state.stats.reviewedWords.toString(),Modifier.weight(1f))
                StatChip("待复习",state.due.toString(),Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                FilterChip(selected=!browsing,onClick={browsing=false;selectedId=null},label={Text("今日练习")})
                FilterChip(selected=browsing,onClick={browsing=true;selectedId=null},label={Text("词库 · ${state.words.size}")})
            }
        }
        if(browsing && selectedId==null) {
            item { OutlinedTextField(search,{search=it},label={Text("搜索单词或释义")},leadingIcon={Icon(Icons.Outlined.Search,null)},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(18.dp)) }
            items(state.words.filter{it.word.contains(search,true)||it.meaning.contains(search)},key={it.id}) { entry ->
                Surface(onClick={selectedId=entry.id;model.interact()},shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)){Text(entry.word,style=MaterialTheme.typography.titleLarge);Text(entry.meaning,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        Text(if(state.progress[entry.id]?.isMastered==true)"已巩固" else if(state.progress.containsKey(entry.id))"学习中" else "新词",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } else if(word!=null) {
            if(selectedId!=null) item { TextButton(onClick={selectedId=null}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Text("返回词库") } }
            item(key=word.id) {
                WordCard(word,model,onRated={rating->model.rate(word,rating){selectedId=null;activeId=null}},onNext={selectedId=null;activeId=null},ask={ask(word)})
            }
            item { Text("先复习到期词，再学新词；每日新词上限 20 个。掌握情况来自多次复习，不靠一次点“认识”。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        } else item {
            EmptyPanel("这一轮完成了。","别得意，记忆需要时间。稍后再来复习，也可以去读一篇短文。")
        }
    }
}

@Composable private fun WordCard(word:Word,model:StudyViewModel,onRated:(ReviewRating)->Unit,onNext:()->Unit,ask:()->Unit) {
    var revealed by rememberSaveable(word.id) { mutableStateOf(false) }
    var spelling by rememberSaveable(word.id) { mutableStateOf(false) }
    var answer by rememberSaveable(word.id) { mutableStateOf("") }
    var result by remember(word.id) { mutableStateOf<Boolean?>(null) }
    var submitting by remember(word.id) { mutableStateOf(false) }
    Surface(shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(word.level,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(28.dp))
            if(!spelling) {
                Text(word.word,fontSize=38.sp,lineHeight=46.sp,fontWeight=FontWeight.Medium,modifier=Modifier.testTag("study_word"))
                Spacer(Modifier.height(8.dp));Text(word.ipa,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text(word.meaning,style=MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp));FilledTonalIconButton(onClick={model.speak(word.word)},modifier=Modifier.size(48.dp)){Icon(Icons.Outlined.VolumeUp,"播放单词")}
            Spacer(Modifier.height(26.dp))
            if(spelling) {
                OutlinedTextField(answer,{answer=it;model.interact()},label={Text("凭记忆拼写")},singleLine=true,enabled=result==null,modifier=Modifier.fillMaxWidth().testTag("spelling_input"),keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.None,autoCorrectEnabled=false),shape=RoundedCornerShape(16.dp))
                Spacer(Modifier.height(14.dp))
                if(result==null) Button(onClick={submitting=true;model.spelling(word,answer){result=it;submitting=false}},enabled=answer.isNotBlank()&&!submitting,modifier=Modifier.fillMaxWidth()){Text("检查拼写")}
                else {
                    Text(if(result==true)"拼对了。这次算你厉害。" else "再记一遍：${word.word}",color=if(result==true)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                    TextButton(onClick={spelling=false;revealed=true}){Text("看释义与例句")}
                }
            } else if(!revealed) {
                Text("先想想它的意思，别急着偷看。",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp));Button(onClick={revealed=true;model.interact()},modifier=Modifier.fillMaxWidth().height(50.dp).testTag("reveal_word")){Text("揭晓释义")}
            } else {
                SelectionContainer { Column(Modifier.fillMaxWidth()) {
                    Text(word.meaning,style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(22.dp));Text(word.example,style=MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp));Text(word.exampleZh,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                Row(Modifier.fillMaxWidth()){TextButton(onClick={model.speak(word.example)}){Text("听例句")};TextButton(onClick=ask){Text("问问凛")}}
                Spacer(Modifier.height(12.dp))
                if(result!=null) TextButton(onClick=onNext){Text("继续下一个")}
                else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={submitting=true;onRated(ReviewRating.AGAIN)},enabled=!submitting,modifier=Modifier.weight(1f)){Text("不认识")}
                    OutlinedButton(onClick={submitting=true;onRated(ReviewRating.HARD)},enabled=!submitting,modifier=Modifier.weight(1f)){Text("模糊")}
                    Button(onClick={submitting=true;onRated(ReviewRating.GOOD)},enabled=!submitting,modifier=Modifier.weight(1f).testTag("rate_good")){Text("认识")}
                }
            }
            if(result==null) TextButton(onClick={spelling=!spelling;model.interact()}){Text(if(spelling)"回到认词" else "试试拼写")}
        }
    }
}

@Composable fun ReadingScreen(model:StudyViewModel,state:LearningUi,ask:(String)->Unit) {
    var articleId by rememberSaveable { mutableStateOf<String?>(null) }
    val article=state.articles.find{it.id==articleId}
    if(article==null) LazyColumn(Modifier.fillMaxSize().testTag("reading_screen"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { Text("从读懂一小段开始。",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(10.dp));Text("原创分级练习 · 不是真题\n先自己读，再让凛帮你拆解。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.articles,key={it.id}) { item ->
            Surface(onClick={articleId=item.id;model.interact()},shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) { Text(item.level,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium);Spacer(Modifier.height(16.dp));Text(item.title,style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(12.dp));Text("${item.paragraphs.joinToString(" ").split(" ").size} 词 · ${item.questions.size} 道理解题",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(16.dp));Text("开始阅读 →",color=MaterialTheme.colorScheme.primary) }
            }
        }
    } else ReadingArticlePage(model,article,{articleId=null},ask)
}

@Composable private fun ReadingArticlePage(model:StudyViewModel,article:ReadingArticle,back:()->Unit,ask:(String)->Unit) {
    var translation by rememberSaveable(article.id) { mutableStateOf(false) }
    var answers by rememberSaveable(article.id) { mutableStateOf(List(article.questions.size){-1}) }
    var result by remember(article.id) { mutableStateOf<ReadingResult?>(null) }
    var grading by remember(article.id) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { TextButton(onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Text("返回文章")};Text(article.title,style=MaterialTheme.typography.headlineMedium);Text(article.level,color=MaterialTheme.colorScheme.primary) }
        items(article.paragraphs) { paragraph ->
            Column { SelectionContainer { Text(paragraph,style=MaterialTheme.typography.bodyLarge.copy(fontSize=18.sp,lineHeight=31.sp)) }
                Row {TextButton(onClick={model.speak(paragraph)}){Icon(Icons.Outlined.VolumeUp,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text("听这一段")}
                    TextButton(onClick={ask("请讲解这段英文，挑出适合初中基础学习的单词和句子结构：\n$paragraph")}){Text("请凛讲解")}}
            }
        }
        item { TextButton(onClick={translation=!translation;model.interact()}){Text(if(translation)"收起中文参考" else "查看中文参考")};if(translation)Text(article.translation,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { HorizontalDivider();Spacer(Modifier.height(20.dp));Text("读懂了吗？",style=MaterialTheme.typography.titleLarge) }
        article.questions.forEachIndexed { index, question ->
            item {
                Column { Text("${index+1}. ${question.prompt}",fontWeight=FontWeight.Medium)
                    question.options.forEachIndexed { optionIndex,option ->
                        Row(Modifier.fillMaxWidth().clickable(enabled=result==null){answers=answers.toMutableList().also{it[index]=optionIndex};model.interact()}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=answers[index]==optionIndex,onClick=null,enabled=result==null);Spacer(Modifier.width(10.dp));Text(option,Modifier.weight(1f))}
                    }
                    if(result!=null) { Text(if(answers[index]==question.answerIndex)"回答正确" else "正确答案：${question.options[question.answerIndex]}",color=MaterialTheme.colorScheme.primary);Text(question.explanation,style=MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item { if(result==null) Button(onClick={grading=true;model.reading(article,answers){result=it;grading=false}},enabled=answers.none{it<0}&&!grading,modifier=Modifier.fillMaxWidth()){Text("提交理解题")}
            else Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.tertiaryContainer){Text("完成阅读 · ${result!!.correct} / ${result!!.total} 题正确\n学习记录已保存。",Modifier.fillMaxWidth().padding(20.dp))}
        }
    }
}

@Composable fun ListeningScreen(model:StudyViewModel,state:LearningUi,ask:(String)->Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val sentence=state.dictations.getOrNull(index%state.dictations.size.coerceAtLeast(1))
    if(sentence==null) { EmptyPanel("还没有听写材料","请稍后再试。");return }
    var answer by rememberSaveable(sentence.id) { mutableStateOf("") }
    var result by remember(sentence.id) { mutableStateOf<DictationResult?>(null) }
    var submitting by remember(sentence.id) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("listening_screen"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item { Text("先听见，再写下来。",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(10.dp));Text("短句听写 · ${index%state.dictations.size+1} / ${state.dictations.size}",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Surface(shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Spacer(Modifier.height(10.dp));FilledTonalIconButton(onClick={model.speak(sentence.text)},modifier=Modifier.size(84.dp)){Icon(Icons.Outlined.PlayArrow,"播放听写",Modifier.size(42.dp))}
                Spacer(Modifier.height(20.dp));Text("耳朵准备好了吗？",style=MaterialTheme.typography.titleLarge)
                Text("可以多听几遍，答案先保密。",Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={model.stopSpeech()}){Text("停止播放")}
            }
        } }
        item { OutlinedTextField(answer,{answer=it;model.interact()},label={Text("写下你听到的英文")},modifier=Modifier.fillMaxWidth().heightIn(min=150.dp).testTag("dictation_input"),enabled=result==null,minLines=4,shape=RoundedCornerShape(22.dp),keyboardOptions=KeyboardOptions(autoCorrectEnabled=false)) }
        item {
            if(result==null) Button(onClick={submitting=true;model.dictation(sentence,answer){result=it;submitting=false}},enabled=answer.isNotBlank()&&!submitting,modifier=Modifier.fillMaxWidth().height(52.dp)){Text("检查听写")}
            else Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(if(result!!.isCorrect)"全对。看来认真听了嘛。" else "有几处没听清，来对照一下。",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                SelectionContainer { Text(sentence.text,style=MaterialTheme.typography.bodyLarge) };Text(sentence.translation,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={ask("我在听写这句英文：${sentence.text}\n我写的是：$answer\n请帮我分析漏听或拼写错误，再讲讲句意。")}){Text("让凛帮我分析")}
                Button(onClick={index++;model.stopSpeech();model.interact()},modifier=Modifier.fillMaxWidth()){Text("下一句")}
            }
        }
        item { Text("使用合成语音进行基础听写；四级真题听力将单独接入原录音。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun ProgressScreen(state:LearningUi,navigate:(String)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item { Text("你走过的每一步。",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(10.dp));Text("${state.stats.date} · 今天",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { TodayCard(state,navigate) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {StatChip("接触过的词",state.learned.toString(),Modifier.weight(1f));StatChip("长期巩固",state.mastered.toString(),Modifier.weight(1f))} }
        item { Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text("今天的练习",style=MaterialTheme.typography.titleLarge)
            Text("阅读完成  ${state.stats.readingCompleted} 篇")
            Text("听写正确  ${state.stats.listeningCorrect} / ${state.stats.listeningAttempts} 次")
            Text("到期复习  ${state.due} 词")
        }} }
        item { Text("基础阶段 · 120 分钟",style=MaterialTheme.typography.titleLarge) }
        items(listOf("旧词复习" to 25,"新词与例句" to 25,"拼写和词汇小测" to 20,"短文阅读" to 20,"短句听写" to 20,"语法与答疑" to 10)) { plan ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(plan.first,Modifier.width(115.dp));LinearProgressIndicator(progress={plan.second/30f},modifier=Modifier.weight(1f).height(5.dp));Text("${plan.second} 分",Modifier.padding(start=14.dp),style=MaterialTheme.typography.labelMedium)}
        }
        item { Text("时长只统计前台练习页，连续 90 秒无互动会暂停计时；不是打开软件就算学习。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun MemoryScreen(model:StudyViewModel,state:LearningUi) {
    var memory by remember(state.memory) { mutableStateOf(state.memory) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item { Text("记得你，也听你的。",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));Text("这里的学习记忆会随问题发送给你配置的 AI 服务。可以修改，也可以清空。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("当前学习档案",style=MaterialTheme.typography.titleMedium)
            Text("起点：初中英语基础\n目标：大学英语四级\n节奏：每天 120 分钟，前期词汇优先\n陪伴方式：傲娇、严格纠错；你说收敛就收敛。")
        }} }
        item { OutlinedTextField(memory,{memory=it.take(3000)},modifier=Modifier.fillMaxWidth(),minLines=7,label={Text("可编辑的长期记忆")},placeholder={Text("例如：我经常混淆时态；讲语法时先给简单例句。")},shape=RoundedCornerShape(20.dp)) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Button(onClick={model.saveMemory(memory)}){Text("保存记忆")};TextButton(onClick={memory="";model.saveMemory("")}){Text("清空记忆")}} }
        item { OutlinedButton(onClick=model::summarizeMemory){Text("让凛整理近期对话")} }
        item { Text("单词进度由数据库记录，与这里的文字记忆分开保存。清空文字记忆不会清除单词和练习记录。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun StatChip(label:String,value:String,modifier:Modifier=Modifier) {
    Surface(modifier=modifier,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){Column(Modifier.padding(16.dp)){Text(value,fontSize=26.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.primary);Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
}
@Composable fun EmptyPanel(title:String,body:String) { Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){CompanionAvatar(64.dp);Spacer(Modifier.height(20.dp));Text(title,style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(12.dp));Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)} }
