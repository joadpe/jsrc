package com.jsrc.app.analysis;

import java.util.*;

import com.jsrc.app.command.CommandContext;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Resolves class metadata: DAO detection, field type resolution, class hierarchy.
 * Used by perf, security, flow, and debt commands.
 */
public class ClassResolver {

    private static Set<String> daoClassesCache;

    /**
     * Detects DAO classes by name, superclass, and interfaces.
     * Cached after first call.
     */
    public static Set<String> detectDaoClasses(CommandContext ctx) {
        if (daoClassesCache != null) return daoClassesCache;
        daoClassesCache = new HashSet<>();

        for (ClassInfo ci : ctx.getAllClasses()) {
            String name = ci.name();
            String qname = ci.qualifiedName();

            // Heuristic 1: class name
            if (name.endsWith("Dao") || name.endsWith("DAO")
                    || name.endsWith("Repository") || name.endsWith("Mapper")) {
                daoClassesCache.add(name);
                daoClassesCache.add(qname);
                continue;
            }

            // Heuristic 2: superclass
            String superClass = ci.superClass();
            if (superClass != null && !superClass.isEmpty()) {
                String superSimple = superClass.contains(".")
                        ? superClass.substring(superClass.lastIndexOf('.') + 1) : superClass;
                if (superSimple.contains("Dao") || superSimple.contains("DAO")
                        || superSimple.contains("Repository") || superSimple.contains("Mapper")
                        || superSimple.contains("JdbcTemplate") || superSimple.contains("JpaRepository")
                        || superSimple.contains("CrudRepository")) {
                    daoClassesCache.add(name);
                    daoClassesCache.add(qname);
                }
            }

            // Heuristic 3: interfaces
            for (String iface : ci.interfaces()) {
                String ifaceSimple = iface.contains(".")
                        ? iface.substring(iface.lastIndexOf('.') + 1) : iface;
                if (ifaceSimple.contains("Repository") || ifaceSimple.contains("Dao")
                        || ifaceSimple.contains("Mapper")) {
                    daoClassesCache.add(name);
                    daoClassesCache.add(qname);
                }
            }
        }

        return daoClassesCache;
    }

    /**
     * Checks if a class name is a known DAO class.
     */
    public static boolean isDaoClass(String className, CommandContext ctx) {
        return detectDaoClasses(ctx).contains(className);
    }

    /**
     * Resets the DAO cache (for testing).
     */
    public static void resetCache() {
        daoClassesCache = null;
    }
}
