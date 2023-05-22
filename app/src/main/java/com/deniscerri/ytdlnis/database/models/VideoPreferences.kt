package com.deniscerri.ytdlnis.database.models

data class VideoPreferences (
    var embedSubs: Boolean = true,
    var addChapters: Boolean = true,
    var splitByChapters: Boolean = false,
    var sponsorBlockFilters: ArrayList<String> = arrayListOf(),
    var writeSubs: Boolean = false,
    var subsLanguages: String = "en.*,.*-orig",
    var audioFormatIDs : ArrayList<String> = arrayListOf(),
    var removeAudio: Boolean = false
)
