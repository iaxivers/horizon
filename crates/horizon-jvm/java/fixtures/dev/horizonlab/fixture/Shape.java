package dev.horizonlab.fixture;

public interface Shape {
    default float area() {
        return 1.5f;
    }

    static Shape unit() {
        return new Sub();
    }
}
