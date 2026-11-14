package com.asdfg.soundmaster.audio

import org.json.JSONObject

/** Runtime observations of original third-party players, independent from requested configuration. */
data class RouteEvidence(val selected: Int, val others: Int, val unknown: Int, val wrong: Int) {
    val bothObserved: Boolean get() = selected > 0 && others > 0 && unknown == 0 && wrong == 0

    companion object {
        fun from(status: JSONObject, selectedUid: Int): RouteEvidence {
            val players = status.getJSONArray("players")
            var selected = 0; var others = 0; var unknown = 0; var wrong = 0
            for (index in 0 until players.length()) {
                val player = players.getJSONObject(index)
                val actual = player.getInt("actual")
                if (actual < 0) unknown++
                else if (actual != player.getInt("expected")) wrong++
                else if (player.getInt("uid") == selectedUid) selected++
                else others++
            }
            return RouteEvidence(selected, others, unknown, wrong)
        }
    }
}
