package com.jarves.mh.agent

class CooperativeSession(
    private val bus: MessageBus,
    private val store: SharedStateStore
) {
    suspend fun peerSync(peerId: AgentId, contextKey: String, value: String) {
        store.setContext("$peerId.$contextKey", value)
    }

    fun getContext(peerId: AgentId, contextKey: String): String? {
        return store.getContext("$peerId.$contextKey")
    }

    suspend fun jointIntegration(peerA: AgentId, peerB: AgentId): String {
        val contextA = store.getContext("$peerA.context") ?: ""
        val contextB = store.getContext("$peerB.context") ?: ""
        return "Integrated from $peerA: $contextA and $peerB: $contextB"
    }
}
