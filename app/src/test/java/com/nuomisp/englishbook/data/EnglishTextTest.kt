package com.nuomisp.englishbook.data

import org.junit.Assert.*
import org.junit.Test

class EnglishTextTest {
    @Test fun englishSpansPreserveOffsetsInMixedChineseText() {
        val text="例句：I'm studying English. 我正在学习。"
        val words=EnglishText.words(text)
        assertEquals(listOf("I'm","studying","English"),words.map{it.text})
        words.forEach{assertEquals(it.text,text.substring(it.start,it.end))}
        assertEquals("I'm studying English.",EnglishText.contextAt(text,text.indexOf("studying")))
    }
    @Test fun urlsAndCodeAreNotSpokenAsStudySentences() {
        val text="Read this. https://example.com/x\n```\nsecret_key = value\n```\n你好：Try again!"
        val sentences=EnglishText.sentences(text).map{it.text}
        assertEquals(listOf("Read this.","Try again!"),sentences)
        assertFalse(EnglishText.words(text).any{it.text=="secret" || it.text=="example"})
    }
    @Test fun lookupNormalizesWithoutGuessingLemmas() {
        assertEquals("don't",Lexicon.normalize("Don’t!"))
        assertEquals("take care",Lexicon.normalize(" take   care "))
        assertEquals("physics",Lexicon.normalize("physics"))
    }
}
