package com.nousresearch.hermes.data

import com.nousresearch.hermes.protocol.CronJob
import com.nousresearch.hermes.protocol.ProfileInfo

private val BOT_ROUTINE_TAG = Regex("^\\[bot:([a-z0-9][a-z0-9_-]*)]\\s*", RegexOption.IGNORE_CASE)
internal val BOT_ROUTINE_NAME = Regex("^\\[bot:[a-z0-9][a-z0-9_-]*]\\s+.+", RegexOption.IGNORE_CASE)
private val MENTION = Regex("(^|\\s)@([a-z0-9](?:[a-z0-9._-]*[a-z0-9])?)", RegexOption.IGNORE_CASE)

internal fun CronJob.botRoutineOwner(): String? = BOT_ROUTINE_TAG.find(name.orEmpty())?.groupValues?.get(1)?.lowercase()

internal fun CronJob.botRoutineTitle(): String = name.orEmpty().replace(BOT_ROUTINE_TAG, "").ifBlank { "Untitled routine" }

internal fun botRoutineName(owner: String, title: String): String {
    val profile = owner.trim().lowercase()
    require(profile.matches(Regex("[a-z0-9][a-z0-9_-]*"))) { "Invalid Bot routine owner" }
    return "[bot:$profile] ${title.trim().ifBlank { "Untitled routine" }}"
}

internal data class BotMention(
    val profile: String,
    val backendId: String,
    val handle: String,
)

internal data class BotMentionToken(val start: Int, val query: String)

internal fun botMentionBaseHandle(profile: ProfileInfo): String =
    profile.displayName.ifBlank { profile.name }.lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim { it !in 'a'..'z' && it !in '0'..'9' }
        .ifBlank { profile.name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-') }

internal fun botMentionToken(text: String): BotMentionToken? {
    val match = Regex("(^|\\s)@([a-z0-9._-]*)$", RegexOption.IGNORE_CASE).find(text) ?: return null
    return BotMentionToken(match.range.last - match.groupValues[2].length, match.groupValues[2])
}

internal fun completeBotMention(text: String, token: BotMentionToken, handle: String): String =
    text.replaceRange(token.start, text.length, "@$handle ")

internal fun shouldNotifyBotEvent(hidden: Boolean, initialized: Boolean, changed: Boolean, open: Boolean = false): Boolean =
    initialized && changed && !hidden && !open

internal fun resolveBotMentions(
    text: String,
    candidates: List<BotMention>,
    senderProfile: String,
    senderBackendId: String,
): List<BotMention> {
    val byHandle = candidates.groupBy { it.handle.lowercase() }.filterValues { it.size == 1 }.mapValues { it.value.single() }
    return MENTION.findAll(text).mapNotNull { match -> byHandle[match.groupValues[2].lowercase()] }
        .filterNot {
            it.profile.equals(senderProfile, ignoreCase = true) && it.backendId.equals(senderBackendId, ignoreCase = true)
        }
        .distinctBy { "${it.backendId.lowercase()}::${it.profile.lowercase()}" }
        .toList()
}

internal enum class BotScheduleFrequency { ONCE, HOURLY, DAILY, WEEKDAYS, WEEKLY, MONTHLY, INTERVAL, ADVANCED }

internal data class BotSchedule(
    val frequency: BotScheduleFrequency = BotScheduleFrequency.DAILY,
    val hour: Int = 9,
    val minute: Int = 0,
    val weekday: Int = 1,
    val monthDay: Int = 1,
    val amount: Int = 1,
    val unit: Char = 'h',
    val raw: String = "",
) {
    fun expression(): String = when (frequency) {
        BotScheduleFrequency.ONCE -> "${amount.coerceAtLeast(1)}${unit.takeIf { it in "mhd" } ?: 'h'}"
        BotScheduleFrequency.HOURLY -> "every 1h"
        BotScheduleFrequency.DAILY -> "$minute $hour * * *"
        BotScheduleFrequency.WEEKDAYS -> "$minute $hour * * 1-5"
        BotScheduleFrequency.WEEKLY -> "$minute $hour * * ${weekday.coerceIn(0, 6)}"
        BotScheduleFrequency.MONTHLY -> "$minute $hour ${monthDay.coerceIn(1, 31)} * *"
        BotScheduleFrequency.INTERVAL -> "every ${amount.coerceAtLeast(1)}${unit.takeIf { it in "mhd" } ?: 'h'}"
        BotScheduleFrequency.ADVANCED -> raw.trim()
    }

    companion object {
        fun parse(value: String): BotSchedule {
            val schedule = value.trim()
            Regex("^(\\d+)([mhd])$").matchEntire(schedule)?.let {
                return BotSchedule(BotScheduleFrequency.ONCE, amount = it.groupValues[1].toInt(), unit = it.groupValues[2].single())
            }
            Regex("^every (\\d+)([mhd])$").matchEntire(schedule)?.let {
                val amount = it.groupValues[1].toInt()
                val unit = it.groupValues[2].single()
                return BotSchedule(if (amount == 1 && unit == 'h') BotScheduleFrequency.HOURLY else BotScheduleFrequency.INTERVAL, amount = amount, unit = unit)
            }
            Regex("^(\\d{1,2}) (\\d{1,2}) (\\*|\\d{1,2}) \\* (\\*|1-5|[0-6])$").matchEntire(schedule)?.let {
                val minute = it.groupValues[1].toInt()
                val hour = it.groupValues[2].toInt()
                if (minute in 0..59 && hour in 0..23) {
                    val monthDay = it.groupValues[3]
                    val weekday = it.groupValues[4]
                    return when {
                        monthDay != "*" && weekday != "*" -> BotSchedule(BotScheduleFrequency.ADVANCED, raw = schedule)
                        monthDay != "*" -> BotSchedule(BotScheduleFrequency.MONTHLY, hour, minute, monthDay = monthDay.toInt())
                        weekday == "1-5" -> BotSchedule(BotScheduleFrequency.WEEKDAYS, hour, minute)
                        weekday != "*" -> BotSchedule(BotScheduleFrequency.WEEKLY, hour, minute, weekday = weekday.toInt())
                        else -> BotSchedule(BotScheduleFrequency.DAILY, hour, minute)
                    }
                }
            }
            return BotSchedule(BotScheduleFrequency.ADVANCED, raw = schedule)
        }
    }
}
