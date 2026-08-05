package com.openclaw.assistant.speech.voicevox

/**
 * VOICEVOX character information including credit notation.
 *
 * styleId values match the official VOICEVOX Core API (voicevox_vvm 0.16.x).
 * Do NOT use arbitrary sequential IDs — always refer to the official speaker manifest.
 */
data class VoiceVoxCharacter(
    val styleId: Int,
    val name: String,
    val styleName: String,
    val vvmFile: String,
    val creditNotation: String,
    val copyright: String,
    val termsUrl: String,
    val requiresCvCredit: Boolean = false
) {
    override fun toString(): String = "$name ($styleName)"

    fun getFullCredit(): String = creditNotation
}

/**
 * VOICEVOX character database.
 *
 * Style IDs are authoritative and must stay in sync with:
 *   - SettingsActivity.VoiceVoxCharacters.CHARACTERS (UI / character picker)
 *   - SettingsActivity.VoiceVoxCharacters.VVM_FILE_MAPPING (download management)
 *   - VoiceVoxProvider.getVvmFileNameForStyle() (synthesis)
 *
 * Source of truth: voicevox_vvm README (https://github.com/VOICEVOX/voicevox_vvm)
 * VVM version: 0.16.x
 */
object VoiceVoxCharacters {

    private val characters = listOf(
        // ── 0.vvm ── Shikoku Metan
        VoiceVoxCharacter(0,  "Shikoku Metan", "Sweet", "0",
            "VOICEVOX:四国めたん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),
        VoiceVoxCharacter(2,  "Shikoku Metan", "Normal",  "0",
            "VOICEVOX:四国めたん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),
        VoiceVoxCharacter(4,  "Shikoku Metan", "Sexy", "0",
            "VOICEVOX:四国めたん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),
        VoiceVoxCharacter(6,  "Shikoku Metan", "Tsundere", "0",
            "VOICEVOX:四国めたん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),

        // ── 0.vvm ── Zundamon
        VoiceVoxCharacter(1,  "Zundamon", "Sweet", "0",
            "VOICEVOX:ずんだもん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),
        VoiceVoxCharacter(3,  "Zundamon", "Normal",  "0",
            "VOICEVOX:ずんだもん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),
        VoiceVoxCharacter(5,  "Zundamon", "Sexy", "0",
            "VOICEVOX:ずんだもん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),
        VoiceVoxCharacter(7,  "Zundamon", "Tsundere", "0",
            "VOICEVOX:ずんだもん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),

        // ── 0.vvm ── Kasukabe Tsumugi
        VoiceVoxCharacter(8,  "Kasukabe Tsumugi", "Normal", "0",
            "VOICEVOX:春日部つむぎ", "© 春日部つむぎ",
            "https://tsumugi-official.studio.site/rule"),

        // ── 0.vvm ── Amehare Hau
        VoiceVoxCharacter(10, "Amehare Hau", "Normal", "0",
            "VOICEVOX:雨晴はう", "© 雨晴はう",
            "https://amehau.com/rules/amehare-hau-rule"),

        // ── 3.vvm ── Namine Ritsu
        VoiceVoxCharacter(9,  "Namine Ritsu", "Normal", "3",
            "VOICEVOX:波音リツ", "© カノンの落ちる城",
            "https://www.canon-voice.com/"),

        // ── 4.vvm ── Kurono Takehiro
        VoiceVoxCharacter(11, "Kurono Takehiro", "Normal",  "4",
            "VOICEVOX:玄野武宏", "© 玄野武宏 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84"),

        // ── 5.vvm ── Shikoku Metan whisper styles
        VoiceVoxCharacter(36, "Shikoku Metan", "Whisper", "5",
            "VOICEVOX:四国めたん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),
        VoiceVoxCharacter(37, "Shikoku Metan", "Hushed", "5",
            "VOICEVOX:四国めたん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),

        // ── 5.vvm ── Zundamon whisper styles
        VoiceVoxCharacter(22, "Zundamon", "Whisper", "5",
            "VOICEVOX:ずんだもん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),
        VoiceVoxCharacter(38, "Zundamon", "Hushed", "5",
            "VOICEVOX:ずんだもん", "© 東北ずん子プロジェクト",
            "https://zunko.jp/con_ongen_kiyaku.html"),

        // ── 9.vvm ── Shirakami Kotaro
        VoiceVoxCharacter(12, "Shirakami Kotaro", "Normal",   "9",
            "VOICEVOX:白上虎太郎", "© 白上虎太郎",
            "https://frontier.creatia.cc/fandoms/portal/creations/4"),
        VoiceVoxCharacter(32, "Shirakami Kotaro", "Joyful",   "9",
            "VOICEVOX:白上虎太郎", "© 白上虎太郎",
            "https://frontier.creatia.cc/fandoms/portal/creations/4"),
        VoiceVoxCharacter(33, "Shirakami Kotaro", "Nervous", "9",
            "VOICEVOX:白上虎太郎", "© 白上虎太郎",
            "https://frontier.creatia.cc/fandoms/portal/creations/4"),
        VoiceVoxCharacter(34, "Shirakami Kotaro", "Angry",     "9",
            "VOICEVOX:白上虎太郎", "© 白上虎太郎",
            "https://frontier.creatia.cc/fandoms/portal/creations/4"),
        VoiceVoxCharacter(35, "Shirakami Kotaro", "Crying", "9",
            "VOICEVOX:白上虎太郎", "© 白上虎太郎",
            "https://frontier.creatia.cc/fandoms/portal/creations/4"),

        // ── 10.vvm ── Kurono Takehiro additional styles
        VoiceVoxCharacter(39, "Kurono Takehiro", "Happy",     "10",
            "VOICEVOX:玄野武宏", "© 玄野武宏 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84"),
        VoiceVoxCharacter(40, "Kurono Takehiro", "Snappy", "10",
            "VOICEVOX:玄野武宏", "© 玄野武宏 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84"),
        VoiceVoxCharacter(41, "Kurono Takehiro", "Sad",   "10",
            "VOICEVOX:玄野武宏", "© 玄野武宏 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84"),

        // ── 15.vvm ── Aoyama Ryusei
        VoiceVoxCharacter(13, "Aoyama Ryusei", "Normal",  "15",
            "VOICEVOX:青山龍星", "© 青山龍星 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84"),
        VoiceVoxCharacter(81, "Aoyama Ryusei", "Passionate",     "15",
            "VOICEVOX:青山龍星", "© 青山龍星 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84"),
        VoiceVoxCharacter(82, "Aoyama Ryusei", "Grumpy",   "15",
            "VOICEVOX:青山龍星", "© 青山龍星 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84"),
        VoiceVoxCharacter(83, "Aoyama Ryusei", "Happy",     "15",
            "VOICEVOX:青山龍星", "© 青山龍星 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84"),
        VoiceVoxCharacter(84, "Aoyama Ryusei", "Calm", "15",
            "VOICEVOX:青山龍星", "© 青山龍星 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84"),
        VoiceVoxCharacter(85, "Aoyama Ryusei", "Sad", "15",
            "VOICEVOX:青山龍星", "© 青山龍星 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84"),
        VoiceVoxCharacter(86, "Aoyama Ryusei", "Whisper",     "15",
            "VOICEVOX:青山龍星", "© 青山龍星 (ViRVOX Project)",
            "https://virvoxproject.wixsite.com/official/voicevox%E5%88%A9%E7%94%A8%E8%A6%8F%E7%B4%84")
    )

    fun getAllCharacters(): List<VoiceVoxCharacter> = characters

    fun getCharacterByStyleId(styleId: Int): VoiceVoxCharacter? {
        return characters.find { it.styleId == styleId }
    }

    fun getCharactersByVvm(vvmFile: String): List<VoiceVoxCharacter> {
        return characters.filter { it.vvmFile == vvmFile }
    }

    /**
     * Get credit notations for all used characters
     */
    fun getCreditsForUsedCharacters(styleIds: List<Int>): List<VoiceVoxCredit> {
        return styleIds.mapNotNull { styleId ->
            getCharacterByStyleId(styleId)?.let { character ->
                VoiceVoxCredit(
                    characterName = character.name,
                    creditNotation = character.creditNotation,
                    copyright = character.copyright,
                    termsUrl = character.termsUrl
                )
            }
        }.distinctBy { it.creditNotation }
    }

    data class VoiceVoxCredit(
        val characterName: String,
        val creditNotation: String,
        val copyright: String,
        val termsUrl: String
    )
}
