package test;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * Uses Nashorn engine — removed in Java 15, use GraalJS instead.
 */
public class NashornScript {
    public Object eval(String script) throws Exception {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("nashorn");
        return engine.eval(script);
    }
}
