package win.hammerwars

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.*
import java.nio.file.Files
import kotlin.random.Random

enum class Language {
    Python, Java, Cpp, JS;
    companion object {
        val ext = mapOf(
            Python to "py",
            Java to "java",
            JS to "js",
            Cpp to "cpp"
        )

        val fromExt = ext.entries.associate { (k,v) -> v to k }
    }
}

//MLE is just segfault lmao
@Serializable
enum class Verdict {
    TLE,INT,RE,CE,KILLED,XX;
    fun msg() = when (this) {
        TLE -> "Time Limit Exceeded"
        INT -> "Bad interaction"
        RE -> "Runtime Error"
        CE -> "Compilation Error"
        KILLED -> "Program Killed"
        XX -> "Unknown Error"
    }
}

class VerdictData(val v: Verdict, val out: String?=null):
    WebError(WebErrorType.JudgingError, "${v.toString()} ${v.msg()}")

fun verdict(v: Verdict, out: String?=null) = out?.trim().let {
    VerdictData(v, if (it.isNullOrEmpty()) null else it)
}

data class ProcResult(val out: String, val err: String, val code: Int)

suspend fun Process.wait(assert: Boolean=false): ProcResult = withContext(Dispatchers.IO) {
    val s = runCatching {String(inputStream.readAllBytes())}.getOrElse { "" }
    val e = runCatching {String(errorStream.readAllBytes())}.getOrElse { "" }
    val c = waitFor()
    if (assert && c!=0) WebErrorType.Other.err(e)
    ProcResult(s, e, c)
}

suspend fun Process.writeS(s: String) = withContext(Dispatchers.IO) {
    outputStream.write(s.toByteArray())
    outputStream.flush()
}

val READ_INTERVAL = 100L //ms
suspend fun Process.readS(tl: Int?, end: String) = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    var buf = StringBuffer()

    do {
        if (!isAlive)
            throw verdict(Verdict.RE, String(errorStream.readAllBytes()))

        buf.append(String(inputStream.readNBytes(inputStream.available())))
        if (buf.endsWith(end)) return@withContext buf.toString()
        delay(READ_INTERVAL)
    } while (tl==null || System.currentTimeMillis()-start<tl)

    throw verdict(Verdict.TLE, "output:\n${buf.toString().takeLast(500)}")
}

val SIZE_LIMIT = 50*1024 // KB
val MEM_LIMIT = 512*1024 // KB
val CPU_TIME = 10 // s
val INTERACT_TL = 1 // s
val COMPILE_TIME = 5 // s

class Team(val lang: Language, val code: String, val id: Int, val path: String, val isolate: Boolean) {
    val lock = Mutex()

    private var curProc: Process?=null
    private var verdict: VerdictData?=null
    private val metaFile = "$metaDir/team$id.txt"

    suspend fun setVerdict(v: VerdictData) = lock.withLock {
        verdict = v
        stop()
    }

    companion object {
        private val unusedBids = mutableSetOf<Int>()
        private var maxBid = 0
        private val mut = Mutex()

        val metaDir: String by lazy {
            val path = Files.createTempDirectory("hammerwars_game")
            Runtime.getRuntime().addShutdownHook(Thread {
                path.toFile().deleteRecursively()
            })

            path.toString()
        }

        suspend fun getNextBid(): Int = mut.withLock {
            if (unusedBids.isEmpty()) maxBid++
            else unusedBids.first().also { unusedBids.remove(it) }
        }

        suspend fun free(bid: Int) = mut.withLock {unusedBids.add(bid)}

        suspend fun makeTeam(lang: Language, code: String, isDev: Boolean): Team {
            val id = getNextBid()
            val path =
                if (!isDev)
                    "${ProcessBuilder("isolate", "-b", id.toString(), "--init")
                        .start().wait(true).out.trim()}/box"
                else Files.createTempDirectory("hammerwars_team${id}").toString()
            return Team(lang, code, id, path, !isDev)
        }
    }

    private fun runCmd(cmd: Array<String>, tl: Int?=null): Process =
        if (isolate) ProcessBuilder("isolate",
            "-b", id.toString(),
            "--time", CPU_TIME.toString(),
            "--mem", MEM_LIMIT.toString(),
            "--meta", metaFile,
            "--tty-hack",
            "--silent",
            *tl?.let { arrayOf("--wall-time", it.toString()) } ?: arrayOf(),
            "--fsize", SIZE_LIMIT.toString(),
            "--run", "--", *cmd)
            .start()
        else ProcessBuilder(*cmd).directory(File(path)).start()

    private fun checkMeta(compile: Boolean=false): VerdictData? =
        runCatching {
            File(metaFile).readLines()
        }.map { ls ->
            val map = ls.associate {
                val arr = it.split(":")
                arr[0] to arr[1]
            }

            val ty = if (compile) Verdict.CE else Verdict.RE

            when (map["status"]) {
                "RE" -> verdict(ty, map["message"])
                "TO" -> verdict(Verdict.TLE, map["message"])
                "SG" -> verdict(ty, "Killed on signal")
                else -> null
            }
        }.getOrNull()

    suspend fun compile() = lock.withLock {
        File("$path/main.${Language.ext[lang]}").writeText(code)

        val compileOut = when (lang) {
            Language.Java -> {
                runCmd(arrayOf("javac", "main.java"), COMPILE_TIME).wait()
            }
            Language.Cpp -> {
                runCmd(("g++ main.cpp -std=c++17 -O2 -Wall -Wextra" +
                        "-Wfloat-equal -Wduplicated-cond" +
                        "-Wlogical-op -o main")
                    .split(" ").toTypedArray(), COMPILE_TIME).wait()
            }
            else -> ProcResult("", "", 0)
        }

        if (compileOut.code!=0) setVerdict(
            if (compileOut.err.trim().isNotEmpty()) verdict(Verdict.CE, compileOut.err)
            else checkMeta(true) ?: verdict(Verdict.CE)
        )
    }

    suspend fun<T> interact(s: String, cb: suspend (String)->T?): T? {
        try {
            val p = lock.withLock {
                if (verdict!=null) return null

                if (curProc==null) curProc = runCmd(when (lang) {
                    Language.Java -> arrayOf("java", "main")
                    Language.Cpp -> arrayOf("./main")
                    Language.Python -> arrayOf("python3", "main.py")
                    Language.JS -> arrayOf("node", "main.js")
                })

                curProc!!
            }

            p.writeS(s)
            return cb(p.readS(INTERACT_TL*1000, "\n\n").trim())
        } catch (e: VerdictData) {
            setVerdict(checkMeta() ?: e)
        } catch (e: Exception) {
            setVerdict(checkMeta() ?: verdict(Verdict.XX, e.message))
        }

        return null
    }

    suspend fun verdict() = lock.withLock {verdict}

    suspend fun stop() {
        curProc?.destroyForcibly()
        curProc?.wait()
        curProc=null
    }

    suspend fun cleanup() = lock.withLock {
        stop()

        if (isolate) ProcessBuilder("isolate", "-b", id.toString(), "--cleanup")
            .start().wait(true)
        else File(path).deleteRecursively()

        free(id)
    }
}

class GameTeam(val t: Team, val pts: Int)

sealed interface GameMessage
class AddTeam(val t: Team): GameMessage
class RemoveTeam(val id: Int): GameMessage
class RunGame(): GameMessage

data class Proposal(val from: Int, val to: Int, val a: Int, val b: Int)

class Game {
    val teams = mutableMapOf<Int, GameTeam>()
    var running = false
    var curRound: Int? = null
    val lock = Mutex()

    val changes = Channel<GameMessage>(1000)
    val roundFinished = Channel<Unit>()

    suspend fun Team.interact1(pts: Map<Int, Int>): List<Proposal>? = pts.entries.let { ents ->
        interact("1 ${pts.size}\n${
            ents.joinToString("\n", postfix="\n") { (a,b) -> "$a $b" }
        }") {
            runCatching {
                val lines = it.split("\n")

                if (lines.size != pts.size) throw verdict(Verdict.INT)

                lines.zip(ents).map { (s, pt) ->
                    val vs = s.split(" ").map { v->v.toInt() }

                    if (vs.size!=2 || vs[0]>vs[1] || vs[0]<0 || vs[1]>100)
                        throw verdict(Verdict.INT)

                    Proposal(id, pt.key, vs[0], vs[1])
                }
            }.getOrElse { throw verdict(Verdict.INT, it.message) }
        }
    }

    suspend fun Team.interact2(proposals: List<Proposal>): Proposal? =
        interact("2 ${proposals.size}\n${
            proposals.joinToString("\n", postfix="\n") { it -> "${it.from} ${it.a} ${it.b}" }
        }") {
            val idx = it.toIntOrNull()
                ?: throw verdict(Verdict.INT, "Accepted proposal not an integer")

            when (val y = proposals.firstOrNull { a->a.from==idx }) {
                null -> throw verdict(Verdict.INT, "Accepted proposal not in list")
                else -> y
            }
        }

    suspend fun addTeam(lang: Language, code: String, isDev: Boolean): Team {
        if (code.length > SIZE_LIMIT)
            WebErrorType.BadRequest.err("Code too long")

        val t = Team.makeTeam(lang, code, isDev)
        t.compile()

        val ids = (1..100).map { Random.nextInt(0, 1000) }.distinct()

        //test interaction
        t.interact1(ids.associateWith { Random.nextInt(0,1000) })

        t.interact2(ids.map {
            val lo = Random.nextInt(0, 100)
            Proposal(it, -1, lo, Random.nextInt(lo + 1, 101))
        })

        t.lock.withLock {t.stop()}

        changes.send(AddTeam(t))
        return t
    }

    suspend fun removeTeam(t: Team) {
        t.setVerdict(verdict(Verdict.KILLED, "Team removed from game"))
        changes.send(RemoveTeam(t.id))
    }

    suspend fun remaining() = lock.withLock {
        teams.filter { it.value.t.verdict()==null }.let {
            if (it.size<=2) null else it
        }
    }

    suspend fun CoroutineScope.round(): Boolean {
        val rem = remaining() ?: return true

        val props = rem.map { (id, gt) -> async {
            gt.t.interact1(rem.filterNot { (id2,_) -> id2==id }.mapValues {it.value.pts}) ?: listOf()
        } }.awaitAll().flatten().groupBy { it.to }

        val accepted = (remaining() ?: return true).map { (id, gt) -> async {
            gt.t.interact2(props[id]!!)
        } }.awaitAll().mapNotNull { it }

        val accFrom = accepted.groupBy { it.from }
        val accTo = accepted.groupBy { it.to }

        lock.withLock {
            teams.replaceAll { id, gt ->
                GameTeam(gt.t, gt.pts
                        + (accFrom[id]?.sumOf {it.a} ?: 0)
                        + (accTo[id]?.sumOf {it.b} ?: 0))
            }
        }

        return false
    }

    suspend fun CoroutineScope.start() = launch {
        while (true) {
            when (val msg = changes.receive()) {
                is AddTeam -> {
                    lock.withLock {
                        teams.put(msg.t.id, GameTeam(msg.t, 0))
                    }?.t?.cleanup()

                    roundFinished.send(Unit)
                }
                is RemoveTeam -> {
                    lock.withLock { teams.remove(msg.id) }?.t?.cleanup()
                    roundFinished.send(Unit)
                }
                is RunGame -> {
                    val numRounds = Random.nextInt(100,500)

                    lock.withLock {
                        running=true
                        teams.replaceAll {_, gt -> GameTeam(gt.t, 0)}
                    }

                    for (i in 1..numRounds) {
                        lock.withLock { curRound=i }
                        if (round()) break
                        roundFinished.send(Unit)
                    }

                    lock.withLock {
                        running=false
                        teams.map { (_, gt) -> async {
                            gt.t.lock.withLock {gt.t.stop()}
                        } }.awaitAll()
                    }

                    roundFinished.send(Unit)
                }
            }
        }
    }
}