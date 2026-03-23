package test;

/**
 * Uses SecurityManager — deprecated for removal in Java 17+.
 */
public class SecurityManagerUsage {
    public void setup() {
        SecurityManager sm = new SecurityManager();
        System.setSecurityManager(sm);
    }

    public boolean isRestricted() {
        SecurityManager sm = System.getSecurityManager();
        return sm != null;
    }
}
