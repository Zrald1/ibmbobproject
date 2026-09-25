package com.argos.argos_backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.argos.argos_backend.service.CommandPlannerService.Plan;

/**
 * Unit tests for the deterministic command planner covering app launching,
 * search, scheduling, the full file-tool family, expressions and sequences.
 */
class CommandPlannerServiceTest {

    private final CommandPlannerService planner = new CommandPlannerService();

    @Test
    void opensAppWithPackageTag() {
        Plan plan = planner.plan("open youtube", null);
        assertEquals("OPEN_APP", plan.intent());
        assertEquals("HAPPY", plan.expression());
        assertTrue(plan.replyWithTags().contains("[TOOL:OPEN:com.google.android.youtube]"),
                "expected an OPEN tag, got: " + plan.replyWithTags());
        assertNotNull(plan.action());
        assertEquals("OPEN_APP", plan.action().type());
    }

    @Test
    void searchesTheWeb() {
        Plan plan = planner.plan("search for cats", null);
        assertEquals("SEARCH", plan.intent());
        assertTrue(plan.replyWithTags().contains("[TOOL:SEARCH:cats]"),
                "expected a SEARCH tag, got: " + plan.replyWithTags());
    }

    @Test
    void schedulesReminderWithTime() {
        Plan plan = planner.plan("remind me at 18:30 to call mom", null);
        assertEquals("SCHEDULE", plan.intent());
        assertTrue(plan.replyWithTags().contains("[TOOL:SCHEDULE:18:30:"),
                "expected a SCHEDULE tag, got: " + plan.replyWithTags());
        assertTrue(plan.action().requiresConfirmation());
    }

    @Test
    void handlesFileTools() {
        assertTrue(planner.plan("list files", null).replyWithTags().contains("[TOOL:LIST_FILES]"));
        assertTrue(planner.plan("read file notes.txt", null).replyWithTags().contains("[TOOL:READ_FILE:notes.txt]"));
        assertTrue(planner.plan("write file notes.txt | hello world", null).replyWithTags()
                .contains("[TOOL:WRITE_FILE:notes.txt|hello world]"));
        assertTrue(planner.plan("delete file secret.txt", null).replyWithTags()
                .contains("[TOOL:DELETE_FILE:secret.txt]"));
        assertTrue(planner.plan("create folder projects", null).replyWithTags()
                .contains("[TOOL:CREATE_FOLDER:projects]"));
        assertTrue(planner.plan("show the file tree", null).replyWithTags().contains("[TOOL:FILE_TREE]"));
    }

    @Test
    void detectsEmotionAndGreeting() {
        Plan love = planner.plan("i love you", null);
        assertEquals("EMOTION", love.intent());
        assertEquals("LOVE", love.expression());

        Plan greet = planner.plan("hello there", null);
        assertEquals("GREETING", greet.intent());
        assertEquals("HAPPY", greet.expression());
        assertTrue(greet.replyWithTags().contains("[TOOL:HAND:WAVE]"));
    }

    @Test
    void emitsExcitementSequence() {
        Plan plan = planner.plan("I'm so excited about this!", null);
        assertEquals("EXCITED", plan.expression());
        assertTrue(plan.replyWithTags().contains("[TOOL:EXPRSEQ:"),
                "expected an EXPRSEQ tag, got: " + plan.replyWithTags());
    }

    @Test
    void alwaysEmitsAnExpressionTag() {
        Plan plan = planner.plan("tell me something random", null);
        assertTrue(plan.replyWithTags().contains("[TOOL:EXPR:"),
                "every reply must carry an EXPR tag, got: " + plan.replyWithTags());
    }

    @Test
    void extractsExpressionAndGestureFromReply() {
        String reply = "Opening now. [TOOL:EXPR:HAPPY] [TOOL:HAND:POINT] [TOOL:OPEN:com.spotify.music]";
        assertEquals("HAPPY", planner.extractExpression(reply));
        assertEquals("POINT", planner.extractGesture(reply));
        // Fallbacks for a reply with no tags.
        assertEquals("NEUTRAL", planner.extractExpression("no tags here"));
        assertEquals("NONE", planner.extractGesture("no tags here"));
    }

    @Test
    void handlesNullAndBlankSafely() {
        assertNotNull(planner.plan(null, null));
        assertNotNull(planner.plan("", ""));
        assertTrue(planner.plan(null, null).replyWithTags().contains("[TOOL:EXPR:"));
    }
}
