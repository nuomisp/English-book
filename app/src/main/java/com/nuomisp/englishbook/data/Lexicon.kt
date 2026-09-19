package com.nuomisp.englishbook.data

import android.content.Context
import org.json.JSONObject
import java.util.Locale

class Lexicon(context: Context) {
    private val root = JSONObject(context.assets.open("dictionary.json").bufferedReader().use { it.readText() })
    val metadata: String = root.getJSONObject("metadata").toString(2)
    val words: List<Word> = root.getJSONArray("words").let { rows -> (0 until rows.length()).map { index ->
        val w=rows.getJSONObject(index)
        val tags=w.getJSONArray("tags").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        val phonetic=w.optString("ipa").trim('/')
        Word(w.getString("id"),w.getString("word"),if(phonetic.isBlank())"" else "/$phonetic/",
            w.getString("meaning"),w.optString("example"),w.optString("exampleZh"),
            if("zk" in tags)"基础" else if("cet4" in tags)"四级" else if("gk" in tags)"高中衔接" else "常用查词",
            tags,w.optInt("rank",999999),w.optString("forms"),w.getString("source"))
    } }
    private val index=words.associateBy { it.id }
    private val aliases=root.getJSONObject("aliases")
    fun lookup(text:String):List<Word> {
        val key=normalize(text)
        val result=mutableListOf<Word>()
        index[key]?.let { result.add(it) }
        aliases.optJSONArray(key)?.let { values ->
            for(i in 0 until values.length()) index[values.getString(i)]?.let { if(it !in result)result.add(it) }
        }
        return result.sortedWith(compareBy<Word>{ if(it.tags.isEmpty())1 else 0 }.thenBy { it.rank }).take(8)
    }
    companion object {
        fun normalize(text:String)=text.trim().trim('"','“','”','.',',','!','?',':',';','(',')')
            .replace('’','\'').lowercase(Locale.ROOT).replace(Regex("\\s+")," ")
    }
}

data class EnglishSpan(val text:String,val start:Int,val end:Int)
object EnglishText {
    private val token=Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*")
    private val excluded=Regex("```[\\s\\S]*?```|https?://\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]+")
    fun words(text:String):List<EnglishSpan> {
        val blocked=excluded.findAll(text).map { it.range }.toList()
        return token.findAll(text).filter { match-> blocked.none{match.range.first in it} }
            .map { EnglishSpan(it.value,it.range.first,it.range.last+1) }.toList()
    }
    fun sentences(text:String):List<EnglishSpan> {
        val sanitized=excluded.replace(text) { " ".repeat(it.value.length) }
        return Regex("[A-Za-z][A-Za-z0-9 \\t,;:'’\"()\\-–—.!?]*").findAll(sanitized)
            .flatMap { match ->
                val pieces=Regex("[^.!?]+[.!?]*").findAll(match.value)
                pieces.map { part ->
                    val leading=part.value.length-part.value.trimStart().length
                    val value=part.value.trim()
                    EnglishSpan(value,match.range.first+part.range.first+leading,match.range.first+part.range.first+leading+value.length)
                }
            }.filter { it.text.any(Char::isLetter) && it.text.length>1 }.toList()
    }
    fun contextAt(text:String,offset:Int)=sentences(text).find { offset in it.start until it.end }?.text ?: text.take(1500)
}
