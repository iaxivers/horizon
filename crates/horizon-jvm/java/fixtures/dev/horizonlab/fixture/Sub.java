package dev.horizonlab.fixture;

import java.util.List;
import java.util.function.IntSupplier;

public class Sub extends Base implements Shape {
    private static final List<String> NAMES = List.of("a", "b");
    public static int counter = init();

    static int init() {
        return 7 + NAMES.size();
    }

    // A `new` among the super call's arguments: the stub must still find the super call.
    public Sub(int x) {
        super(new StringBuilder("s").append(x).toString(), x * 2L);
    }

    public Sub() {
        this(3);
    }

    @Override
    public int answer() {
        IntSupplier s = () -> 43;
        return s.getAsInt();
    }

    public long big(long a, double b) {
        switch ((int) a) {
            case 1:
                return 10;
            case 2:
                return 20;
            default:
                return (long) b + 30;
        }
    }
}
