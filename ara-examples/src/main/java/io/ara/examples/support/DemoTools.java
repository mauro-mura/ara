package io.ara.examples.support;

import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;

/**
 * Demo tools shared by sibling examples — the {@code echo} tool used by
 * {@code AraSimpleExample} — kept in {@code support} so an example that wants it does
 * not have to import another example's nested classes.
 */
public final class DemoTools {

    /** The {@code echo} tool: returns the submitted text verbatim, prefixed. */
    public static AraTool echo() {
        return new AraTool() {
            @Override public String toolId()          { return "echo"; }
            @Override public String description()     { return "Echoes back the provided text."; }
            @Override public String argumentSchema()  {
                return """
                        {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""";
            }
            @Override
            public ToolResult execute(String argumentJson) {
                // Extract "text" field naively — sufficient for the demo
                String text = argumentJson.contains("\"text\"")
                        ? argumentJson.replaceAll(".*\"text\"\\s*:\\s*\"([^\"]+)\".*", "$1")
                        : argumentJson;
                System.out.printf("  [EchoTool] executing with: %s%n", text);
                return ToolResult.success("echo", "ECHO → " + text);
            }
        };
    }

    private DemoTools() { }
}
