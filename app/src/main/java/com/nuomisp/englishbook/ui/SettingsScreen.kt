package com.nuomisp.englishbook.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuomisp.englishbook.StudyViewModel
import com.nuomisp.englishbook.services.TtsMode
import java.time.LocalDate

@Composable fun SettingsScreen(model:StudyViewModel) {
    val saved by model.settings.collectAsStateWithLifecycle()
    var form by remember(saved) { mutableStateOf(saved) }
    val context = LocalContext.current
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.message.value = if(granted) "通知权限已开启，保存设置后安排提醒。" else "通知权限未开启；可在系统设置里修改。"
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let{model.exportBackup(it)} }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri=uri }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("settings_screen"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text("按你的习惯来。",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(8.dp));Text("密钥仅加密保存在本机；接口地址需使用 HTTPS。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SettingHeading("AI 接口","兼容 OpenAI Chat Completions 的中转服务") }
        item { SettingField("接口基础地址",form.baseUrl,{form=form.copy(baseUrl=it)},"https://你的中转站/v1") }
        item { SettingField("API 密钥",form.apiKey,{form=form.copy(apiKey=it)},secret=true) }
        item { SettingField("对话模型",form.chatModel,{form=form.copy(chatModel=it)},"填写中转站提供的模型 ID") }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Button(onClick={model.testConnection(form)}){Text("保存并测试 AI")}} }
        item { SettingHeading("模型分工（可选）","留空时共用对话模型；所有回答都由同一个凛呈现。") }
        item { SettingField("教学模型",form.teachingModel,{form=form.copy(teachingModel=it)},"讲解与批改，留空使用对话模型") }
        item { SettingField("记忆模型",form.memoryModel,{form=form.copy(memoryModel=it)},"摘要与提醒，留空使用对话模型") }
        item { SettingHeading("朗读声音","先用系统英语语音，或接你自己的语音 API。") }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            FilterChip(selected=form.ttsMode==TtsMode.SYSTEM,onClick={form=form.copy(ttsMode=TtsMode.SYSTEM)},label={Text("系统英语语音")})
            FilterChip(selected=form.ttsMode==TtsMode.OPENAI_API,onClick={form=form.copy(ttsMode=TtsMode.OPENAI_API)},label={Text("语音 API")})
        } }
        if(form.ttsMode==TtsMode.OPENAI_API) {
            item { SettingField("语音接口地址",form.ttsBaseUrl,{form=form.copy(ttsBaseUrl=it)},"https://语音服务/v1") }
            item { SettingField("语音 API 密钥",form.ttsApiKey,{form=form.copy(ttsApiKey=it)},secret=true) }
            item { SettingField("语音模型",form.ttsModel,{form=form.copy(ttsModel=it)}) }
            item { SettingField("音色 ID",form.ttsVoice,{form=form.copy(ttsVoice=it)}) }
            item { Text("需要兼容 /audio/speech 并返回 MP3；普通聊天模型不一定提供语音。音频缓存最多约 64MB。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        } else item { Text("使用手机已安装的英语语音包。MOSS-TTS-Nano 尚未接入此验证版。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {OutlinedButton(onClick={if(model.saveSettings(form))model.speak("A little progress every day adds up to big results.")}){Text("保存并试听")};TextButton(onClick=model::stopSpeech){Text("停止")}} }
        item { SettingHeading("凛的主动提醒","09:00–22:00 · 4 次随机提醒 + 21:30 回顾") }
        item { SettingToggle("开启学习提醒",form.remindersEnabled){form=form.copy(remindersEnabled=it)} }
        item { Text("完成 120 分钟后停止随机催学。Android 和魅族省电策略可能延迟通知，21:30 是计划时间。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedButton(onClick={if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)else model.message.value="当前系统不需要单独申请通知权限。"}){Text("申请通知权限")} }
        item { TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}){Text("打开系统通知设置")} }
        item { SettingToggle("使用自己的提醒服务器",form.serverModeEnabled){form=form.copy(serverModeEnabled=it)} }
        if(form.serverModeEnabled) {
            item { Text("开启后使用服务器消息同步，替代本地随机提醒。会上传日期、学习数量、时长和易错词，不上传聊天或手机 API 密钥。后台约每 15 分钟同步，可能被省电延迟。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            item { SettingField("服务器地址",form.serverUrl,{form=form.copy(serverUrl=it)},"https://提醒服务域名") }
            item { SettingField("服务器连接令牌",form.serverToken,{form=form.copy(serverToken=it)},secret=true) }
            item { OutlinedButton(onClick={model.testServer(form)}){Text("保存并测试服务器")} }
        }
        item { Button(onClick={model.saveSettings(form)},modifier=Modifier.fillMaxWidth().height(52.dp).testTag("save_settings")){Text("保存全部设置")} }
        item { SettingHeading("学习数据","备份包含学习记录、聊天和文字记忆，不包含密钥。") }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick={export.launch("englishbook-${LocalDate.now()}.json")}){Text("导出备份")}
            OutlinedButton(onClick={restore.launch(arrayOf("application/json","text/plain"))}){Text("恢复备份")}
        } }
        item { Text("葱伴英语 0.1.0 · 原生安卓验证版\n内置 100 个起步词和原创练习，尚非完整四级词库。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if(restoreUri!=null) AlertDialog(onDismissRequest={restoreUri=null},title={Text("用备份替换学习记录？")},text={Text("会替换当前单词进度、练习记录、聊天和记忆。API 设置不受影响，建议先导出当前备份。")},confirmButton={TextButton(onClick={restoreUri?.let{model.importBackup(it)};restoreUri=null}){Text("恢复")}},dismissButton={TextButton(onClick={restoreUri=null}){Text("取消")}})
}
@Composable private fun SettingHeading(title:String,description:String) { Column(Modifier.padding(top=16.dp)) {Text(title,style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(6.dp));Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)} }
@Composable private fun SettingToggle(label:String,checked:Boolean,change:(Boolean)->Unit) {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(checked,change)} }
@Composable private fun SettingField(label:String,value:String,change:(String)->Unit,placeholder:String="",secret:Boolean=false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value=value,onValueChange=change,label={Text(label)},placeholder={Text(placeholder)},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),
        visualTransformation=if(secret&&!visible)PasswordVisualTransformation()else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=if(secret)KeyboardType.Password else KeyboardType.Text,autoCorrectEnabled=false),
        trailingIcon={if(secret)IconButton(onClick={visible=!visible}){Icon(if(visible)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(visible)"隐藏密钥" else "显示密钥")}})
}
