package win.hammerwars

import io.jooby.Environment
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
import kotlin.math.min

const val SCOREBOARD_UPDATE_INTERVAL = 10L //s
const val SCOREBOARD_UPDATE_COUNT = 50

@Serializable
data class ProblemConfig(val pts: Double)
@Serializable
data class ScoreboardConfig(val contestId: String, val problems: Map<String, ProblemConfig>,
                            val orderBonus: Double, val orderDecay: Double, val fastestBonus: Double,
                            val timePenalty: Double, val subPenalty: Double,
                            val gameProblemId: String, val gamePts: Double, val gameMaxPts: Double,
                            @Serializable(with=InstantSerializer::class)
                            val startTime: Instant,
                            @Serializable(with=InstantSerializer::class)
                            val endTime: Instant,
                            @Serializable(with=InstantSerializer::class)
                            val freezeTime: Instant?=null)

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
    val verdict: String?,
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
                          @Serializable(with=InstantSerializer::class)
                          val freezeTime: Instant?,
                          val submissions: List<SubmissionData>): ScoreboardMessage

class Scoreboard(val db: DB, val game: Game, val env: Environment, val http: HttpClient, val log: Logger) {
    //code duplication is healthy
    val flow = MutableSharedFlow<ScoreboardMessage>()
    val mut = Mutex()

    val apiKey = env.getProperty("cfApiKey")!!
    val apiSecret = env.getProperty("cfApiSecret")!!
    val contestUrl = env.getProperty("contestUrl")!!
    val root = env.getProperty("root")!!

    val rng = SecureRandom()
    val json = Json {ignoreUnknownKeys=true}

    suspend fun req(contest: String, batched: Boolean): Flow<Submission> = flow {
        var idx = 1
        do {
            val chars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
            val rand = (1..6).map { chars[rng.nextInt(chars.size)] }.joinToString("")

            log.info("fetching submissions from $idx (batched: $batched)")

            val params = listOf(
                "contestId" to contest,
                "from" to idx.toString(),
                "asManager" to "true",
                "apiKey" to apiKey,
                "time" to Instant.now().epochSecond.toString()
            ) + (if (batched) listOf("count" to SCOREBOARD_UPDATE_COUNT.toString()) else listOf())

            val qs = params.sortedWith { (k1, v1), (k2, v2) ->
                k1.compareTo(k2).let { if (it == 0) v1.compareTo(v2) else it }
            }.joinToString("&") {
                (k,v) -> "$k=$v"
            }

            val hash = MessageDigest.getInstance("SHA-512").run {
                update("$rand/contest.status?$qs#$apiSecret".toByteArray())
                digest()
            }.joinToString("") { String.format("%02x", it) }

            val res = http.get("https://codeforces.com/api/contest.status") {
                url {
                    (params + ("apiSig" to "$rand$hash"))
                        .forEach { (k,v) -> encodedParameters.append(k,v) }
                }
            }.body<ContestStatus>()

            if (res.status!="OK" || res.comment!=null || res.result==null)
                throw IOException("CF API error: ${res.comment ?: "no info"}")

            if (res.result.isEmpty()) break
            emitAll(res.result.asFlow())

            idx += SCOREBOARD_UPDATE_COUNT
        } while (currentCoroutineContext().isActive && batched)
    }

    suspend fun gameSubData(cfg: ScoreboardConfig) = game.lock.withLock {
        if (game.curRound==null || game.running) return emptyList<SubmissionData>()

        val avg = game.teams.entries.sumOf {it.value.pts}.toDouble()/game.teams.size.toDouble()

        game.teams.map { (id,gt) ->
            SubmissionData(id, db.getTeamName(id), cfg.gameProblemId,
                if (gt.pts==0) 0.0 else min(cfg.gameMaxPts, cfg.gamePts*gt.pts.toDouble()/avg),
                true,
                SubmissionStat(false, 0, 0, game.lastUpdate))
        }
    }

    suspend fun initData(): ScoreboardInit = db.scoreboardConfig().let {cfg ->
        val now = Instant.now()
        if (now<cfg.startTime)
            WebErrorType.BadRequest.err("Contest hasn't started")

        ScoreboardInit(
            cfg.problems.map { (id, c) ->
                ProblemData(id, "$contestUrl/${cfg.contestId}/problem/$id", c.pts)
            } + ProblemData(
                cfg.gameProblemId, "${root}/game",null
            ), cfg.startTime, cfg.endTime, cfg.freezeTime,
            db.allBestSubmissions() + gameSubData(cfg)
        )
    }

    suspend fun update(cfg: ScoreboardConfig, batched: Boolean, unfreeze: Boolean=false): Flow<SubmissionData> = flow {
        req(cfg.contestId, batched).map {
            it to Instant.ofEpochSecond(it.creationTimeSeconds)
        }.takeWhile { (sub,time)-> db.checkSubmission(sub.id, time) }
        .toList().reversed().takeWhile { it.first.verdict !in setOf("TESTING", null) }
        .forEach { (sub,time) ->
            db.markSubmission(sub.id, time)

            val prob = cfg.problems[sub.problem.index]
            if (prob==null) {
                log.error("${sub.problem.index} not configured")
                return@forEach
            }

            if (sub.author.members.isEmpty()) {
                log.error("submission ${sub.id} has no authors, skipping")
                return@forEach
            }

            if (time !in cfg.startTime..<cfg.endTime
                || (!unfreeze && cfg.freezeTime!=null && time>=cfg.freezeTime)
                || sub.verdict=="COMPILATION_ERROR")
                return@forEach

            emitAll(db.makeSubmission(sub.id, sub.author.members.first().handle,
                sub.problem.index, sub.verdict=="OK",
                time, sub.timeConsumedMillis
            ) { stat ->
                var x = prob.pts - cfg.subPenalty * stat.numSub
                x -= cfg.timePenalty * (stat.time.toEpochMilli() - cfg.startTime.toEpochMilli()).toDouble() / (1000.0 * 60)
                x = max(x, 0.0)

                if (stat.fastest) x += cfg.fastestBonus
                x += max(0.0, cfg.orderBonus - cfg.orderDecay * stat.order)
                x
            }.asFlow())
        }
    }

    suspend fun clear() = mut.withLock {
        db.clearSubmissions()
        update(db.scoreboardConfig(), false).collect()
        flow.emit(initData())
    }

    suspend fun unfreeze(): Unit = mut.withLock {
        val cfg = db.scoreboardConfig()
        if (cfg.freezeTime==null) return
        db.uncheckSince(cfg.freezeTime)

        update(cfg,false, unfreeze=true).collect()
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
                        mut.withLock { flow.emitAll(update(cfg, true)) }
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