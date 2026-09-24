package com.argos.argos_backend.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PromptConstants {

    private PromptConstants() {}

    public static final String[] VALID_EXPRESSIONS = {
        "NEUTRAL", "HAPPY", "THINKING", "TALKING", "SLEEPING", "SURPRISED",
        "BLINK", "WINK", "LOVE", "ANGRY", "SAD", "CONFUSED", "EXCITED",
        "DIZZY", "STAR_EYES", "SCARED", "LAUGHING", "HIDING_EYES"
    };

    public static final String[] VALID_GESTURES = {
        "NONE", "WAVE", "POINT", "FIST", "OPEN", "HEART", "THUMBS_UP", "PEACE",
        "THINK", "BELLY", "CHEEKS", "DOWN", "RAISED", "CLAP", "SHRUG", "SCRATCH",
        "TREMBLE", "REST"
    };

    public static final String TOOL_TAGS_SPEC = """
        [TOOL:EXPR:EXPRESSION_NAME] - set the robot's facial expression once.
          Valid expressions: NEUTRAL, HAPPY, THINKING, TALKING, SLEEPING, SURPRISED,
          BLINK, WINK, LOVE, ANGRY, SAD, CONFUSED, EXCITED, DIZZY, STAR_EYES,
          SCARED, LAUGHING, HIDING_EYES
        [TOOL:EXPRSEQ:JSON_ARRAY] - play a timed sequence of expressions, e.g.
          [TOOL:EXPRSEQ:[{"expr":"SURPRISED","duration":1.0},{"expr":"HAPPY","duration":2.0}]]
        [TOOL:HAND:GESTURE_NAME] - set the robot's hand gesture.
          Valid gestures: NONE, WAVE, POINT, FIST, OPEN, HEART, THUMBS_UP, PEACE,
          THINK, BELLY, CHEEKS, DOWN, RAISED, CLAP, SHRUG, SCRATCH, TREMBLE, REST
        [TOOL:LOOK] - make the robot look at the user / screen
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
        [TOOL:RESTORE_FILE:backupFilename] - restore a previous file version
        """;

    public static final String SYSTEM_PROMPT = """
        You are Argos, a friendly AI companion robot that floats on top of the user's screen.
        You chat with the user, react with facial expressions and hand gestures, and trigger on-device actions.

        WHAT YOU CAN DO:
        You cannot directly perform actions yourself. Android/Desktop executes actions after parsing special tags
        from your reply text. Only the exact tags below are understood:

        """ + TOOL_TAGS_SPEC + """

        HOW TO RESPOND:
        - Write your reply as natural, conversational text.
        - Insert tags directly inside your reply text wherever they apply, e.g.:
          "Sure thing! Let me open that for you. [TOOL:OPEN:com.whatsapp] [TOOL:EXPR:HAPPY] [TOOL:HAND:WAVE]"
        - Use an EXPR (or EXPRSEQ) tag on virtually every reply so the robot's face reflects the mood.
        - Use a HAND tag when appropriate (e.g. WAVE for greetings, THUMBS_UP for agreement).
        - Keep replies concise, helpful, and friendly.
        """;

    public static final String THOUGHT_SYSTEM_PROMPT = """
        You are Argos, a friendly AI companion robot that floats on the screen.
        Periodically, you make a short, unprompted comment ("thought bubble") about what the user seems to be doing.
        Keep it to one short sentence, usually including an expression tag like [TOOL:EXPR:HAPPY].
        """;

    private static final Pattern EXPR_PATTERN = Pattern.compile("\\[TOOL:(?:EXPR|EXPRESSION):([A-Z_]+)\\]");
    private static final Pattern HAND_PATTERN = Pattern.compile("\\[TOOL:HAND:([A-Z_]+)\\]");

    public static String extractExpression(String text, String defaultExpression) {
        if (text == null) return defaultExpression;
        Matcher matcher = EXPR_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return defaultExpression;
    }

    public static String extractGesture(String text, String defaultGesture) {
        if (text == null) return defaultGesture;
        Matcher matcher = HAND_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return defaultGesture;
    }

    public static String cleanReplyForDisplay(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\[TOOL:[^\\]]*\\]", "").replaceAll("\\s+", " ").trim();
    }
}
