@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.nuomisp.englishbook.ui

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuomisp.englishbook.LearningUi
import com.nuomisp.englishbook.StudyViewModel
import com.nuomisp.englishbook.data.*

/** Native selection toolbar keeps Copy, adds an explicit selected-text card action. */
private class CardSelectionToolbar(private val view:View,private val clipboard:ClipboardManager,
    private val open:(String)->Unit):TextToolbar {
    private var mode:ActionMode?=null
    override val status:TextToolbarStatus get()=if(mode==null)TextToolbarStatus.Hidden else TextToolbarStatus.Shown
    override fun hide(){mode?.finish();mode=null}
    override fun showMenu(rect:Rect,onCopyRequested:(()->Unit)?,onPasteRequested:(()->Unit)?,onCutRequested:(()->Unit)?,onSelectAllRequested:(()->Unit)?) {
        hide()
        mode=view.startActionMode(object:ActionMode.Callback2(){
            override fun onCreateActionMode(actionMode:ActionMode,menu:Menu):Boolean {
                if(onCopyRequested!=null){menu.add(0,1,0,"词卡 / 朗读");menu.add(0,2,1,"复制")}
                if(onSelectAllRequested!=null)menu.add(0,3,2,"全选")
                return true
            }
            override fun onPrepareActionMode(actionMode:ActionMode,menu:Menu)=false
            override fun onActionItemClicked(actionMode:ActionMode,item:MenuItem):Boolean {
                when(item.itemId){
                    1->{onCopyRequested?.invoke();clipboard.getText()?.text?.let(open);actionMode.finish()}
                    2->{onCopyRequested?.invoke();actionMode.finish()}
                    3->onSelectAllRequested?.invoke()
                }
                return true
            }
            override fun onDestroyActionMode(actionMode:ActionMode){mode=null}
            override fun onGetContentRect(actionMode:ActionMode,view:View,outRect:android.graphics.Rect) {
                val position=IntArray(2);view.getLocationOnScreen(position)
                outRect.set(rect.left.toInt()-position[0],rect.top.toInt()-position[1],rect.right.toInt()-position[0],rect.bottom.toInt()-position[1])
            }
        },ActionMode.TYPE_FLOATING)
    }
}

@Composable fun EnglishRichText(text:String,source:String,model:StudyViewModel,modifier:Modifier=Modifier) {
    val spans=remember(text){EnglishText.words(text)}
    val view=LocalView.current
    val clipboard=LocalClipboardManager.current
    val toolbar=remember(text,source,view,clipboard){CardSelectionToolbar(view,clipboard){selected->
        if(selected.isNotBlank() && text.contains(selected))model.openCard(selected,text,source)
    }}
    DisposableEffect(toolbar){onDispose{toolbar.hide()}}
    val annotated=buildAnnotatedString {
        append(text)
        spans.forEach { span->addStyle(SpanStyle(color=MaterialTheme.colorScheme.primary),span.start,span.end) }
    }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        SelectionContainer {
            ClickableText(text=annotated,modifier=modifier,style=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),onClick={offset->
                spans.find{offset in it.start until it.end}?.let{model.openCard(it.text,EnglishText.contextAt(text,offset),source)}
            })
        }
    }
}

@Composable fun SentenceCardButtons(text:String,source:String,model:StudyViewModel) {
    val sentences=remember(text){EnglishText.sentences(text).map{it.text}.distinct()}
    var expanded by remember(text){mutableStateOf(false)}
    if(sentences.isNotEmpty()) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            sentences.take(if(expanded)sentences.size else 3).forEachIndexed{index,sentence->
                AssistChip(onClick={model.openCard(sentence,sentence,source)},label={Text(if(sentences.size==1)"句子朗读卡" else "第 ${index+1} 句")},leadingIcon={Icon(Icons.Outlined.VolumeUp,null,Modifier.size(16.dp))})
            }
        }
        if(sentences.size>3)TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起句子" else "展开全部 ${sentences.size} 句")}
    }
}

@Composable fun StudyCardSheet(model:StudyViewModel) {
    val value by model.card.collectAsStateWithLifecycle()
    val speech by model.speechUi.collectAsStateWithLifecycle()
    val state by model.ui.collectAsStateWithLifecycle()
    val card=value ?: return
    val word=card.entries.find{it.id==card.selectedId}
    val text=word?.word ?: card.request.text
    ModalBottomSheet(onDismissRequest=model::closeCard,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=650.dp).testTag("study_card"),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Text(if(word!=null)"单词卡" else "英文朗读卡",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(12.dp));SelectionContainer{Text(text,style=MaterialTheme.typography.headlineMedium)}
                if(word!=null && word.id!=Lexicon.normalize(card.request.text))Text("来自词形：${card.request.text}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(word?.ipa?.isNotBlank()==true)Text(word.ipa,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(card.loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(card.entries.size>1)item{
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){card.entries.forEach{entry->FilterChip(selected=entry.id==word?.id,onClick={model.chooseCardWord(entry.id)},label={Text(entry.word)})}}
            }
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Button(onClick={model.speak(text)},modifier=Modifier.testTag("card_speak")){Icon(Icons.Outlined.VolumeUp,null);Spacer(Modifier.width(6.dp));Text(if(speech.error.isNotEmpty())"重试朗读" else "朗读 / 重播")}
                    OutlinedButton(onClick=model::stopSpeech,enabled=speech.status.isNotBlank()){Text("停止")}
                }
                if(speech.status=="loading")Text("准备声音…",color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(speech.status=="playing")Text("正在朗读",color=MaterialTheme.colorScheme.primary)
                if(speech.error.isNotEmpty())Text(speech.error,color=MaterialTheme.colorScheme.error)
            }
            if(word!=null)item{
                SelectionContainer{Text(word.meaning,style=MaterialTheme.typography.bodyLarge)}
                Text("${word.level} · 释义来源：${word.source}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(word.forms.isNotBlank())Text("词形："+word.forms.split('/').mapNotNull{part->
                    val p=part.split(':',limit=2);val name=mapOf("p" to "过去式","d" to "过去分词","i" to "现在分词","3" to "三单","s" to "复数","r" to "比较级","t" to "最高级")[p.firstOrNull()]
                    if(p.size==2 && name!=null)"$name ${p[1]}" else null
                }.joinToString(" · "),style=MaterialTheme.typography.bodySmall)
            } else if(!card.loading)item{Text("本地没有对应词条。仍可朗读和收藏，翻译或讲解需连接 AI。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(card.request.context.isNotBlank() && card.request.context!=text)item{
                Text("遇见它的原句",style=MaterialTheme.typography.titleSmall)
                EnglishRichText(card.request.context,card.request.source,model)
                TextButton(onClick={model.speak(card.request.context)}){Text("朗读原句")}
            }
            item {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick={model.saveCard(if(word!=null)"word" else "sentence",text,word?.id.orEmpty(),card.request.context,card.request.source)},modifier=Modifier.testTag("card_save")){Text(if(word!=null)"收藏单词" else "收藏句子 / 短语")}
                    if(word!=null)OutlinedButton(onClick={model.setWordStatus(word.id,"queued");model.message.value="已加入学习队列；已有复习进度会保留。"},modifier=Modifier.testTag("card_enroll")){Text(if(state.overrides[word.id]=="queued")"已加入学习" else "加入学习")}
                }
                if(word!=null)TextButton(onClick={model.setWordStatus(word.id,if(state.overrides[word.id]=="familiar")"" else "familiar")}){Text(if(state.overrides[word.id]=="familiar")"恢复这个词的学习" else "这个词已熟悉，暂不安排")}
            }
            item {HorizontalDivider();Button(onClick=model::explainCard,enabled=!card.explaining,modifier=Modifier.testTag("card_explain")){Text(if(card.explaining)"凛正在讲解…" else "AI 翻译 / 讲解")}}
            if(card.error.isNotEmpty())item{Text(card.error,color=MaterialTheme.colorScheme.error)}
            if(card.explanation.isNotEmpty())item{
                Text("AI 讲解 · 请结合原句判断",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                EnglishRichText(card.explanation,"词卡 AI 讲解",model)
                SentenceCardButtons(card.explanation,"词卡 AI 讲解",model)
            }
            item{Text("来源：${card.request.source}\n查词、播放和收藏不会计为掌握。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}

@Composable fun SavedCardsScreen(model:StudyViewModel,state:LearningUi) {
    var wordsOnly by rememberSaveable{mutableStateOf(true)}
    var query by rememberSaveable{mutableStateOf("")}
    LazyColumn(Modifier.fillMaxSize().testTag("saved_screen"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item{Text("把遇见的好词好句留下。",style=MaterialTheme.typography.headlineMedium)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){FilterChip(wordsOnly,{wordsOnly=true},label={Text("生词本")});FilterChip(!wordsOnly,{wordsOnly=false},label={Text("收藏句子")})}}
        item{OutlinedTextField(query,{query=it},label={Text("搜索收藏")},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(18.dp))}
        val cards=state.savedCards.filter{(it.kind=="word")==wordsOnly && (it.text.contains(query,true)||it.context.contains(query,true))}
        if(cards.isEmpty())item{EmptyPanel("还没有这类收藏","在助手回复或阅读中点英文单词；长按选择短语，也可以打开朗读卡。")}
        items(cards,key={it.id}){saved->
            Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(saved.text,style=MaterialTheme.typography.titleLarge)
                if(saved.context.isNotBlank()&&saved.context!=saved.text)Text(saved.context,style=MaterialTheme.typography.bodyMedium)
                Text(saved.source,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    TextButton(onClick={model.openCard(saved.text,saved.context,saved.source)}){Text("打开词句卡")}
                    if(saved.wordId.isNotBlank())TextButton(onClick={model.setWordStatus(saved.wordId,"queued");model.message.value="已加入学习队列"}){Text("加入学习")}
                    TextButton(onClick={model.removeCard(saved.id)}){Text("取消收藏")}
                }
            }}
        }
    }
}
