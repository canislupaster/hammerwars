package win.hammerwars

import fuel.Fuel
import fuel.get
import io.jooby.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import kotlin.math.max

const val SCOREBOARD_UPDATE_INTERVAL = 10L //s
const val SCOREBOARD_UPDATE_COUNT = 15

@Serializable
data class ProblemConfig(val pts: Double)
@kotlinx.serialization.Serializable
data class ScoreboardConfig(val contestId: String, val problems: Map<String, ProblemConfig>,
                            val orderBonus: Double, val orderDecay: Double, val fastestBonus: Double,
                            val timePenalty: Double, val subPenalty: Double,
                            val gameProblemId: String, val gamePts: Double,
                            val startTime: Long, val endTime: Long)

//thanks chatgpt
@Serializable
data class Problem(
    val contestId: Int,
    val index: String,
    val name: String,
    val type: String?=null,
    val points: Int?=null,
    val rating: Int?=null,
    val tags: List<String>?=null
)

@Serializable
data class Member(
    val handle: String
)

@Serializable
data class Author(
    val contestId: Int?=null,
    val members: List<Member>,
    val participantType: String,
    val ghost: Boolean,
    val startTimeSeconds: Int
)

@Serializable
data class Submission(
    val id: Int,
    val contestId: Int?=null,
    val creationTimeSeconds: Long,
    val relativeTimeSeconds: Long,
    val problem: Problem,
    val author: Author,
    val programmingLanguage: String,
    val verdict: String,
    val testset: String,
    val passedTestCount: Int,
    val timeConsumedMillis: Int,
    val memoryConsumedBytes: Int
)

@Serializable
data class ContestStatus(
    val status: String,
    val comment: String? = null,
    val result: List<Submission>? = null
)

@Serializable
sealed interface ScoreboardMessage

@Serializable
data class SubmissionStat(val fastest: Boolean,
                          val order: Int, val numSub: Int,
                          @Serializable(with=InstantSerializer::class)
                          val time: Instant)

@Serializable
data class SubmissionData(val teamId: Int, val teamName: String, val problem: String, val pts: Double,
                          val fullSolve: Boolean, val stat: SubmissionStat): ScoreboardMessage

@Serializable
data class ProblemData(val id: String, val url: String, val pts: Double?)
@Serializable
data class ScoreboardInit(val problems: List<ProblemData>,
                          @Serializable(with=InstantSerializer::class)
                          val startTime: Instant,
                          @Serializable(with=InstantSerializer::class)
                          val endTime: Instant,
                          val submissions: List<SubmissionData>): ScoreboardMessage

class Scoreboard(val db: DB, val game: Game, val env: Environment, val log: Logger) {
    //code duplication is healthy
    val flow = MutableSharedFlow<ScoreboardMessage>()
    val mut = Mutex()

    val apiKey = env.getProperty("cfApiKey")!!
    val apiSecret = env.getProperty("cfApiSecret")!!
    val root = env.getProperty("root")!!

    val rng = SecureRandom()

    suspend fun req(contest: String, from: Int, count: Int?): ContestStatus {
        val chars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        val rand = (1..6).map { chars[rng.nextInt(chars.size)] }.joinToString("")

        val params = listOf(
            "contestId" to contest,
            "from" to from.toString(),
            "asManager" to "true",
            "apiKey" to apiKey,
            "time" to Instant.now().epochSecond.toString()
        ) + (if (count==null) emptyList() else listOf("count" to count.toString()))

        val qs = params.sortedWith { (k1, v1), (k2, v2) ->
            k1.compareTo(k2).let { if (it == 0) v1.compareTo(v2) else it }
        }.joinToString("&") {
            (k,v) -> "$k=$v"
        }

        val hash = MessageDigest.getInstance("SHA-512").run {
            update("$rand/contest.status?$qs#$apiSecret".toByteArray())
            digest()
        }.joinToString("") { String.format("%02x", it) }

        return Json {ignoreUnknownKeys=true}.decodeFromString<ContestStatus>(Fuel.get(
            "https://codeforces.com/api/contest.status",
            params + ("apiSig" to "$rand$hash")
        ).body)
    }

    suspend fun gameSubData(cfg: ScoreboardConfig) = game.lock.withLock {
        if (game.curRound==null || game.running) return emptyList<SubmissionData>()

        val avg = game.teams.entries.sumOf {it.value.pts}.toDouble()/game.teams.size.toDouble()

        game.teams.map { (id,gt) ->
            SubmissionData(id, db.getTeamName(id), cfg.gameProblemId,
                if (gt.pts==0) 0.0 else cfg.gamePts*gt.pts.toDouble()/avg,
                true,
                SubmissionStat(false, 0, 0, game.lastUpdate))
        }
    }

    suspend fun initData(): ScoreboardInit = db.scoreboardConfig().let {cfg ->
        if (Instant.now().epochSecond<cfg.startTime)
            WebErrorType.BadRequest.err("Contest hasn't started")

        ScoreboardInit(
            cfg.problems.map { (id, c) ->
                ProblemData(id, "https://codeforces.com/gym/${cfg.contestId}/problem/$id", c.pts)
            } + ProblemData(
                cfg.gameProblemId, "${root}/game",null
            ), Instant.ofEpochSecond(cfg.startTime), Instant.ofEpochSecond(cfg.endTime),
            db.allBestSubmissions() + gameSubData(cfg)
        )
    }

    suspend fun update(cfg: ScoreboardConfig, from: Int, count: Int?): List<SubmissionData> {
        val res = req(cfg.contestId, from, count)

        if (res.status!="OK" || res.comment!=null || res.result==null)
            throw IOException("CF API error: ${res.comment ?: "no info"}")

        return res.result.takeWhile { !db.submissionExists(it.id) }.reversed().flatMap {sub->
            val prob = cfg.problems[sub.problem.index]
            if (prob==null) {
                log.error("${sub.problem.index} not configured")
                return@flatMap emptyList()
            }

            if (sub.author.members.isEmpty()) {
                log.error("submission ${sub.id} has no authors, skipping")
                return@flatMap emptyList()
            }

            if (sub.creationTimeSeconds !in cfg.startTime..<cfg.endTime
                || sub.verdict=="COMPILATION_ERROR")
                return@flatMap emptyList()

            db.makeSubmission(sub.id, sub.author.members.first().handle,
                sub.problem.index, sub.verdict=="OK",
                Instant.ofEpochSecond(sub.creationTimeSeconds),
                sub.timeConsumedMillis
            ) { stat ->
                var x = prob.pts - cfg.subPenalty * stat.numSub
                x -= cfg.timePenalty * (stat.time.toEpochMilli() - cfg.startTime * 1000L).toDouble() / (1000.0 * 60)
                if (stat.fastest) x += cfg.fastestBonus
                x += max(0.0, cfg.orderBonus - cfg.orderDecay * stat.order)
                x
            }
        }
    }

    suspend fun clear() = mut.withLock {
        db.clearSubmissions()
        update(db.scoreboardConfig(), 1, null)
        flow.emit(initData())
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            game.flow.collect {
                gameSubData(db.scoreboardConfig()).forEach {flow.emit(it)}
            }
        }

        scope.launch {
            var lastFetch: Long? = null

            while (isActive) {
                try {
                    val now = Instant.now().epochSecond
                    val cfg = db.scoreboardConfig()

                    if (lastFetch==null || now >= lastFetch+SCOREBOARD_UPDATE_INTERVAL) {
                        lastFetch=now

                        var idx = 1
                        while (mut.withLock {update(cfg, idx, SCOREBOARD_UPDATE_COUNT).let {
                            it.forEach {sd -> flow.emit(sd)}
                            it.size == SCOREBOARD_UPDATE_COUNT
                        }})
                            idx += SCOREBOARD_UPDATE_COUNT
                    }

                    delay(1000*(lastFetch+SCOREBOARD_UPDATE_INTERVAL-now))
                } catch (e: Throwable) {
                    log.error("Can't update scoreboard", e)
                    delay(SCOREBOARD_UPDATE_INTERVAL*1000)
                }
            }
        }
    }
}