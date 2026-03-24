package com.jsrc.app.jfr;

/**
 * Thrown when a required JDK tool (jcmd or jfr) is not found.
 */
public class JfrToolNotFoundException extends RuntimeException {

    private final String tool;

    public JfrToolNotFoundException(String tool) {
        super(tool + " not found. Requires JDK 14+ with HotSpot (not OpenJ9). "
                + "Ensure " + tool + " is in PATH or JAVA_HOME is set.");
        this.tool = tool;
    }

    public String tool() {
        return tool;
    }
}
