package dev.forgesworn.kithmoot.session

/** Generated from the original artwork bundled with KithMoot. */
data class OriginalArt(val slug: String, val title: String, val keywords: String, val pngBytes: Long, val gifBytes: Long)
val ORIGINAL_ART = listOf(
    OriginalArt("laugh", "Laugh", "laugh laughter lol funny tears lol", 202302L, 181899L),
    OriginalArt("facepalm", "Facepalm", "facepalm not again frustrated head wall not again", 186838L, 202473L),
    OriginalArt("mindblown", "Mind blown", "mind blown shocked wow surprise mind. blown.", 218758L, 199425L),
    OriginalArt("cool", "Cool", "cool sunglasses smug deal with it deal with it", 192505L, 194469L),
    OriginalArt("shrug", "Shrug", "shrug whatever dunno unsure dunno", 188096L, 201407L),
    OriginalArt("celebrate", "Celebrate", "celebrate party yes victory confetti yes!", 211548L, 166280L),
    OriginalArt("angry", "Angry", "angry furious rage fuming fuming", 205830L, 210310L),
    OriginalArt("love", "Love", "love heart hug thanks affection big love", 197197L, 189725L),
    OriginalArt("cry", "Cry", "cry sad tears sob upset send help", 206705L, 208522L),
    OriginalArt("sideeye", "Side eye", "side eye unimpressed sceptical doubt really?", 185589L, 187605L),
    OriginalArt("popcorn", "Popcorn", "popcorn drama watching waiting here for the drama", 198115L, 211360L),
    OriginalArt("micdrop", "Mic drop", "mic drop winner done nailed it mic drop", 202293L, 200200L),
    OriginalArt("thumbsup", "Thumbs up", "thumbs up approve yes good thanks nice one", 205917L, 208364L),
    OriginalArt("thumbsdown", "Thumbs down", "thumbs down no dislike nope nope", 189380L, 196826L),
    OriginalArt("slowclap", "Slow clap", "slow clap sarcastic applause brilliant brilliant", 197716L, 192025L),
    OriginalArt("eyeroll", "Eye roll", "eye roll bored annoyed unbelievable oh please", 189826L, 198432L),
    OriginalArt("waiting", "Waiting", "waiting impatient time clock hurry still waiting", 199975L, 215675L),
    OriginalArt("exhausted", "Exhausted", "exhausted dead tired done sleepy i am done", 200755L, 170187L),
    OriginalArt("wtf", "What", "wtf what confused baffled huh wtf", 205065L, 205238L),
    OriginalArt("melting", "Melting", "melting embarrassed cringe awkward this is fine", 192504L, 192286L),
    OriginalArt("plotting", "Plotting", "plotting evil cheeky grin mischievous heh heh", 196182L, 210422L),
    OriginalArt("moon", "To the moon", "moon rocket fly launch to the moon to the moon", 221611L, 196509L),
    OriginalArt("coffee", "Coffee", "coffee tired morning wake caffeine coffee first", 202751L, 5924981L),
    OriginalArt("handshake", "Handshake", "handshake agree deal friends respect deal", 195638L, 180119L),
)
val ORIGINAL_EMOJIS = ORIGINAL_ART.map { ":km_${it.slug}:" to it.keywords }
fun isOriginalEmoji(value: String): Boolean = ORIGINAL_EMOJIS.any { it.first == value }
