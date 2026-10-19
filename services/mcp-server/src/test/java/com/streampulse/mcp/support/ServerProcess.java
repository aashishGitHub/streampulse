/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.support;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launches the real server in a child JVM over stdio: the same thing .mcp.json does, minus the jar. */
public final class ServerProcess {

    private ServerProcess() {}

    public static String java() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }

    public static List<String> command() {
        List<String> cmd = new ArrayList<>();
        cmd.add(java());
        cmd.add("-cp");
        cmd.add(System.getProperty("streampulse.test.classpath"));
        cmd.add("com.streampulse.mcp.McpServerApplication");
        return cmd;
    }

    /** Backend URLs travel as environment variables, exactly as .mcp.json passes them. */
    public static Map<String, String> env(URI queryApi, URI alertEngine) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("STREAMPULSE_MCP_QUERYAPI_BASEURL", queryApi.toString());
        env.put("STREAMPULSE_MCP_ALERTENGINE_BASEURL", alertEngine.toString());
        return env;
    }
}
