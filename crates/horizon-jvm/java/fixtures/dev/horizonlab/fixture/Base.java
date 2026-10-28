package dev.horizonlab.fixture;

/** A stand-in game class for the facade test: real bodies the facade must take out. */
public class Base {
    protected final String name;
    protected final long weight;

    public Base(String name, long weight) {
        // Would throw on the null the stub passes, if this body still ran.
        this.name = name.trim();
        this.weight = weight;
    }

    public int answer() {
        return 42;
    }

    public static double pi() {
        return Math.PI;
    }

    public String name() {
        return name;
    }
}
