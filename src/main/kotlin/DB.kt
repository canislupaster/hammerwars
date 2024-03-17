package win.hammerwars

import io.jooby.Environment
import io.jooby.StatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.json
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.io.path.absolutePathString

val SESSION_EXPIRE: Duration = Duration.ofDays(2)
val OAUTH_EXPIRE: Duration = Duration.ofMinutes(5)
const val MAX_TEAM_SIZE = 3

enum class WebErrorType {
    NotFound,
    Unauthorized,
    NoUser,
    BadRequest,
    BadEmail,
    FormError,
    Other,
    JudgingError,
    RegistrationClosed;

    fun message(): String = when(this) {
        NotFound -> "Not found"
        Unauthorized -> "Unauthorized"
        NoUser -> "No user found associated with this session. You probably didn't finish logging in."
        BadRequest -> "Bad request"
        JudgingError -> "Judge error"
        BadEmail -> "Invalid email"
        FormError -> "Invalid form input"
        Other -> "Unknown error"
        RegistrationClosed -> "Registration has closed"
    }

    fun code(): StatusCode = when(this) {
        NotFound -> StatusCode.NOT_FOUND
        NoUser, Unauthorized -> StatusCode.UNAUTHORIZED
        JudgingError, BadEmail, BadRequest, FormError -> StatusCode.BAD_REQUEST
        RegistrationClosed -> StatusCode.FORBIDDEN
        Other -> StatusCode.SERVER_ERROR
    }

    fun err(msg: String?=null): Nothing = throw WebError(this, msg)
}

open class WebError(val ty: WebErrorType, val msg: String?=null): Exception(msg ?: ty.message())

fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
fun String.base64(): ByteArray = Base64.getDecoder().decode(this)

@Serializable
enum class Pizza {
    Cheese,
    Pepperoni,
    Meat,
    Veggie,
    None
}

@Serializable
enum class ShirtSize {
    S,
    M,
    L,
    XL,
    XXL,
    None
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

object Grid: KSerializer<List<List<Boolean>>> {
    fun encode(value: List<List<Boolean>>) = value.joinToString("\n") {
        it.joinToString("") { v -> if (v) "1" else "0" }
    }

    fun decode(s: String) = s.split(*arrayOf("\r\n","\n")).map { it.map { v -> v=='1' } }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Grid", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: List<List<Boolean>>) = encoder.encodeString(encode(value))
    override fun deserialize(decoder: Decoder): List<List<Boolean>> = decode(decoder.decodeString())
}

fun convertTime(time: Instant): String =
    DateTimeFormatter.ofPattern("MMMM d 'at' h:mm a 'ET'")
        .format(LocalDateTime.ofInstant(time, ZoneId.of("America/New_York")))

val DEFAULT_GRID = Grid.decode("""
    00000000000000000000000000000000000000000000000000000
    00000000000000000000000000000000000000000000000000000
    00000000000000000000000000000000000000000000000000000
    00000000000000000000000000000000000000000000000000000
    00000000000000000000000000000000000000000000000000000
    00000000000000000000000000000000000000000000000000000
    00000000000000000000000000000000000000000000000000000
    00000000000000000000000000000000000000000000000000000
    00000000000000000000000000000000000000000000000000000
    00000000000000000000000000000000000000000000000000000
    00000000000000000000000000000000000000000000000000000
    00000011111100000000000000000000000000000111111000000
    00001100000011000000000000000000000000011000000110000
    00010000000000100000000000000000000000100000000001000
    00100000000000010000000000000000000001000000000000100
    00100000000000010000000000000000000001000000000000100
    01000000000000001000000000000000000010000000000000010
    01000000000000001000000000000000000010000000000000010
    01000000000000001000000000000000000010000000000000010
    01000000000000001000000000000000000010000000000000010
    01000000000000001000000000000000000010000000000000010
    01000000000000001000000000000000000010000000000000010
    00100000000000010000000000000000000001000000000000100
    00100000000000010000000000000000000001000000000000100
    00010000000000100000000000000000000000100000000001000
    00001100000011000000000000000000000000011000000110000
    00000011111100000000000000000000000000000111111000000
    11111111111111111111111111111111111111111111111111111
""".trimIndent())

val DRAWING_HEIGHT = DEFAULT_GRID.size
val DRAWING_WIDTH = DEFAULT_GRID[0].size
const val TEXT_MAXLEN: Int = 1000
const val NAME_LEN: Int = 30

fun String.checkName() {
    if (length !in 1..NAME_LEN || !all { it.isLetterOrDigit() || it==' ' })
        WebErrorType.FormError.err("Your name and team name should be between 1 and $NAME_LEN characters long and only contain letters, numbers, and spaces")
}

@Serializable
data class UserData(
    val name: String?,
    val lookingForTeam: Boolean=false,
    val cfHandle: String?=null,
//    val cfTeam: String?=null,
    val ans: String?=null,

    @Serializable(with=Grid::class)
    val drawing: List<List<Boolean>> = DEFAULT_GRID,

    val pizza: Pizza=Pizza.None,
    val randomNumber: String?=null,
    val enjoyment: Double=50.0,
    val shirt: ShirtSize=ShirtSize.None,

    @Serializable(with=InstantSerializer::class)
    val time: Instant=Instant.now()
) {
    fun isComplete() =
        arrayOf<Any?>(name, ans, randomNumber).all { it!=null }

    fun checkFields() {
        if (arrayOf(name, cfHandle, randomNumber).any {
            it!=null && it.length !in 1..TEXT_MAXLEN
        } || ans!=null && ans.length !in 1..10)
            WebErrorType.FormError.err("Text is too long")

        name?.checkName()

        if (drawing.size!=DRAWING_HEIGHT || drawing.any { it.size!=DRAWING_WIDTH })
            WebErrorType.FormError.err("Invalid drawing")

        if (enjoyment<0.0 || enjoyment>100.0)
            WebErrorType.FormError.err("Invalid enjoyment")
    }
}

fun dataFromForm(form: Map<String, String>) =
    runCatching {
        val formB = form.mapValues { (_,v) -> v.ifBlank { null }?.trim() }

        val dat = UserData(
            formB["name"],
            formB.containsKey("lookingForTeam"),
            formB["cfHandle"],
//            formB["cfTeam"],
            formB["ans"],
            Grid.decode(form["drawing"]!!),
            Pizza.valueOf(formB["pizza"]!!),
            formB["randomNumber"],
            formB["enjoyment"]!!.toDouble(),
            ShirtSize.valueOf(formB["shirt"]!!)
        )

        dat.checkFields()
        dat
    }.getOrElse {
        when (it) {
            is WebError -> throw it
            else -> WebErrorType.FormError.err()
        }
    }

@Serializable
data class UserInfo(
    val id: Int,
    val num: Int,
    val email: String,
    @Serializable(with=InstantSerializer::class)
    val lastSaved: Instant,
    val submitted: UserData?,
    val teamId: Int,
    val accepted: Boolean
)

@Serializable
data class TeamInfo(
    val id: Int,
    val code: String,
    val name: String
)

data class Dashboard(
    val closed: Boolean,
    val inProgress: Boolean,
    val locked: Boolean,
    val scoreboard: String?,
    val maxSubmissions: Int,
    val numSubmissions: Int,
    val users: List<UserInfo>
)

class DB(dir: String, env: Environment) {
    val db: Database = Path.of(dir, env.getProperty("db")
        ?: throw IllegalArgumentException("No db path provided")).let {
       Database.connect("jdbc:sqlite:${it.absolutePathString()}?foreign_keys=on", "org.sqlite.JDBC")
    }

    val maxSubmissions = env.getProperty("maxSubmissions")!!.toInt()
    val open: Instant = Instant.parse(env.getProperty("opens")!!)
    val adminEmails = env.config.getStringList("admin")!!.toSet()

    val rng = SecureRandom()

    init {
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(User,Session,Props,Submission,TeamProblem)
        }
    }

    private suspend fun<T> query(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction<T>(Dispatchers.IO,db,statement=block)

    object User: Table(name="user") {
        val id: Column<Int> = integer("id").autoIncrement()
        val num: Column<Int> = integer("num").uniqueIndex()
        val team: Column<Int> = integer("team").index().references(Team.id)

        val cfHandle: Column<String?> = text("cfHandle").uniqueIndex().nullable()
        val email: Column<String> = text("email").uniqueIndex()

        val savedData: Column<UserData> = json("saved_data", Json.Default)
        val data: Column<UserData?> = json<UserData>("data", Json.Default).nullable()

        val accepted: Column<Boolean> = bool("accepted").default(false)

        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    object Props: Table(name="props") {
        val name: Column<String> = text("name")
        val value: Column<String> = text("value")

        override val primaryKey: PrimaryKey = PrimaryKey(name)
    }

    object Session: Table(name="session") {
        val id: Column<String> = text("id")
        val userId: Column<Int?> = integer("user_id").references(User.id, onDelete=ReferenceOption.SET_NULL).nullable()

        val key: Column<ExposedBlob> = blob("key")

        val expires: Column<Instant> = timestamp("expires")
        val oauthExpires: Column<Instant> = timestamp("oauth_expires")

        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    object Team: Table(name="team") {
        val id: Column<Int> = integer("id").autoIncrement()
        val code: Column<String> = text("code")
        val name: Column<String> = text("name")

        override val primaryKey = PrimaryKey(id)
    }

    object Submission: Table(name="submission") {
        val cfId: Column<Int> = integer("cf_id")
        //i dont even know why this is here?
        val team: Column<Int> = integer("team").references(Team.id, onDelete=ReferenceOption.CASCADE)

        override val primaryKey = PrimaryKey(cfId)
    }

    object TeamProblem: Table(name="team_problem") {
        val problem: Column<String> = text("problem")
        val team: Column<Int> = integer("team").references(Team.id, onDelete=ReferenceOption.CASCADE)
        val cfIdAC: Column<Int?> = integer("cfId").references(Submission.cfId, onDelete = ReferenceOption.SET_NULL).nullable()
        val runningTime: Column<Int> = integer("runtime")
        val pts: Column<Double> = double("pts")
        val stat: Column<SubmissionStat> = json("stat", Json.Default)

        override val primaryKey = PrimaryKey(problem, team)
    }

    fun genKey(): String {
        val key = ByteArray(32)
        rng.nextBytes(key)
        return key.base64()
    }

    fun genId(): String = UUID.randomUUID().toString()

    fun hash(data: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(data.toByteArray())
            digest()
        }

    data class MakeSession(val id: String, val key: String, val state: String)

    suspend fun makeSession(): MakeSession {
        val uid = genId()
        val ukey = genKey()
        val ukeyHash = hash(ukey)

        query {
            Session.insert {
                it[id] = uid
                it[key] = ExposedBlob(ukeyHash)
                it[expires] = Instant.now() + SESSION_EXPIRE
                it[oauthExpires] = Instant.now() + OAUTH_EXPIRE
            }
        }

        return MakeSession(uid, ukey, ukeyHash.base64())
    }

    suspend fun auth(id: String, key: String): SessionDB {
        val session = query {
            Session.selectAll()
                .where { (Session.id eq id) and (Session.expires greaterEq Instant.now()) }
                .singleOrNull()
        } ?: WebErrorType.Unauthorized.err("Session expired")

        val khash = hash(key)
        if (!MessageDigest.isEqual(hash(key), session[Session.key].bytes))
            WebErrorType.Unauthorized.err("Invalid session key")

         //should be ok to store state as string and do normal compare, etc
        return SessionDB(session[Session.id], session[Session.userId],
            khash.base64(), session[Session.oauthExpires])
    }

    // uhhh
    fun hasOpened() = Instant.now() > open

    private fun prop(name: String): String? =
        Props.select(Props.value).where { Props.name eq name }.singleOrNull()?.get(Props.value)

    suspend fun setProps(kv: Map<String,String>) = query {
        Props.batchUpsert(kv.entries, Props.name) { (k,v) ->
            this[Props.name] = k
            this[Props.value] = v
        }
    }

    suspend fun scoreboardConfig() = query {
        Json.decodeFromString<ScoreboardConfig>(prop("scoreboard")
            ?: throw IllegalStateException("No scoreboard config"))
    }

    suspend fun inProgress() = query { prop("inProgress")=="true" }

    private fun getUsersQ(): List<UserInfo> =
        User.selectAll().map {
            UserInfo(it[User.id], it[User.num], it[User.email],
                it[User.savedData].time, it[User.data],
                it[User.team], it[User.accepted])
        }

    suspend fun getUsers() = query {getUsersQ()}
    suspend fun getTeams() = query {
        Team.selectAll().map { TeamInfo(it[Team.id], it[Team.code], it[Team.name]) }
    }

    suspend fun getDashboard(): Dashboard = query {
        Dashboard(
            prop("closed")=="true",
            prop("inProgress")=="true",
            prop("locked")=="true",
            prop("scoreboard"), maxSubmissions,
            User.select(User.id).where { User.data.isNotNull() }.count().toInt(),
            getUsersQ()
        )
    }

    suspend fun setTeamsAccepted(teams: List<Int>, acc: Boolean) = query {
        User.update({ User.team inList teams }) {
            it[accepted] = acc
        }
    }

    suspend fun getInTeam(id: Int, other: Int?=null) = query {
        val op = User.team eq id
        User.select(User.email).where {
            if (other==null) op else op and (User.id neq other)
        }.map { it[User.email] }.toList()
    }

    suspend fun getTeamName(id: Int) = query {
        Team.select(Team.name).where { Team.id eq id }.single()[Team.name]
    }

    suspend fun submissionExists(cfId: Int) = query {
        !Submission.select(Submission.cfId).where { Submission.cfId eq cfId }.empty()
    }

    suspend fun makeSubmission(cfId: Int, cfHandle: String, problem: String,
                               fullSolve: Boolean, time: Instant,
                               runningTime: Int, score: (SubmissionStat) -> Double): List<SubmissionData> = query {
        val team = User.join(Team, JoinType.LEFT)
            .select(User.team, Team.name, User.email).where {
                (User.cfHandle eq cfHandle) and (User.accepted eq true)
            }.singleOrNull()

        if (team==null || (prop("inProgress")!="true" && !adminEmails.contains(team[User.email])))
            return@query emptyList()

        Submission.upsert {
            it[Submission.cfId] = cfId
            it[Submission.team] = team[User.team]
        }

        val (pstat,didAC) = TeamProblem.select(TeamProblem.stat, TeamProblem.cfIdAC).where {
            (TeamProblem.team eq team[User.team]) and (TeamProblem.problem eq problem)
        }.singleOrNull()?.let {
            it[TeamProblem.stat] to (it[TeamProblem.cfIdAC]!=null)
        } ?: (null to false)

        val fastest = TeamProblem
            .join(Team, JoinType.LEFT)
            .select(TeamProblem.runningTime.min(), TeamProblem.team, TeamProblem.stat, Team.name)
            .where {
                (TeamProblem.problem eq problem) and (TeamProblem.cfIdAC.isNotNull()) and
                        (TeamProblem.team neq team[User.team])
            }.singleOrNull()

        val prevFastest = fastest?.getOrNull(TeamProblem.team)?.let { teamId ->
            if (runningTime < fastest[TeamProblem.runningTime]) {
                val newStat = fastest[TeamProblem.stat].let {
                    SubmissionStat(false, it.order, it.numSub, it.time)
                }

                val newPts = score(newStat)
                TeamProblem.update({
                    (TeamProblem.problem eq problem) and (TeamProblem.team eq teamId)
                }) {
                    it[stat]=newStat
                    it[pts]=newPts
                }

                SubmissionData(teamId, fastest[Team.name], problem, newPts, true, newStat)
            } else null
        }

        val isFastest = fullSolve && (pstat?.fastest==true ||
                prevFastest!=null || fastest?.getOrNull(TeamProblem.team)==null)

        val order = TeamProblem.select(TeamProblem.team).where {
            (TeamProblem.cfIdAC less cfId) and (TeamProblem.problem eq problem)
        }.count().toInt().let { x->
            pstat?.order?.let { y->minOf(x,y) } ?: x
        }

        val numSub = pstat?.let {
            it.numSub + (if (!didAC && !fullSolve) 1 else 0)
        } ?: (if (fullSolve) 0 else 1)

        val stat = SubmissionStat(isFastest, order, numSub,
            pstat?.time?.let {minOf(it,time)} ?: time)

        val pts = if (fullSolve) score(stat) else 0.0

        TeamProblem.upsert(TeamProblem.team, TeamProblem.problem) {
            it[TeamProblem.team] = team[User.team]
            it[TeamProblem.problem] = problem
            //...
            it[cfIdAC]=if (fullSolve) cfId else null
            it[TeamProblem.runningTime]=runningTime
            it[TeamProblem.pts]=pts
            it[TeamProblem.stat]=stat
        }

        SubmissionData(team[User.team], team[Team.name], problem, pts, fullSolve, stat).let {
            if (prevFastest!=null) listOf(it, prevFastest) else listOf(it)
        }
    }

    suspend fun allBestSubmissions(): List<SubmissionData> = query {
        //special sqlite feature!
        TeamProblem.join(Team, JoinType.LEFT)
            .select(TeamProblem.columns + Team.name)
            .map {
                SubmissionData(it[TeamProblem.team], it[Team.name],
                    it[TeamProblem.problem], it[TeamProblem.pts],
                    it[TeamProblem.cfIdAC]!=null, it[TeamProblem.stat])
            }.toList()
    }

    suspend fun clearSubmissions() = query {
        Submission.deleteAll()
        TeamProblem.deleteAll()
    }

    data class SavedData(val saved: UserData, val submitted: UserData?,
                         val email: String, val teamCode: String, val teamName: String,
                         val teamWith: List<String>, val accepted: Boolean?)

    inner class SessionDB(val sid: String, val suid: Int?,
                          val state: String, val oauthExpire: Instant) {
        private suspend fun email() =
            User.select(User.email).where { User.id eq uid() }
                .singleOrNull()?.get(User.email)

        suspend fun checkAdmin() = query {
            if (!adminEmails.contains(email()))
                WebErrorType.Unauthorized.err("You aren't an administrator.")
        }

        suspend fun getTeam() = query {
            val u = User.select(User.team, User.accepted)
                .where { User.id eq uid() }.singleOrNull() ?: WebErrorType.NoUser.err()
            if (!u[User.accepted])
                WebErrorType.Unauthorized.err("You aren't in the contest!")
            u[User.team]
        }

        suspend fun withEmail(uEmail: String, name: String?): SessionDB = query {
            if (suid!=null)
                WebErrorType.BadRequest.err("Session already has user. Please logout first!")

            if (Instant.now() > oauthExpire)
                WebErrorType.Unauthorized.err("It's been too long since you tried logging in.")

            val u = User.select(User.id).where { User.email eq uEmail }.singleOrNull()

            val uid = if (u!=null) {
                Session.deleteWhere { (userId eq u[User.id]) and (id neq sid) }
                u[User.id]
            } else {
                val tc = genId()
                val tid = Team.insert {
                    it[code] = tc
                    it[Team.name] = name ?: uEmail
                }.resultedValues!!.first()[Team.id]

                User.insert {
                    it[email] = uEmail
                    it[num] = (User.select(num).maxOfOrNull { row -> row[num] } ?: 0) + 1
                    it[savedData] = UserData(name)
                    it[team] = tid
                }.resultedValues!!.first()[User.id]
            }

            Session.update({ Session.id eq sid }) { it[userId] = uid }
            SessionDB(sid, uid, state, oauthExpire)
        }

        private fun uid() = suid ?: WebErrorType.NoUser.err()
        private fun tid() = User.select(User.team).where { User.id eq uid() }.single()[User.team]

        suspend fun setData(newData: UserData, submit: Boolean=false, unsubmit: Boolean=false) {
            query {
                User.update({ User.id eq uid() }) {
                    it[savedData] = newData
                }
            }

            if (submit || unsubmit) query {
                if (submit && newData.cfHandle!=null
                    && User.select(User.id).where {User.cfHandle eq newData.cfHandle}
                        .singleOrNull()?.let { it[User.id]!=uid() }==true)
                    WebErrorType.FormError.err("My man, I didn't think this would actually happen, but someone already entered this Codeforces username. Please chat with us in the Discord to clear this up.")

                if (User.update({ User.id eq uid() }) {
                        if (submit) {
                            it[data] = newData
                            it[cfHandle] = newData.cfHandle
                        } else {
                            it[data] = null
                            it[cfHandle]=null
                        }
                    }==0)
                    WebErrorType.BadRequest.err("User not found")
            }
        }

        suspend fun isClosed(): Boolean = query {
            var crit = (User.id eq uid()) and User.data.isNotNull()

            if (prop("locked")=="true")
                crit = crit and (User.accepted eq true)

            (Instant.now() < open
                || User.select(User.id).where { User.data.isNotNull() }.count() >= maxSubmissions
                || prop("closed")=="true")
            && User.select(User.id).where { crit }
                .singleOrNull()==null
        }
        
        suspend fun getSavedData() = query {
            User.join(Team, JoinType.LEFT).select(User.columns + Team.code)
                .where { User.id eq uid() }.singleOrNull()
                ?.let { SavedData(
                    it[User.savedData], it[User.data],
                    it[User.email], it[Team.code], getTeamName(it[User.team]),
                    getInTeam(it[User.team], uid()),
                    if (prop("locked")=="true") it[User.accepted] else null
                ) }
                ?: WebErrorType.Other.err("User not found")
            }
        
        suspend fun leaveTeam() = query {
            val tc = genId()

            val rec = User.select(User.email, User.savedData, User.team)
                .where { User.id eq uid() }.single()

            val tid = Team.insert {
                it[code] = tc
                it[name] = rec[User.savedData].name ?: rec[User.email]
            }.resultedValues!!.first()[Team.id]

            User.update({ User.id eq uid() }) { it[team] = tid }

            if (User.select(User.id).where { User.team eq rec[User.team] }.empty())
                Team.deleteWhere { id eq rec[User.team] }
        }

        suspend fun joinTeam(code: String) = query {
            val acc = User.join(Team, JoinType.LEFT).select(User.accepted, Team.id, Team.code)
                .where { Team.code eq code }.toList()

            if (prop("inProgress")=="true" && acc.any { !it[User.accepted] })
                WebErrorType.BadRequest.err("Some people in this team haven't been accepted")

            val rec = acc.firstOrNull()
            if (rec==null) WebErrorType.NotFound.err("No team found with that code")
            else if (acc.size>=MAX_TEAM_SIZE) WebErrorType.BadRequest.err("Team is full")

            val curTeam = tid()
            User.update({ User.id eq uid() }) { it[team] = rec[Team.id] }

            if (User.select(User.id).where { User.team eq curTeam }.empty())
                Team.deleteWhere { Team.id eq curTeam }
        }

        suspend fun setTeamName(newName: String) = query {
            newName.checkName()
            Team.update({ Team.id eq tid() }) { it[name] = newName }
        }

        suspend fun remove() = query {
            Session.deleteWhere { id eq sid }
        }
    }
}
