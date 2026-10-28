package dev.horizonlab.fixture;

public enum Kind {
    STONE,
    DIRT;

    public boolean solid() {
        return this == STONE;
    }
}
