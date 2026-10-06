package io.ara.runtime.bus;

import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argument parsing of {@link AgentDelegationTool#parseDelegation}: a missing or blank
 * {@code agent_id}/{@code task} field must fail with a message that names the field —
 * not the raw NPE a bare {@code get(...).asText()} chain produced when the model omitted
 * {@code task} (e.g. after an output-token truncation mid-JSON).
 */
class AgentDelegationToolParseTest {

    @Test
    void parsesWellFormedArguments() {
        AgentDelegationTool.DelegationRequest req =
                AgentDelegationTool.parseDelegation("{\"agent_id\":\"NL2SQL\",\"task\":\"do it\"}");
        assertEquals("NL2SQL", req.recipientAgentId());
        assertEquals("do it", req.task());
    }

    @Test
    void missingTaskField_failsNamingTheField_notAnNpe() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentDelegationTool.parseDelegation("{\"agent_id\":\"NL2SQL\"}"));
        assertTrue(e.getMessage().contains("task"),
                "failure message should name the missing field, got: " + e.getMessage());
        assertFalse(e.getMessage().contains("Cannot invoke"),
                "no raw NPE text should leak, got: " + e.getMessage());
    }

    @Test
    void missingAgentIdField_failsNamingTheField() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentDelegationTool.parseDelegation("{\"task\":\"do it\"}"));
        assertTrue(e.getMessage().contains("agent_id"),
                "failure message should name the missing field, got: " + e.getMessage());
    }

    @Test
    void blankTaskField_failsNamingTheField() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentDelegationTool.parseDelegation("{\"agent_id\":\"NL2SQL\",\"task\":\"  \"}"));
        assertTrue(e.getMessage().contains("task"),
                "failure message should name the missing field, got: " + e.getMessage());
    }

    @Test
    void nonTextualField_failsNamingTheField() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentDelegationTool.parseDelegation("{\"agent_id\":17,\"task\":\"do it\"}"));
        assertTrue(e.getMessage().contains("agent_id"),
                "failure message should name the malformed field, got: " + e.getMessage());
    }

    @Test
    void nonObjectPayload_failsWithObjectMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentDelegationTool.parseDelegation("\"just a string\""));
        assertTrue(e.getMessage().contains("JSON object"),
                "failure message should ask for a JSON object, got: " + e.getMessage());
    }

    @Test
    void unparseableJson_failsWithParseMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentDelegationTool.parseDelegation("{\"agent_id\":"));
    }

    @Test
    void execute_returnsFailureNamingTheMissingField() {
        AgentDelegationTool tool = new AgentDelegationTool(new io.ara.core.bus.MessageBus() {
            @Override public void send(io.ara.core.bus.AgentMessage message) {
                throw new AssertionError("bus must not be reached on invalid arguments");
            }
            @Override public io.ara.core.bus.AgentMessage request(io.ara.core.bus.AgentMessage message, java.time.Duration timeout) {
                throw new AssertionError("bus must not be reached on invalid arguments");
            }
        }, "caller");

        ToolResult result = tool.execute("{\"agent_id\":\"NL2SQL\"}");

        assertFalse(result.success());
        assertTrue(result.content().contains("task"),
                "tool failure should name the missing field, got: " + result.content());
        assertFalse(result.content().contains("Cannot invoke"),
                "no raw NPE text should leak, got: " + result.content());
    }
}
