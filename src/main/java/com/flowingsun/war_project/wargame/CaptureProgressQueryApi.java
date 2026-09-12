package com.flowingsun.war_project.wargame;

import java.util.Optional;

public final class CaptureProgressQueryApi {
    private CaptureProgressQueryApi() {
    }

    public static Optional<NodeOccupationService.Progress> progress(String nodeId) {
        return NodeOccupationService.progress(nodeId);
    }
}
