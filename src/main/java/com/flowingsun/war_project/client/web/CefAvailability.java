package com.flowingsun.war_project.client.web;

/**
 * Implemented by the Chromium backend so the service can ask whether it can run before it is used.
 * The string it returns is logged and shown in diagnostics, never thrown.
 */
public interface CefAvailability {
    boolean available();

    String unavailableReason();
}
