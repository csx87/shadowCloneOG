import io.ktor.http.cio.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

data class sessionInfo(
    var uuid: UUID? = null,
    var session: DefaultWebSocketServerSession? = null
){

}

/**
 * Originally written by Artem Bagritsevich.
 *
 * https://github.com/artem-bagritsevich/WebRTCKtorSignalingServerExample
 */
object SessionManager {

    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    public val clients = mutableMapOf<UUID, DefaultWebSocketServerSession>()
    public val client_database = mutableMapOf<String,UUID>()
    public var ice_candidates:String? = "";
    public var number_of_ice = 0;

    private var sessionState: WebRTCSessionState = WebRTCSessionState.Impossible

    fun onSessionStarted(sessionId: UUID, session: DefaultWebSocketServerSession) {
        sessionManagerScope.launch {
            mutex.withLock {
                clients[sessionId] = session
                session.send("Added as a client: $sessionId")
                sessionState = WebRTCSessionState.Ready
                notifyAboutStateUpdate()
            }
        }
    }

    fun onMessage(sessionId: UUID, message: String) {
        when {
            message.startsWith(MessageType.NAME.toString(),true) -> handleName(sessionId,message)
            message.startsWith(MessageType.STATE.toString(), true) -> handleState(sessionId)
            message.startsWith(MessageType.OFFER.toString(), true) -> handleOffer(sessionId, message)
            //message.startsWith(MessageType.ANSWER.toString(), true) -> handleAnswer(sessionId, message)
            message.startsWith(MessageType.ICE.toString(), true) -> handleIce(sessionId, message)
        }
    }

    public fun findClient(name: String): UUID? {
        if(client_database.containsKey(name))
            if(clients.containsKey(client_database[name]))
                return client_database[name]
        return null
    }

    private fun handleName(sessionId: UUID,message: String){
        client_database[message.substringAfter(' ')] = sessionId;

        print(message)

    }

    private fun handleState(sessionId: UUID) {
        sessionManagerScope.launch {
            clients[sessionId]?.send("${MessageType.STATE} $sessionState")
        }
    }

    public fun handleOffer(sessionId: UUID, message: String) {
        if (sessionState != WebRTCSessionState.Ready) {
            error("Session should be in Ready state to handle offer")
        }
        sessionState = WebRTCSessionState.Active
        println("handling offer from $sessionId")
        notifyAboutStateUpdate()
        //val clientToSendOffer = clients.filterKeys { it != sessionId }.values.first()
        //clientToSendOffer.send(message)
        clients[sessionId]?.send(message)
    }

    private fun handleAnswer(sessionId: UUID, message: String) {
         if (sessionState != WebRTCSessionState.Creating) {
            error("Session should be in Creating state to handle answer")
        }
        println("handling answer from $sessionId")
        val clientToSendAnswer = clients.filterKeys { it != sessionId }.values.first()
        clientToSendAnswer.send(message)
        sessionState = WebRTCSessionState.Active
        notifyAboutStateUpdate()
    }

    private fun handleIce(sessionId: UUID, message: String) {
        println("handling ice from $sessionId")
        println(message)
        ice_candidates + "a=candidate:" + message.substringAfter(":")
        number_of_ice += 1;
        println("ICE candidateas: " + ice_candidates)
        //val clientToSendIce = clients.filterKeys { it != sessionId }.values.first()
        //clientToSendIce.send(message)
    }

    fun onSessionClose(sessionId: UUID) {
        sessionManagerScope.launch {
            mutex.withLock {
                clients.remove(sessionId)
                sessionState = WebRTCSessionState.Impossible
                notifyAboutStateUpdate()
            }
        }
    }

    enum class WebRTCSessionState {
        Active, // Offer and Answer messages has been sent
        Creating, // Creating session, offer has been sent
        Ready, // Both clients available and ready to initiate session
        Impossible // We have less than two clients
    }

    enum class MessageType {
        STATE,
        OFFER,
        ANSWER,
        ICE,
        NAME
    }

    private fun notifyAboutStateUpdate() {
        clients.forEach { (_, client) ->
            client.send("${MessageType.STATE} $sessionState")
        }
    }

    private fun DefaultWebSocketServerSession.send(message: String) {
        sessionManagerScope.launch {
            this@send.send(Frame.Text(message))
        }
    }
}
