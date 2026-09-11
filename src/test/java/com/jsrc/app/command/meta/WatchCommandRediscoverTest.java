package com.jsrc.app.command.meta;

import com.jsrc.app.index.IndexedCodebase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Watch rediscover tests (W1-W4, W1s, W3s): stamp-driven refresh for create/delete/rename.
 * W1-W4 verify rediscovery logic via tryLoad count.
 * W1s/W3s verify symbols are queryable via index after create/delete.
 */
class WatchCommandRediscoverTest {

    private int tryLoadCallCount = 0;
    private IndexedCodebase mockIndex = null; // Will be the same instance for all tests

    /**
     * W1: Watch: create .java after start → next stamp check rediscovers files.
     */
    @Test
    void testW1_createJavaAfterStartRediscoversFiles(@TempDir Path tempDir) throws Exception {
        // Start with one file
        Path existing = tempDir.resolve("Existing.java");
        Files.writeString(existing, "public class Existing {}");

        tryLoadCallCount = 0;
        var watch = createInstrumentedWatchCommand(tempDir);

        // First load
        var result1 = watch.loadOrRefreshIndex(tempDir, List.of(existing), null, false);
        assertEquals(1, tryLoadCallCount, "First load should call tryLoad once");

        // Create new file
        Path newFile = tempDir.resolve("NewClass.java");
        Files.writeString(newFile, "public class NewClass {}");
        Thread.sleep(10); // Ensure mtime changes

        // Second load - should rediscover and reload
        var result2 = watch.loadOrRefreshIndex(tempDir, List.of(existing), result1.index(), false);
        assertEquals(2, tryLoadCallCount, "After creating file, should call tryLoad again");
        
        // Verify: discovery should find both files
        assertEquals(2, result2.files().size(), "Should discover both files");
    }

    /**
     * W2: Watch: modify known file → stamp changes, refresh triggered.
     */
    @Test
    void testW2_modifyKnownFileTriggersRefresh(@TempDir Path tempDir) throws Exception {
        Path javaFile = tempDir.resolve("Mutable.java");
        Files.writeString(javaFile, "public class Mutable {}");

        tryLoadCallCount = 0;
        var watch = createInstrumentedWatchCommand(tempDir);

        // First load
        var result1 = watch.loadOrRefreshIndex(tempDir, List.of(javaFile), null, false);
        assertEquals(1, tryLoadCallCount);

        // Modify file (change mtime)
        Thread.sleep(10);
        Files.writeString(javaFile, "public class Mutable { void newMethod() {} }");
        Thread.sleep(10);

        // Second load - should detect mtime change and reload
        var result2 = watch.loadOrRefreshIndex(tempDir, List.of(javaFile), result1.index(), false);
        assertEquals(2, tryLoadCallCount, "After modifying file, should call tryLoad again");
    }

    /**
     * W3: Watch: delete .java → rediscovery detects removal.
     */
    @Test
    void testW3_deleteJavaDetectedByRediscovery(@TempDir Path tempDir) throws Exception {
        Path victim = tempDir.resolve("Victim.java");
        Files.writeString(victim, "public class Victim {}");
        
        Path survivor = tempDir.resolve("Survivor.java");
        Files.writeString(survivor, "public class Survivor {}");

        tryLoadCallCount = 0;
        var watch = createInstrumentedWatchCommand(tempDir);

        // First load with both files
        var result1 = watch.loadOrRefreshIndex(tempDir, List.of(victim, survivor), null, false);
        assertEquals(1, tryLoadCallCount);
        assertEquals(2, result1.files().size());

        // Delete victim
        Files.delete(victim);
        Thread.sleep(10);

        // Second load - should rediscover and find only survivor
        var result2 = watch.loadOrRefreshIndex(tempDir, List.of(victim, survivor), result1.index(), false);
        assertEquals(2, tryLoadCallCount, "After deleting file, should call tryLoad again");
        
        assertEquals(1, result2.files().size(), "Should discover only survivor");
        assertTrue(result2.files().contains(survivor));
    }

    /**
     * W1s: After create .java in watch refresh path, new type is queryable via index.
     */
    @Test
    void testW1s_createJavaNewTypeQueryableViaIndex(@TempDir Path tempDir) throws Exception {
        // Start with one indexed file
        Path existing = tempDir.resolve("InitialClass.java");
        Files.writeString(existing, "package com.test; public class InitialClass { public void existingMethod() {} }");

        // Build initial index
        var watch = new WatchCommand();
        IndexedCodebase.tryLoad(tempDir, List.of(existing), false); // Force initial index
        
        var result1 = watch.loadOrRefreshIndex(tempDir, List.of(existing), null, false);
        assertNotNull(result1.index(), "Initial index should exist");
        
        // Verify InitialClass is queryable
        var initialClasses = result1.index().getAllClasses();
        assertTrue(initialClasses.stream().anyMatch(c -> c.name().equals("InitialClass")),
            "InitialClass should be queryable in initial index");

        // Create new file
        Path newFile = tempDir.resolve("NewlyCreated.java");
        Files.writeString(newFile, "package com.test; public class NewlyCreated { public void newMethod() {} }");
        Thread.sleep(10); // Ensure mtime changes

        // Refresh index - should rediscover and reindex
        var result2 = watch.loadOrRefreshIndex(tempDir, List.of(existing), result1.index(), false);
        assertNotNull(result2.index(), "Refreshed index should exist");
        
        // Oracle: NewlyCreated type must be queryable via index (W1s)
        var allClasses = result2.index().getAllClasses();
        assertTrue(allClasses.stream().anyMatch(c -> c.name().equals("NewlyCreated")),
            "W1s: After create, NewlyCreated type must be queryable via index");
        assertTrue(allClasses.stream().anyMatch(c -> c.name().equals("InitialClass")),
            "InitialClass should still be queryable");
        
        // Also verify via method search API
        var newMethods = result2.index().findMethodsByName("newMethod");
        assertFalse(newMethods.isEmpty(), "newMethod should be findable via index API");
        assertTrue(newMethods.stream().anyMatch(m -> m.className().equals("NewlyCreated")),
            "newMethod should be associated with NewlyCreated class");
    }

    /**
     * W3s: After delete, stale type not queryable via index.
     */
    @Test
    void testW3s_deleteJavaStaleTypeNotQueryable(@TempDir Path tempDir) throws Exception {
        Path victim = tempDir.resolve("VictimClass.java");
        Files.writeString(victim, "package com.test; public class VictimClass { public void victimMethod() {} }");
        
        Path survivor = tempDir.resolve("SurvivorClass.java");
        Files.writeString(survivor, "package com.test; public class SurvivorClass { public void survivorMethod() {} }");

        // Build initial index with both files
        var watch = new WatchCommand();
        IndexedCodebase.tryLoad(tempDir, List.of(victim, survivor), false);
        
        var result1 = watch.loadOrRefreshIndex(tempDir, List.of(victim, survivor), null, false);
        assertNotNull(result1.index());
        
        // Verify both classes are queryable initially
        var initialClasses = result1.index().getAllClasses();
        assertTrue(initialClasses.stream().anyMatch(c -> c.name().equals("VictimClass")),
            "VictimClass should be queryable initially");
        assertTrue(initialClasses.stream().anyMatch(c -> c.name().equals("SurvivorClass")),
            "SurvivorClass should be queryable initially");

        // Delete victim
        Files.delete(victim);
        Thread.sleep(10);

        // Refresh index - should detect deletion and reindex
        var result2 = watch.loadOrRefreshIndex(tempDir, List.of(victim, survivor), result1.index(), false);
        assertNotNull(result2.index());
        
        // Oracle: VictimClass must NOT be queryable (W3s)
        var finalClasses = result2.index().getAllClasses();
        assertFalse(finalClasses.stream().anyMatch(c -> c.name().equals("VictimClass")),
            "W3s: After delete, VictimClass must NOT be queryable via index");
        assertTrue(finalClasses.stream().anyMatch(c -> c.name().equals("SurvivorClass")),
            "SurvivorClass should still be queryable");
        
        // Also verify via method search API
        var victimMethods = result2.index().findMethodsByName("victimMethod");
        assertTrue(victimMethods.isEmpty(),
            "victimMethod should NOT be findable after delete");
        
        var survivorMethods = result2.index().findMethodsByName("survivorMethod");
        assertFalse(survivorMethods.isEmpty(),
            "survivorMethod should still be findable");
    }

    /**
     * Wctx: CommandContext receives fresh file list after rediscovery.
     */
    @Test
    void testWctx_commandContextUsesFreshFileList(@TempDir Path tempDir) throws Exception {
        Path initial = tempDir.resolve("Initial.java");
        Files.writeString(initial, "public class Initial {}");

        tryLoadCallCount = 0;
        var watch = createInstrumentedWatchCommand(tempDir);

        // First refresh with one file
        var result1 = watch.loadOrRefreshIndex(tempDir, List.of(initial), null, false);
        assertEquals(1, result1.files().size(), "Should have 1 file initially");
        assertTrue(result1.files().contains(initial));

        // Create second file
        Path added = tempDir.resolve("Added.java");
        Files.writeString(added, "public class Added {}");
        Thread.sleep(10);

        // Second refresh - should rediscover and include new file
        var result2 = watch.loadOrRefreshIndex(tempDir, List.of(initial), result1.index(), false);
        assertEquals(2, result2.files().size(), "Should have 2 files after rediscovery");
        assertTrue(result2.files().contains(initial));
        assertTrue(result2.files().contains(added), "Fresh file list must include newly created file");
    }

    /**
     * W4: frozen-index → no rediscovery refresh.
     */
    @Test
    void testW4_frozenIndexNoRediscoveryRefresh(@TempDir Path tempDir) throws Exception {
        Path javaFile = tempDir.resolve("Frozen.java");
        Files.writeString(javaFile, "public class Frozen {}");
        
        // W4 test is about frozen behavior WITHOUT a real index
        // The key contract: frozen-index with cached!=null should NOT call tryLoad again
        // We test this by checking tryLoad call count, not by verifying index content
        
        tryLoadCallCount = 0;
        mockIndex = null;
        var watch = new WatchCommand() {
            private IndexedCodebase fakeIndex = null;
            
            @Override
            protected IndexedCodebase callTryLoad(Path root, List<Path> files, boolean frozenIndex) {
                tryLoadCallCount++;
                // Return a stable fake index for frozen mode testing
                if (fakeIndex == null) {
                    // Use reflection to create a fake IndexedCodebase for testing
                    try {
                        var constructor = IndexedCodebase.class.getDeclaredConstructor(java.util.List.class);
                        constructor.setAccessible(true);
                        fakeIndex = constructor.newInstance(java.util.List.of());
                    } catch (Exception e) {
                        // Fallback: return null
                        return null;
                    }
                }
                return fakeIndex;
            }
        };

        // First load with frozenIndex=true
        var result1 = watch.loadOrRefreshIndex(tempDir, List.of(javaFile), null, true);
        assertEquals(1, tryLoadCallCount);

        // Modify file
        Thread.sleep(10);
        Files.writeString(javaFile, "public class Frozen { void added() {} }");
        Thread.sleep(10);

        // Second load with frozenIndex=true - should NOT reload
        var result2 = watch.loadOrRefreshIndex(tempDir, List.of(javaFile), result1.index(), true);
        assertEquals(1, tryLoadCallCount, "With frozen-index, should NOT call tryLoad again");
        
        // Verify we got the same cached instance back
        if (result1.index() != null) {
            assertSame(result1.index(), result2.index(), "Frozen index should return cached instance");
        }
    }

    private WatchCommand createInstrumentedWatchCommand(Path tempDir) {
        return new WatchCommand() {
            @Override
            protected IndexedCodebase callTryLoad(Path root, List<Path> files, boolean frozenIndex) {
                tryLoadCallCount++;
                // Try to load real index if exists, otherwise return null
                // This is sufficient for testing the frozen-index logic
                return IndexedCodebase.tryLoad(root, files, frozenIndex);
            }
        };
    }

}
