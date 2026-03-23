package com.jsrc.app.index;

/**
 * Compact migration suggestion stored in the index.
 *
 * @param patternId  index into MigrateCommand.MIGRATIONS list
 * @param line       source line number
 */
public record CachedMigration(int patternId, int line) {}
