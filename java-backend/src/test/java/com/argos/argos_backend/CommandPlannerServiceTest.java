package com.argos.argos_backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.argos.argos_backend.service.CommandPlannerService;
import com.argos.argos_backend.service.CommandPlannerService.PlannedToolResult;

class CommandPlannerServiceTest {

    private CommandPlannerService planner;

    @BeforeEach
    void setUp() {
        planner = new CommandPlannerService();
    }

    @Test
    @DisplayName("Plans OPEN_APP tool for WhatsApp")
    void testOpenAppTool() {
        PlannedToolResult res = planner.plan("open WhatsApp", null);
        assertNotNull(res);
        assertEquals("OPEN_APP", res.action().type());
        assertEquals("com.whatsapp", res.action().value());
        assertTrue(res.reply().contains("[TOOL:OPEN:com.whatsapp]"));
        assertEquals("HAPPY", res.expression());
    }

    @Test
    @DisplayName("Plans SEARCH_WEB tool for query")
    void testSearchWebTool() {
        PlannedToolResult res = planner.plan("search weather in Tokyo", null);
        assertNotNull(res);
        assertEquals("SEARCH_WEB", res.action().type());
        assertTrue(res.action().value().contains("weather in Tokyo"));
        assertTrue(res.reply().contains("[TOOL:SEARCH:"));
    }

    @Test
    @DisplayName("Plans WRITE_FILE tool for notes")
    void testWriteFileTool() {
        PlannedToolResult res = planner.plan("create note meeting:Discuss Argos launch", null);
        assertNotNull(res);
        assertEquals("WRITE_FILE", res.action().type());
        assertTrue(res.reply().contains("[TOOL:WRITE_FILE:"));
    }

    @Test
    @DisplayName("Plans LIST_APPS tool")
    void testListAppsTool() {
        PlannedToolResult res = planner.plan("list apps installed", null);
        assertNotNull(res);
        assertEquals("LIST_APPS", res.action().type());
        assertTrue(res.reply().contains("[TOOL:LIST_APPS]"));
    }

    @Test
    @DisplayName("Plans SCHEDULE_TASK reminder")
    void testScheduleTask() {
        PlannedToolResult res = planner.plan("remind me to check server at 14:30", null);
        assertNotNull(res);
        assertEquals("SCHEDULE_TASK", res.action().type());
        assertTrue(res.reply().contains("[TOOL:SCHEDULE:"));
    }
}
