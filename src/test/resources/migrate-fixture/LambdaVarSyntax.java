package test;

import java.util.*;

/**
 * Lambda parameters without var can use (var x, var y) for type annotations
 */
public class LambdaVarSyntax {
    public void process() {
        List<String> names = Arrays.asList("a", "b", "c");

        // Without var - cannot add @NonNull
        names.forEach((s) -> System.out.println(s));

        // Multiple parameters
        Map<String, Integer> map = new HashMap<>();
        map.forEach((k, v) -> System.out.println(k + v));

        // More complex
        names.stream()
            .map((x) -> x.toUpperCase())
            .filter((s) -> s.length() > 0)
            .forEach((a, b) -> System.out.println(a + b));
    }
}