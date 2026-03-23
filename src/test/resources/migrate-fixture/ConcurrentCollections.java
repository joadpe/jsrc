package test;

import java.util.*;

/**
 * Collections.synchronized*() should be replaced with ConcurrentHashMap
 */
public class ConcurrentCollections {
    public Map<String, String> getSyncedMap() {
        return Collections.synchronizedMap(new HashMap<>());
    }

    public Set<Integer> getSyncedSet() {
        return Collections.synchronizedSet(new HashSet<>());
    }

    public List<String> getSyncedList() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    public Collection<String> getSyncedCollection() {
        return Collections.synchronizedCollection(new ArrayList<>());
    }

    private Map<String, Object> cache = Collections.synchronizedMap(new HashMap<>());
}