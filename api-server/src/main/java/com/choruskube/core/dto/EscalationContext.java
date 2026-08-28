package com.choruskube.core.dto;

import java.util.UUID;

/**
 * Why a Supervisor gate is open and who opened it.
 *
 * <p>{@code category} and {@code summary} are parsed from the escalating node's
 * {@code escalation.md} front matter and are {@code null} when it is absent or unparseable;
 * that degrades the banner, never the gate.
 */
public record EscalationContext(
        String escalatorLabel, UUID escalatorExecId, String escalatorLoopGroup, String category, String summary) {}
