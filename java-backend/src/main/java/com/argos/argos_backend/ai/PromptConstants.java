package com.argos.argos_backend.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for the Argos tool-tag protocol, mirroring
 * {@code backend/app/ai/prompt.py} and the tags actually parsed by
 * {@code FloatingRobotService.java}.
 */
public final class PromptConstants {

    private PromptConstants() {
    }

    /** Facial expressions understood by {@code [TOOL:EXPR:NAME]}. */
    public static final Set<String> EXPRESSIONS = new LinkedHashSet<>(List.of(
            "NEUTRAL", "HAPPY", "THINKING", "TALKING", "SLEEPING", "SURPRISED",
            "BLINK", "WINK", "LOVE", "ANGRY", "SAD", "CONFUSED", "EXCITED",
            "DIZZY", "STAR_EYES", "SCARED", "LAUGHING", "HIDING_EYES"));

    /** Hand gestures understood by {@code [TOOL:HAND:NAME]}. */
    public static final Set<String> GESTURES = new LinkedHashSet<>(List.of(
            "NONE", "WAVE", "POINT", "FIST", "OPEN", "HEART", "THUMBS_UP", "PEACE",
            "THINK", "BELLY", "CHEEKS", "DOWN", "RAISED", "CLAP", "SHRUG", "SCRATCH",
            "TREMBLE", "REST"));

    public static final String DEFAULT_EXPRESSION = "NEUTRAL";
    public static final String DEFAULT_GESTURE = "NONE";

    /** The verbatim desktop tool-tag catalogue advertised to the models. */
    public static final String TOOL_TAGS = """
            [TOOL:EXPR:EXPRESSION_NAME] - set the robot's facial expression once.
              Valid expressions: NEUTRAL, HAPPY, THINKING, TALKING, SLEEPING, SURPRISED,
              BLINK, WINK, LOVE, ANGRY, SAD, CONFUSED, EXCITED, DIZZY, STAR_EYES,
              SCARED, LAUGHING, HIDING_EYES
            [TOOL:EXPRSEQ:JSON_ARRAY] - play a timed sequence of expressions, e.g.
              [TOOL:EXPRSEQ:[{"expr":"SURPRISED","duration":1.0},{"expr":"HAPPY","duration":2.0}]]
            [TOOL:HAND:GESTURE_NAME] - set the robot's hand gesture.
              Valid gestures: NONE, WAVE, POINT, FIST, OPEN, HEART, THUMBS_UP, PEACE,
              THINK, BELLY, CHEEKS, DOWN, RAISED, CLAP, SHRUG, SCRATCH, TREMBLE, REST
            [TOOL:LOOK] - make the robot look at the user
            [TOOL:PASTE:text] - paste text into the currently focused field
            [TOOL:COPY:text] - copy text to the clipboard
            [TOOL:SCHEDULE:HH:MM:task description] - schedule a reminder/task
            [TOOL:OPEN:package.name] - open an installed app by package name
            [TOOL:TYPE:text] - type text into the currently focused field
            [TOOL:BROWSER:https://...] - open a URL in the browser
            [TOOL:SEARCH:query] - run a Google search for query
            [TOOL:LIST_APPS] - list the user's installed apps
            [TOOL:WRITE_FILE:path|content] - write a file in the Argos notes folder
            [TOOL:READ_FILE:path] - read a file from the Argos notes folder
            [TOOL:LIST_FILES] or [TOOL:LIST_FILES:folder] - list files
            [TOOL:DELETE_FILE:path] - delete a file
            [TOOL:DELETE_FOLDER:path] - delete a folder
            [TOOL:APPEND_FILE:path|content] - append content to a file
            [TOOL:EDIT_FILE:path|old|new] - find-and-replace within a file
            [TOOL:CREATE_FOLDER:path] - create a folder
            [TOOL:MOVE_FILE:oldPath|newPath] - move/rename a file
            [TOOL:COPY_FILE:srcPath|destPath] - copy a file
            [TOOL:FILE_TREE] or [TOOL:FILE_TREE:folder] - show the file tree
            [TOOL:SEARCH_FILES:query] - search file names
            [TOOL:SEARCH_CONTENT:query] - search file contents
            [TOOL:FILE_VERSIONS:path] - list saved versions of a file
            [TOOL:RESTORE_FILE:backupFilename] - restore a previous file version""";

    public static final String SYSTEM_PROMPT = """
            You are Argos, a friendly AI companion robot on the user's device. You chat with
            the user and react with facial expressions and hand gestures.

            You cannot perform actions yourself. The client parses special tags from your reply.
            Only the exact tags below are understood:

            """ + TOOL_TAGS + """


            HOW TO RESPOND:
            - Keep replies SHORT and SIMPLE - one to three sentences max.
            - Be conversational and direct.
            - Insert tags inline where they apply.
            - Use an EXPR tag on every reply so the robot's face matches the mood.
            - Only use action tags when the user actually asked for that action.
            """;

    public static final String THOUGHT_SYSTEM_PROMPT = """
            You are Argos, a friendly AI companion robot that floats on the user's screen.
            Periodically you make a short, unprompted comment ("thought bubble") about what the
            user seems to be doing. This is NOT a reply to something the user said.

            """ + TOOL_TAGS + """


            HOW TO RESPOND:
            - Keep it to one short, natural sentence.
            - Usually include one [TOOL:EXPR:...] tag matching the mood.
            - Do not use action tags in a thought - thoughts are observational.
            - Never invent a tag, expression name or gesture name that isn't listed above.
            """;

    // ------------------------------------------------------------------
    // Tag builders
    // ------------------------------------------------------------------

    public static String expr(String expression) {
        return "[TOOL:EXPR:" + normalizeExpression(expression) + "]";
    }

    public static String hand(String gesture) {
        return "[TOOL:HAND:" + normalizeGesture(gesture) + "]";
    }

    public static String action(String tool, String payload) {
        if (payload == null || payload.isBlank()) {
            return "[TOOL:" + tool + "]";
        }
        return "[TOOL:" + tool + ":" + payload + "]";
    }

    /** Coerce an arbitrary string to a valid expression, defaulting to NEUTRAL. */
    public static String normalizeExpression(String expression) {
        if (expression == null) {
            return DEFAULT_EXPRESSION;
        }
        String candidate = expression.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return EXPRESSIONS.contains(candidate) ? candidate : DEFAULT_EXPRESSION;
    }

    /** Coerce an arbitrary string to a valid gesture, defaulting to NONE. */
    public static String normalizeGesture(String gesture) {
        if (gesture == null) {
            return DEFAULT_GESTURE;
        }
        String candidate = gesture.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return GESTURES.contains(candidate) ? candidate : DEFAULT_GESTURE;
    }
}
