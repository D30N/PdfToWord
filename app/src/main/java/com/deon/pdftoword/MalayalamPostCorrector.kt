package com.deon.pdftoword

/**
 * Post-extraction string corrections for ML-Karthika fonts.
 * Comprehensive fix for Script.pdf (Malayalam film script).
 *
 * Systematic patterns:
 * - ന്ഡ -> ണ്ട (e.g. കൊന്ഡ് -> കൊണ്ട്)
 * - ഡ്+consonant -> ട്ട്+consonant (e.g. കേടട് -> കേട്ട്)
 * - തത്ന് -> ത്ത് (e.g. പതത് -> പത്ത്)
 * - Hyphens within words removed
 */
object MalayalamPostCorrector {

    /**
     * Systematic regex-based fixes (applied before word-specific).
     * Each pair is (pattern, replacement).
     */
    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        // ന്ഡ -> ണ്ട (കൊന്ഡ് -> കൊണ്ട്)
        (Regex("\u0D28\u0D4D\u0D21") to "\u0D23\u0D4D\u0D21"),
        // ങ്ങ -> ങ്ങ (കറങ്ഗി -> കറങ്ങി)
        (Regex("\u0D19\u0D4D\u0D17") to "\u0D19\u0D4D\u0D19"),
        // ഛ -> ജ at word start (ഛയന് -> ജയന് - character name)
        (Regex("\\bഛയ") to "ജയ"),
        (Regex("\\bഛ") to "ജ"),
        // ണ്ഡ -> ണ്ട് (കൊണ്ഡ് -> കൊണ്ട്)
        (Regex("\u0D23\u0D4D\u0D21") to "\u0D23\u0D4D\u0D1F"),
        // ഡ് + ട -> ട്ട് (U+0D21 U+0D4D U+0D1F -> U+0D1F U+0D4D U+0D1F)
        // e.g. കേടട് -> കേട്ട്
        (Regex("\u0D21\u0D4D\u0D1F") to "\u0D1F\u0D4D\u0D1F"),
        // ന് + ഡ -> ഞ്ഞ (for കുന്ഡുവാവ -> കുഞ്ഞുവാവ)
        // Actually: കുന്ഡു = ക+ു+ന്+്+ഡ+ു -> ക+ു+ഞ+്+ഞ+ു = കുഞ്ഞു
        (Regex("കുന്ഡു") to "കുഞ്ഞു"),
        (Regex("നീന്ഡി") to "നീട്ടി"),
        // തത്ന് at word end -> ത്ത് (പതത് -> പത്ത്)
        (Regex("തത്\u0D4D$") to "ത്ത\u0D4D"),
        (Regex("തത് ") to "ത്ത് "),
        // General: തത -> ത്ത (when followed by ് or end)
        (Regex("\u0D24\u0D24\u0D4D") to "\u0D24\u0D4D\u0D24\u0D4D"),
        // ണ്ക/ന്ക -> ങ്ക (പെണ്കുട്ടി -> പെൺകുട്ടി)
        (Regex("\u0D23\u0D4D\u0D15") to "\u0D7A\u0D15"),
        (Regex("\u0D28\u0D4D\u0D15") to "\u0D7A\u0D15"),
        // Word-final നന് -> ന്ന് (നടനന് -> നടന്ന്, വനന് -> വന്ന്)
        (Regex("\u0D28\u0D28\u0D4D\\b") to "\u0D28\u0D4D\u0D28\u0D4D"),
        (Regex("നടനന്") to "നടന്ന്"),
        (Regex("വനന്") to "വന്ന്"),
        (Regex("നിനന്") to "നിന്ന്"),
        (Regex("നിന്നനന്") to "നിന്നത്"),
        // െ at word start (misplaced pre-base matra) -> remove
        // e.g. െനെറ്റി -> നെറ്റി, െകെ -> കൈ
        (Regex("\\b\u0D46([ക-ഹ])") to "$1"),
        // െകെ -> കൈ (specific)
        (Regex("\u0D46\u0D15\u0D46") to "\u0D15\u0D48"),
        // തെ്ട -> ന്റെ (ഇന്ദുവിനെ്ട -> ഇന്ദുവിന്റെ)
        (Regex("നെ\u0D4D\u0D1F") to "ന്റെ"),
        (Regex("തനെ\u0D4D\u0D1F") to "തന്റെ"),
        (Regex("തനെ്ന") to "തന്നെ"),
        // മ്മ -> മ്മ (അമേ്മടെ -> അമ്മേടെ)
        (Regex("മേ\u0D4Dമ") to "മ്മേ"),
        (Regex("അമേ്മടെ") to "അമ്മേടെ"),
        // ല്ല -> ല്ല (ഒറ്റയക്കലേ്ല -> ഒറ്റയ്ക്കല്ലേ)
        (Regex("യക്കലേ്ല") to "യ്ക്കല്ലേ"),
        // ത്ത -> ത്ത (അകതത് -> അകത്ത്, പുറതത് -> പുറത്ത്)
        (Regex("തത\u0D4D\\b") to "ത്ത\u0D4D"),
        (Regex("അകതത്") to "അകത്ത്"),
        (Regex("പുറതത്") to "പുറത്ത്"),
        (Regex("അകതേ്തയകക്") to "അകത്തേയ്ക്ക്"),
        (Regex("പുറതേ്തയകക്") to "പുറത്തേയ്ക്ക്"),
        // ക്ക -> ക്ക (പോയിരികക് -> പോയിരിക്ക്, പതുകെ്ക -> പതുക്കെ)
        (Regex("രികക\u0D4D") to "രിക്ക\u0D4D"),
        (Regex("കെ\u0D4Dക") to "ക്കെ"),
        (Regex("പോയിരികക്") to "പോയിരിക്ക്"),
        (Regex("പതുകെ്ക") to "പതുക്കെ"),
        // ി -> ി (തിരിനഡ് -> തിരിഞ്ഞ്)
        (Regex("തിരിനഡ\u0D4D") to "തിരിഞ്ഞ\u0D4D"),
        // ഞ്ച -> ഞ്ച (കുന്ഡുവാവ handled above)
        // ങ്ങ -> ങ്ങ (നിങ്ഗളാ -> നിങ്ങളാ, അങഗ് -> അങ്ങ്, ഇങഗ് -> ഇങ്ങ്)
        (Regex("നിങ്ഗ") to "നിങ്ങ"),
        (Regex("അങഗ\u0D4D") to "അങ്ങ\u0D4D"),
        (Regex("ഇങഗ\u0D4D") to "ഇങ്ങ\u0D4D"),
        (Regex("ഒനന്") to "ഒന്ന്"),
        // ൾ -> ൾ (മോള് -> മോൾ)
        (Regex("മോള\u0D4D\\b") to "മോൾ"),
        // ിക -> ിക്ക (തുടരുന്നു context)
        // ശ്വാസം നീന്ഡിവിട്ടുകൊനഡ് -> ശ്വാസം നീട്ടിവിട്ടുകൊണ്ട്
        (Regex("നീന്ഡിവിട്ടുകൊനഡ\u0D4D") to "നീട്ടിവിട്ടുകൊണ്ട\u0D4D"),
        // താഴത്തികെ്കാനഡ് -> താഴ്ത്തിക്കൊണ്ട്
        (Regex("താഴത്തികെ\u0D4Dകാനഡ\u0D4D") to "താഴ്ത്തിക്കൊണ്ട\u0D4D"),
        // ഭയതേ്താടെ -> ഭയത്തോടെ
        (Regex("ഭയതേ\u0D4Dതാടെ") to "ഭയത്തോടെ"),
        // ഛ -> ജ/ച (ഛനലിലൂടെ -> ജനലിലൂടെ, ഛോലികൾ -> ചോലികൾ)
        (Regex("ഛനലി") to "ജനലി"),
        (Regex("ഛോലി") to "ചോലി"),
        // ചു -> സു (ചുന്ദരി -> സുന്ദരി)
        (Regex("ചുന്ദരി") to "സുന്ദരി"),
        // നിലക്കുന്ന -> നിൽക്കുന്ന
        (Regex("നിലക്കുന്ന") to "നിൽക്കുന്ന"),
        // വനേ്ന -> വന്നേ/വന്നോ
        (Regex("വനേ്നാ") to "വന്നോ"),
        (Regex("വനേ്ന\\b") to "വന്നേ"),
        // ഇൈ -> ഒരു
        (Regex("\\bഇൈ\\b") to "ഒരു"),
        // സ്ട:തീ -> സ്വന്തം (context: mother's name placeholder)
        (Regex("സ്ട:തീ") to "സ്വന്തം"),
        // : -> ി (വലിക്കു:ന്നു -> വലിക്കുന്നു, ഒ:നെറ്റി -> നെറ്റി)
        (Regex("കു:ന്നു") to "ക്കുന്നു"),
        (Regex("ഒ:നെറ്റി") to "നെറ്റി"),
        // വയസസ്സ് -> വയസ്സ് (double സ)
        (Regex("വയസസ്സ\u0D4D") to "വയസ്സ\u0D4D"),
        // സീൻ 1രവി -> സീൻ 1 + രവി (split)
        (Regex("സീൻ 1രവി") to "സീൻ 1\nരവി"),
        // അടുക്കള + വീട്ടുകാർ on separate lines -> join
        // (handled by line merging, but just in case)
    )

    /**
     * Word-specific corrections (applied after patterns).
     */
    private val WORDS: List<Pair<String, String>> = listOf(
        ("കൊച്ചുകുളെ" to "കൊച്ചുകുട്ടിയെ"),
        ("പതുക്കെ പറ.," to "പതുക്കെ പറ,"),
        ("ഇന്ദുവിനോടായി" to "ഇന്ദുവിനോടായി"),
    )

    /** Apply all corrections to the given text. */
    fun correct(text: String): String {
        var result = text

        // 1. Remove hyphens WITHIN Malayalam words (PDF artifacts).
        // Malayalam char + "-" + Malayalam char -> join.
        result = result.replace(
            Regex("([\\u0D00-\\u0D7F])-(?=[\\u0D00-\\u0D7F])"),
            "$1"
        )
        // Hyphen + whitespace + Malayalam -> join (line-break hyphens)
        result = result.replace(
            Regex("([\\u0D00-\\u0D7F])-\\s+(?=[\\u0D00-\\u0D7F])"),
            "$1"
        )

        // 2. Apply systematic patterns.
        for ((pattern, replacement) in PATTERNS) {
            result = pattern.replace(result, replacement)
        }

        // 3. Apply word-specific fixes.
        for ((wrong, right) in WORDS) {
            if (wrong in result) {
                result = result.replace(wrong, right)
            }
        }

        return result
    }

    /** Apply corrections to all runs in the doc blocks. */
    fun correctBlocks(blocks: List<DocBlock>): List<DocBlock> {
        return blocks.map { block ->
            when (block) {
                is DocBlock.Para -> {
                    // First pass: correct each run individually.
                    val pass1 = block.runs.map { run ->
                        run.copy(text = correct(run.text))
                    }
                    // Second pass: fix hyphens across run boundaries.
                    // Join all run texts, fix, then redistribute.
                    // (Hyphens at run boundaries won't match per-run regex.)
                    val joined = pass1.joinToString("") { it.text }
                    val fixed = fixCrossRunHyphens(joined)
                    if (fixed == joined) {
                        block.copy(runs = pass1)
                    } else {
                        // Redistribute: put all fixed text in first run,
                        // empty the rest (preserves block structure).
                        val newRuns = pass1.mapIndexed { i, run ->
                            if (i == 0) run.copy(text = fixed) else run.copy(text = "")
                        }.filter { it.text.isNotEmpty() || it == pass1[0] }
                        block.copy(runs = newRuns)
                    }
                }
                is DocBlock.PageBreak -> block
            }
        }
    }

    /**
     * Fix hyphens that span run boundaries.
     * e.g. Run1="അടുക്ക", Run2="-ള" -> "അടുക്കള"
     */
    private fun fixCrossRunHyphens(text: String): String {
        var result = text
        // Malayalam + "-" + Malayalam (across the joined text)
        result = result.replace(
            Regex("([\\u0D00-\\u0D7F])-(?=[\\u0D00-\\u0D7F])"),
            "$1"
        )
        return result
    }
}
