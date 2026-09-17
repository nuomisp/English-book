package com.nuomisp.englishbook.data

import android.content.Context

internal object StarterContent {
    fun words(context: Context): List<Word> = context.assets.open("vocabulary.txt")
        .bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val f = line.split('|')
                check(f.size == 6) { "Invalid starter vocabulary entry" }
                Word(f[0], f[0], f[1], f[2], f[3], f[4], f[5])
            }.toList()
        }.also { words -> check(words.map { it.id }.distinct().size == words.size) }

    val articles = listOf(
        ReadingArticle(
            id = "a-small-start",
            title = "A Small Start · 从小开始",
            level = "基础 · 约 90 词",
            paragraphs = listOf(
                "Lin wants to read English books, but she does not know many words. Every morning, she learns five words before breakfast. She writes one short sentence with each word. In the evening, she closes her notebook and tries to remember them.",
                "On busy days, Lin studies for only ten minutes. She does not learn new words when she has many old words to review. After two weeks, she can read a short story. She still makes mistakes, but she is happy. A small start can lead to a useful habit.",
            ),
            translation = "小林想读英文书，但她认识的单词不多。每天早上，她在早餐前学五个单词，并用每个单词写一个短句。到了晚上，她合上笔记本，试着回忆这些词。\n\n忙碌的日子里，小林只学习十分钟。如果有很多旧词需要复习，她就不学新词。两周后，她可以读一篇小故事了。她仍然会犯错，但她很高兴。一个小小的开始也能带来有用的习惯。",
            questions = listOf(
                ReadingQuestion("When does Lin learn new words?", listOf("Before breakfast.", "After dinner.", "At midnight."), 0, "第一段明确写道：她每天早饭前学五个单词。"),
                ReadingQuestion("What does Lin do when many old words need review?", listOf("She learns more new words.", "She stops learning English.", "She reviews instead of learning new words."), 2, "旧词多时，她先复习，并暂停新词，不是放弃学习。"),
                ReadingQuestion("What is the main idea?", listOf("Mistakes are always bad.", "Small, regular steps can build a habit.", "Everyone must study before breakfast."), 1, "文章通过小林的日常练习说明：从小事开始、持续练习可以培养习惯。"),
            ),
        ),
        ReadingArticle(
            id = "a-quiet-place",
            title = "A Quiet Place · 找到专注的地方",
            level = "进阶 · 约 130 词",
            paragraphs = listOf(
                "Kai used to study on his bed with his phone beside him. Every few minutes, a message arrived. He often spent an hour with his books but remembered very little. He thought that English was too difficult for him.",
                "One afternoon, a friend invited him to the library. Kai left his phone in his bag and chose a small task: read one page and answer three questions. He worked for twenty minutes and then took a short break. For the first time that week, he finished his plan.",
                "Kai still uses his phone to listen to English. However, he turns off other notifications during a lesson. A useful tool can also take away our attention. The important thing is to decide how and when to use it.",
            ),
            translation = "凯以前常躺在床上学习，手机就放在旁边。每隔几分钟就有一条消息。他经常对着书坐一小时，却记住得很少。他觉得英语对自己来说太难了。\n\n一天下午，朋友邀请他去图书馆。凯把手机留在包里，选了一个小任务：读一页书，回答三个问题。他学了二十分钟，然后短暂休息。这是他那一周第一次完成自己的计划。\n\n凯仍用手机听英语。不过，上课时他会关闭其他通知。有用的工具也可能分散我们的注意力。重要的是决定怎样使用、何时使用它。",
            questions = listOf(
                ReadingQuestion("Why did Kai remember little at first?", listOf("His books were too old.", "Messages often interrupted him.", "He studied only at the library."), 1, "开头的消息频繁到来，打断了他的学习；文章并没有说他的书太旧。"),
                ReadingQuestion("What task did Kai choose at the library?", listOf("Finish an entire book.", "Learn one hundred words.", "Read one page and answer three questions."), 2, "第二段直接给出他的小任务：读一页并回答三个问题。"),
                ReadingQuestion("What does the writer suggest about phones?", listOf("Use them with a clear purpose.", "Never use them for learning.", "Keep all notifications on."), 0, "末段认为手机既能帮助学习也能分散注意力，要有意识地决定使用方式和时间。"),
            ),
        ),
        ReadingArticle(
            id = "learning-with-others",
            title = "Learning with Others · 在合作中进步",
            level = "四级衔接 · 约 170 词",
            paragraphs = listOf(
                "A small reading group meets at a community library every Saturday. Its members have different levels of English. Some can read long articles, while others need help with simple sentences. At first, the beginners worried that they would slow everyone down.",
                "The group found a method that worked for them. Before each meeting, everyone reads the same short article. Beginners bring questions about words and sentences. More experienced readers prepare a short explanation of the main idea. During the meeting, members compare their answers and explain the reasons for their choices.",
                "The beginners soon discovered that asking a clear question was an important skill. Meanwhile, the stronger readers noticed gaps in their own knowledge when they tried to explain something. Teaching others required them to think more carefully.",
                "The group does not replace individual study. Members still review vocabulary and practise listening on their own. However, the weekly meeting provides support and a reason to continue. Progress is not always fast, but nobody has to face every challenge alone.",
            ),
            translation = "每周六，一个小型读书小组都会在社区图书馆碰面。成员们的英语水平不同：有些人能读长文章，有些人读简单句子也需要帮助。起初，初学者担心自己会拖慢大家的进度。\n\n小组找到了适合自己的方法。每次活动前，大家阅读同一篇短文。初学者带来单词和句子方面的问题，经验更多的读者准备简要解释文章主旨。活动中，成员们比较答案，并解释各自选择的理由。\n\n初学者很快发现，提出一个清楚的问题也是重要技能。与此同时，水平较高的读者在试着解释问题时，也注意到自己知识的不足。教别人要求他们更仔细地思考。\n\n小组活动并不能替代独立学习。成员们仍然自己复习词汇、练习听力。但是，每周的活动提供了支持，也给了大家坚持下去的理由。进步并不总是很快，但没有人必须独自面对所有挑战。",
            questions = listOf(
                ReadingQuestion("What worried the beginners at first?", listOf("The library was too far away.", "They might slow the group down.", "The meetings were too early."), 1, "第一段末句指出，初学者担心自己拖慢大家的学习进度。"),
                ReadingQuestion("How did stronger readers benefit?", listOf("They no longer needed to study.", "They avoided difficult questions.", "Explaining ideas helped reveal gaps in their knowledge."), 2, "第三段说明，向别人解释时，他们也发现了自己知识的不足。"),
                ReadingQuestion("What is the writer's view of group learning?", listOf("It supports individual study rather than replacing it.", "It makes individual study unnecessary.", "It works only for advanced readers."), 0, "末段强调小组活动不替代自学，但能提供支持，帮助大家坚持。"),
            ),
        ),
    )
}
