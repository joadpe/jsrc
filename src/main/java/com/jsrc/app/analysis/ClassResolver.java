package com.jsrc.app.analysis;

import java.util.*;

import com.jsrc.app.parser.model.ClassInfo;

/**
 * Resolves class metadata: DAO detection, field type resolution, class hierarchy.
 * Used by perf, security, flow, and debt commands.
 */
public class ClassResolver {

    /**
     * Detects DAO classes by name, superclass, and interfaces.
     * Computed from the supplied project, without global cross-project state.
     */
    public static Set<String> detectDaoClasses(List<ClassInfo> classes) {
        Set<String> daoClasses = new HashSet<>();

        for (ClassInfo ci : classes) {
            String name = ci.name();
            String qname = ci.qualifiedName();

            // Heuristic 1: class name
            if (name.endsWith("Dao") || name.endsWith("DAO")
                    || name.endsWith("Repository") || name.endsWith("Mapper")) {
                daoClasses.add(name);
                daoClasses.add(qname);
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
                    daoClasses.add(name);
                    daoClasses.add(qname);
                }
            }

            // Heuristic 3: interfaces
            for (String iface : ci.interfaces()) {
                String ifaceSimple = iface.contains(".")
                        ? iface.substring(iface.lastIndexOf('.') + 1) : iface;
                if (ifaceSimple.contains("Repository") || ifaceSimple.contains("Dao")
                        || ifaceSimple.contains("Mapper")) {
                    daoClasses.add(name);
                    daoClasses.add(qname);
                }
            }
        }

        return Set.copyOf(daoClasses);
    }

    /**
     * Checks if a class name is a known DAO class.
     */
    public static boolean isDaoClass(String className, List<ClassInfo> classes) {
        return detectDaoClasses(classes).contains(className);
    }

}
