package com.barton.dualscreenhost

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan

data class WordTime(
    val startMs: Long,
    val endMs: Long,
    val word: String
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<WordTime> = emptyList()
)

object EnhancedLrcParser {

    fun parse(lrcText: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        lrcText.lines().forEach { rawLine ->
            val parsed = parseLine(rawLine)
            if (parsed != null) {
                lines.add(parsed)
            }
        }

        return lines.sortedBy { it.timeMs }
    }

    private fun parseLine(rawLine: String): LyricLine? {
        val lineMatch = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)").find(rawLine) ?: return null
        val min = lineMatch.groupValues[1].toLong()
        val sec = lineMatch.groupValues[2].toLong()
        val msStr = lineMatch.groupValues[3]
        val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
        val lineStartMs = (min * 60 * 1000) + (sec * 1000) + ms
        val content = lineMatch.groupValues[4].trim()

        if (content.isEmpty()) return null

        val wordTagRegex = Regex("<(\\d{2}):(\\d{2})\\.(\\d{2,3})>")
        val wordMatches = wordTagRegex.findAll(content).toList()

        if (wordMatches.isEmpty()) {
            return LyricLine(lineStartMs, content, emptyList())
        }

        val words = mutableListOf<WordTime>()
        val fullTextBuilder = StringBuilder()

        for (i in wordMatches.indices) {
            val currMatch = wordMatches[i]
            val wMin = currMatch.groupValues[1].toLong()
            val wSec = currMatch.groupValues[2].toLong()
            val wMsStr = currMatch.groupValues[3]
            val wMs = if (wMsStr.length == 2) wMsStr.toLong() * 10 else wMsStr.toLong()
            val wordStartMs = (wMin * 60 * 1000) + (wSec * 1000) + wMs

            val textStart = currMatch.range.last + 1
            val textEnd = if (i + 1 < wordMatches.size) wordMatches[i + 1].range.first else content.length
            val wordText = content.substring(textStart, textEnd)

            val wordEndMs = if (i + 1 < wordMatches.size) {
                val nextMatch = wordMatches[i + 1]
                val nM = nextMatch.groupValues[1].toLong()
                val nS = nextMatch.groupValues[2].toLong()
                val nMsStr = nextMatch.groupValues[3]
                val nMs = if (nMsStr.length == 2) nMsStr.toLong() * 10 else nMsStr.toLong()
                (nM * 60 * 1000) + (nS * 1000) + nMs
            } else {
                wordStartMs + 700
            }

            if (wordText.isNotEmpty()) {
                words.add(WordTime(wordStartMs, wordEndMs, wordText))
                fullTextBuilder.append(wordText)
            }
        }

        val cleanFullText = fullTextBuilder.toString().ifEmpty { content.replace(wordTagRegex, "") }
        return LyricLine(lineStartMs, cleanFullText, words)
    }
}

object WordHighlightHelper {

    fun formatHighlightedWordText(line: LyricLine, currentMs: Long): CharSequence {
        if (line.words.isEmpty()) {
            return line.text
        }

        val fullText = line.text
        val spannable = SpannableString(fullText)
        var charCursor = 0

        for (wordObj in line.words) {
            val word = wordObj.word
            val startIndex = fullText.indexOf(word, charCursor)
            if (startIndex < 0) continue
            val endIndex = startIndex + word.length
            charCursor = endIndex

            val color = when {
                currentMs >= wordObj.endMs -> Color.WHITE
                currentMs >= wordObj.startMs -> Color.parseColor("#1DB954")
                else -> Color.parseColor("#80FFFFFF")
            }

            spannable.setSpan(
                ForegroundColorSpan(color),
                startIndex,
                endIndex,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return spannable
    }
}
