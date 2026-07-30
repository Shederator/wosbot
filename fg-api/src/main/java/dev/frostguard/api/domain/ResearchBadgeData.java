package dev.frostguard.api.domain;

/**
 * Positioned progress badge read from a research-tree node.
 */
public record ResearchBadgeData(int currentLevel, int maximumLevel,
                                PointData center, double confidence) {}
