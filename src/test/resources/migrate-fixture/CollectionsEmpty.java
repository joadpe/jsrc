package test;

import java.util.*;

/**
 * Collections.empty*() should be replaced with List.of(), Map.of(), Set.of()
 */
public class CollectionsEmpty {
    public List<String> getEmptyList() {
        return Collections.emptyList();
    }

    public Map<String, Integer> getEmptyMap() {
        return Collections.emptyMap();
    }

    public Set<Integer> getEmptySet() {
        return Collections.emptySet();
    }

    public void doSomething() {
        List<String> names = Collections.emptyList();
        Map<String, Object> props = Collections.emptyMap();
    }
}