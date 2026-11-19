package com.aicode.feature.workspace.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceRef(
    val backendInstanceId: String,
    val workspaceId: String
) {
    val stableKey: String
        get() = "${backendInstanceId.length}:$backendInstanceId${workspaceId.length}:$workspaceId"
}
