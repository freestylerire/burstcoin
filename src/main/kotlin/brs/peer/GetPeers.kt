package brs.peer

import brs.entity.DependencyProvider
import brs.objects.Constants
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal class GetPeers(private val dp: DependencyProvider) : PeerServlet.PeerRequestHandler {
    override fun processRequest(request: JsonObject, peer: Peer): JsonElement {
        val response = JsonObject()
        val peers = JsonArray()
        for (otherPeer in dp.peerService.allPeers) {
            val announcedAddress = otherPeer.address
            if (!otherPeer.isBlacklisted
                && otherPeer.state == Peer.State.CONNECTED
                && otherPeer.shareAddress
                && (announcedAddress.protocol == PeerAddress.Protocol.HTTP || peer.version.isGreaterThanOrEqualTo(Constants.NEW_PEER_API_MIN_VERSION))) {
                if (peer.version.isGreaterThanOrEqualTo(Constants.NEW_PEER_API_MIN_VERSION)) {
                    peers.add(announcedAddress.toString())
                } else {
                    peers.add("${announcedAddress.host}:${announcedAddress.port}")
                }
            }
        }
        response.add("peers", peers)

        return response
    }
}
