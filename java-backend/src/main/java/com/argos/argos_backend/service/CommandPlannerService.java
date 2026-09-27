package com.argos.argos_backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.argos.argos_backend.ai.PromptConstants;
import com.argos.argos_backend.dto.ActionDto;

/**
 * Deterministic intent parser and tool-tag generator for the full Argos
 * desktop/mobile tool protocol.
 *
 * <p>This service is the resilient heart of the backend: the AI providers use
 * it to guarantee correct, protocol-valid tags, and it doubles as the offline
 * command planner when no upstream model is reachable. It never throws and
 * never performs I/O, so it is trivially unit-testable.</p>
 */
@Service
public class CommandPlannerService {

    /** Result of analysing a single user message. */
    public record Plan(
            String intent,
            String expression,
            String gesture,
            ActionDto action,
            List<String> tags,
            String suggestedReply) {

        /** The suggested reply with all tool tags appended inline. */
        public String replyWithTags() {
            if (tags == null || tags.isEmpty()) {
                return suggestedReply;
            }
            return suggestedReply + " " + String.join(" ", tags);
        }
    }

    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern URL = Pattern.compile("(https?://\\S+|www\\.\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPR_TAG = Pattern.compile("\\[TOOL:(?:EXPR|EXPRESSION):([A-Z_]+)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAND_TAG = Pattern.compile("\\[TOOL:HAND:([A-Z_]+)]", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> APP_PACKAGES = new LinkedHashMap<>();

    static {
        APP_PACKAGES.put("whatsapp", "com.whatsapp");
        APP_PACKAGES.put("youtube", "com.google.android.youtube");
        APP_PACKAGES.put("spotify", "com.spotify.music");
        APP_PACKAGES.put("chrome", "com.android.chrome");
        APP_PACKAGES.put("instagram", "com.instagram.android");
        APP_PACKAGES.put("facebook", "com.facebook.katana");
        APP_PACKAGES.put("twitter", "com.twitter.android");
        APP_PACKAGES.put("x", "com.twitter.android");
        APP_PACKAGES.put("gmail", "com.google.android.gm");
        APP_PACKAGES.put("mail", "com.google.android.gm");
        APP_PACKAGES.put("maps", "com.google.android.apps.maps");
        APP_PACKAGES.put("camera", "com.android.camera");
        APP_PACKAGES.put("settings", "com.android.settings");
        APP_PACKAGES.put("play store", "com.android.vending");
        APP_PACKAGES.put("playstore", "com.android.vending");
        APP_PACKAGES.put("netflix", "com.netflix.mediaclient");
        APP_PACKAGES.put("telegram", "org.telegram.messenger");
        APP_PACKAGES.put("discord", "com.discord");
        APP_PACKAGES.put("calculator", "com.google.android.calculator");
        APP_PACKAGES.put("clock", "com.google.android.deskclock");
        APP_PACKAGES.put("calendar", "com.google.android.calendar");
        APP_PACKAGES.put("photos", "com.google.android.apps.photos");
        APP_PACKAGES.put("drive", "com.google.android.apps.docs");
    }

    /**
     * Analyse a message and produce a protocol-valid plan.
     *
     * @param message       the user's message (never required to be non-null)
     * @param screenContext optional screen context, may be {@code null}
     * @return a plan with expression, gesture, action, tags and a tag-free reply
     */
    public Plan plan(String message, String screenContext) {
        String original = message == null ? "" : message.trim();
        String text = original.toLowerCase(Locale.ROOT);

        if (text.isEmpty()) {
            return chat("CHAT", "I'm here whenever you're ready.", "NEUTRAL", "WAVE", null, null);
        }

        Plan action = tryAction(original, text, screenContext);
        if (action != null) {
            return action;
        }

        Plan emotion = tryEmotion(text);
        if (emotion != null) {
            return emotion;
        }

        Plan social = trySocial(text);
        if (social != null) {
            return social;
        }

        if (looksLikeQuestion(text)) {
            return chat("QUESTION",
                    "Good question - let me think about that for a second.",
                    "THINKING", "THINK", null, null);
        }

        boolean hasScreen = screenContext != null && !screenContext.isBlank();
        String reply = hasScreen
                ? "Got it. I can see what's on your screen and I'm following along."
                : "Understood. I'm listening - tell me what you'd like to do next.";
        return chat("CHAT", reply, "TALKING", "NONE", null, null);
    }

    // ------------------------------------------------------------------
    // Action intents
    // ------------------------------------------------------------------

    private Plan tryAction(String original, String text, String screenContext) {
        // LOOK / screen comprehension
        if (text.contains("what's on my screen") || text.contains("what is on my screen")
                || text.startsWith("look ") || text.equals("look") || text.contains("look at the screen")
                || text.contains("see my screen")) {
            return chat("LOOK", "Let me take a look at your screen.", "THINKING", "POINT",
                    new ActionDto("LOOK", null, false), List.of("[TOOL:LOOK]"));
        }

        // LIST_APPS
        if (text.contains("list apps") || text.contains("list my apps") || text.contains("show apps")
                || text.contains("what apps") || text.contains("installed apps")) {
            return chat("LIST_APPS", "Here are your installed apps.", "NEUTRAL", "OPEN",
                    new ActionDto("LIST_APPS", null, false), List.of("[TOOL:LIST_APPS]"));
        }

        // SCHEDULE / reminder / alarm
        if (text.contains("remind me") || text.contains("reminder") || text.contains("schedule")
                || text.contains("alarm") || text.contains("set a timer")) {
            Matcher m = TIME.matcher(original);
            if (m.find()) {
                String hh = String.format("%02d", Integer.parseInt(m.group(1)) % 24);
                String mm = m.group(2);
                String desc = stripScheduleWords(original.replace(m.group(0), "").trim());
                if (desc.isEmpty()) {
                    desc = "reminder";
                }
                String tag = "[TOOL:SCHEDULE:" + hh + ":" + mm + ":" + desc + "]";
                return chat("SCHEDULE", "I'll remind you at " + hh + ":" + mm + " to " + desc + ".",
                        "HAPPY", "THUMBS_UP", new ActionDto("SCHEDULE", hh + ":" + mm + " " + desc, true),
                        List.of(tag));
            }
            return chat("SCHEDULE", "Sure, what time should I set that reminder for?",
                    "THINKING", "THINK", new ActionDto("SCHEDULE", original, true), null);
        }

        // File operations
        Plan file = tryFile(original, text);
        if (file != null) {
            return file;
        }

        // BROWSER (explicit url)
        Matcher url = URL.matcher(original);
        if (url.find() && (text.contains("open") || text.contains("browse") || text.contains("go to")
                || text.contains("load") || text.startsWith("http"))) {
            String target = url.group(1);
            if (!target.toLowerCase(Locale.ROOT).startsWith("http")) {
                target = "https://" + target;
            }
            return chat("BROWSER", "Opening that link for you.", "HAPPY", "POINT",
                    new ActionDto("OPEN_URL", target, false), List.of("[TOOL:BROWSER:" + target + "]"));
        }

        // SEARCH
        if (text.startsWith("search ") || text.startsWith("search for ") || text.contains("google ")
                || text.startsWith("look up ") || text.contains("find information")
                || text.startsWith("what is the weather") || text.startsWith("who is")) {
            String query = stripSearchWords(original);
            if (!query.isBlank()) {
                return chat("SEARCH", "Searching the web for \"" + query + "\".", "THINKING", "POINT",
                        new ActionDto("SEARCH", query, false), List.of("[TOOL:SEARCH:" + query + "]"));
            }
        }

        // TYPE
        if (text.startsWith("type ") || text.startsWith("write ") && text.contains(" in the field")
                || text.startsWith("type out ")) {
            String payload = afterKeyword(original, "type ");
            if (!payload.isBlank()) {
                return chat("TYPE", "Typing that out now.", "TALKING", "POINT",
                        new ActionDto("TYPE_TEXT", payload, false), List.of("[TOOL:TYPE:" + payload + "]"));
            }
        }

        // COPY
        if (text.startsWith("copy ")) {
            String payload = afterKeyword(original, "copy ");
            if (!payload.isBlank()) {
                return chat("COPY", "Copied that to your clipboard.", "HAPPY", "THUMBS_UP",
                        new ActionDto("COPY", payload, false), List.of("[TOOL:COPY:" + payload + "]"));
            }
        }

        // PASTE
        if (text.startsWith("paste ")) {
            String payload = afterKeyword(original, "paste ");
            if (!payload.isBlank()) {
                return chat("PASTE", "Pasting that into the field.", "HAPPY", "POINT",
                        new ActionDto("PASTE", payload, false), List.of("[TOOL:PASTE:" + payload + "]"));
            }
        }
        if (text.equals("paste") || text.contains("paste it") || text.contains("paste here")) {
            return chat("PASTE", "Pasting your clipboard now.", "HAPPY", "POINT",
                    new ActionDto("PASTE", null, false), List.of("[TOOL:PASTE:]"));
        }

        // OPEN app (checked last among actions so "open <url>" is handled above)
        if (text.startsWith("open ") || text.startsWith("launch ") || text.startsWith("start ")
                || text.startsWith("run ")) {
            String app = stripOpenWords(original);
            if (!app.isBlank()) {
                if (app.equalsIgnoreCase("settings")) {
                    return chat("OPEN_APP", "Opening settings.", "HAPPY", "POINT",
                            new ActionDto("OPEN_SETTINGS", "SETTINGS", false),
                            List.of("[TOOL:OPEN:com.android.settings]"));
                }
                String pkg = resolvePackage(app);
                return chat("OPEN_APP", "Opening " + app + " for you.", "HAPPY", "POINT",
                        new ActionDto("OPEN_APP", pkg, false), List.of("[TOOL:OPEN:" + pkg + "]"));
            }
            return chat("OPEN_APP", "Which app would you like me to open?", "CONFUSED", "SHRUG",
                    new ActionDto("OPEN_APP", null, true), null);
        }

        return null;
    }

    private Plan tryFile(String original, String text) {
        // ORDER matters: more specific phrases first.
        if (text.contains("file tree") || text.contains("show files") || text.contains("show the files")
                || text.contains("show me the files")) {
            return chat("FILE_TREE", "Here's your file tree.", "NEUTRAL", "OPEN",
                    new ActionDto("FILE_OP", "FILE_TREE", false), List.of("[TOOL:FILE_TREE]"));
        }
        if (text.contains("list files") || text.contains("list the files") || text.contains("list my files")) {
            String folder = afterKeyword(text, "list files ");
            String tag = folder.isBlank() ? "[TOOL:LIST_FILES]" : "[TOOL:LIST_FILES:" + folder.trim() + "]";
            return chat("LIST_FILES", "Listing your files.", "NEUTRAL", "OPEN",
                    new ActionDto("FILE_OP", "LIST_FILES", false), List.of(tag));
        }
        if (text.contains("search content") || text.contains("search in files") || text.contains("search inside")) {
            String q = stripSearchWords(original);
            return chat("SEARCH_CONTENT", "Searching file contents for \"" + q + "\".", "THINKING", "POINT",
                    new ActionDto("FILE_OP", "SEARCH_CONTENT", false), List.of("[TOOL:SEARCH_CONTENT:" + q + "]"));
        }
        if (text.contains("search files") || text.contains("find file") || text.contains("find files")
                || text.contains("search for a file")) {
            String q = stripSearchWords(original);
            return chat("SEARCH_FILES", "Searching your files for \"" + q + "\".", "THINKING", "POINT",
                    new ActionDto("FILE_OP", "SEARCH_FILES", false), List.of("[TOOL:SEARCH_FILES:" + q + "]"));
        }
        if (text.contains("versions of") || text.contains("file versions") || text.contains("version history")) {
            String path = extractPath(original, new String[]{"versions of", "file versions", "version history"});
            return chat("FILE_VERSIONS", "Here are the saved versions of " + path + ".", "NEUTRAL", "OPEN",
                    new ActionDto("FILE_OP", "FILE_VERSIONS", false), List.of("[TOOL:FILE_VERSIONS:" + path + "]"));
        }
        if (text.contains("restore file") || text.contains("restore the file") || text.contains("restore version")) {
            String name = extractPath(original, new String[]{"restore file", "restore the file", "restore version"});
            return chat("RESTORE_FILE", "Restoring " + name + ".", "HAPPY", "THUMBS_UP",
                    new ActionDto("FILE_OP", "RESTORE_FILE", true), List.of("[TOOL:RESTORE_FILE:" + name + "]"));
        }
        if (text.contains("create folder") || text.contains("new folder") || text.contains("make a folder")) {
            String path = extractPath(original, new String[]{"create folder", "new folder", "make a folder"});
            return chat("CREATE_FOLDER", "Creating the folder " + path + ".", "HAPPY", "OPEN",
                    new ActionDto("FILE_OP", "CREATE_FOLDER", false), List.of("[TOOL:CREATE_FOLDER:" + path + "]"));
        }
        if (text.contains("delete folder") || text.contains("remove folder")) {
            String path = extractPath(original, new String[]{"delete folder", "remove folder"});
            return chat("DELETE_FOLDER", "Deleting the folder " + path + ".", "THINKING", "DOWN",
                    new ActionDto("FILE_OP", "DELETE_FOLDER", true), List.of("[TOOL:DELETE_FOLDER:" + path + "]"));
        }
        if (text.contains("delete file") || text.contains("remove file") || text.contains("delete the file")) {
            String path = extractPath(original, new String[]{"delete file", "remove file", "delete the file"});
            return chat("DELETE_FILE", "Deleting " + path + ".", "THINKING", "DOWN",
                    new ActionDto("FILE_OP", "DELETE_FILE", true), List.of("[TOOL:DELETE_FILE:" + path + "]"));
        }
        if (text.contains("move file") || text.contains("rename file") || text.contains("move the file")) {
            String[] parts = splitOn(original, new String[]{" to "});
            String src = extractPath(parts[0], new String[]{"move file", "rename file", "move the file"});
            String dest = parts.length > 1 ? parts[1].trim() : "";
            return chat("MOVE_FILE", "Moving " + src + " to " + dest + ".", "HAPPY", "POINT",
                    new ActionDto("FILE_OP", "MOVE_FILE", true), List.of("[TOOL:MOVE_FILE:" + src + "|" + dest + "]"));
        }
        if (text.contains("copy file") || text.contains("copy the file")) {
            String[] parts = splitOn(original, new String[]{" to "});
            String src = extractPath(parts[0], new String[]{"copy file", "copy the file"});
            String dest = parts.length > 1 ? parts[1].trim() : "";
            return chat("COPY_FILE", "Copying " + src + " to " + dest + ".", "HAPPY", "POINT",
                    new ActionDto("FILE_OP", "COPY_FILE", false), List.of("[TOOL:COPY_FILE:" + src + "|" + dest + "]"));
        }
        if (text.contains("append to") || text.contains("append file") || text.contains("add to file")) {
            String[] parts = splitPipe(original);
            String path = extractPath(parts[0], new String[]{"append to", "append file", "add to file"});
            String content = parts.length > 1 ? parts[1].trim() : "";
            return chat("APPEND_FILE", "Appending that to " + path + ".", "HAPPY", "POINT",
                    new ActionDto("FILE_OP", "APPEND_FILE", false),
                    List.of("[TOOL:APPEND_FILE:" + path + "|" + content + "]"));
        }
        if (text.contains("edit file") || text.contains("replace in file") || text.contains("edit the file")) {
            String[] parts = splitPipe(original);
            String path = extractPath(parts[0], new String[]{"edit file", "replace in file", "edit the file"});
            String oldText = parts.length > 1 ? parts[1].trim() : "";
            String newText = parts.length > 2 ? parts[2].trim() : "";
            return chat("EDIT_FILE", "Updating " + path + " for you.", "HAPPY", "POINT",
                    new ActionDto("FILE_OP", "EDIT_FILE", true),
                    List.of("[TOOL:EDIT_FILE:" + path + "|" + oldText + "|" + newText + "]"));
        }
        if (text.contains("read file") || text.contains("open file") || text.contains("show file")
                || text.contains("read the file")) {
            String path = extractPath(original, new String[]{"read file", "open file", "show file", "read the file"});
            return chat("READ_FILE", "Reading " + path + ".", "NEUTRAL", "POINT",
                    new ActionDto("FILE_OP", "READ_FILE", false), List.of("[TOOL:READ_FILE:" + path + "]"));
        }
        if (text.contains("write file") || text.contains("write to file") || text.contains("create file")
                || text.contains("create a file") || text.contains("save file") || text.contains("new file")) {
            String[] parts = splitPipe(original);
            String path = extractPath(parts[0], new String[]{
                    "write file", "write to file", "create file", "create a file", "save file", "new file"});
            String content = parts.length > 1 ? parts[1].trim() : "";
            return chat("WRITE_FILE", "Writing " + path + " for you.", "HAPPY", "POINT",
                    new ActionDto("FILE_OP", "WRITE_FILE", false),
                    List.of("[TOOL:WRITE_FILE:" + path + "|" + content + "]"));
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Emotion / social / small-talk
    // ------------------------------------------------------------------

    private Plan tryEmotion(String text) {
        if (containsAny(text, "excited", "so hyped", "can't wait", "cant wait", "awesome", "amazing", "great news")) {
            String seq = "[TOOL:EXPRSEQ:[{\"expr\":\"SURPRISED\",\"duration\":0.8},{\"expr\":\"EXCITED\",\"duration\":1.5}]]";
            return chat("EMOTION", "That's exciting! I'm pumped for you.", "EXCITED", "CLAP",
                    null, List.of(seq));
        }
        if (containsAny(text, "i love you", "love you", "you're the best", "you are the best")) {
            return chat("EMOTION", "Aww, I love being your companion!", "LOVE", "HEART", null, null);
        }
        if (containsAny(text, "i'm sad", "im sad", "feeling down", "depressed", "unhappy", "lonely")) {
            return chat("EMOTION", "I'm sorry you're feeling down. I'm right here with you.", "SAD", "REST", null, null);
        }
        if (containsAny(text, "i'm angry", "im angry", "so mad", "furious", "annoyed")) {
            return chat("EMOTION", "Take a breath - I'm here. Want to talk about it?", "ANGRY", "FIST", null, null);
        }
        if (containsAny(text, "i'm scared", "im scared", "afraid", "terrified", "creepy")) {
            return chat("EMOTION", "It's okay, you're safe. I've got you.", "SCARED", "TREMBLE", null, null);
        }
        if (containsAny(text, "surprised", "whoa", "wow", "no way", "unbelievable")) {
            return chat("EMOTION", "Wow, I didn't see that coming either!", "SURPRISED", "CHEEKS", null, null);
        }
        if (containsAny(text, "haha", "lol", "funny", "that's hilarious", "laughing")) {
            return chat("EMOTION", "Heh, glad I could make you laugh!", "LAUGHING", "BELLY", null, null);
        }
        if (containsAny(text, "i'm tired", "im tired", "sleepy", "exhausted", "good night", "goodnight")) {
            return chat("EMOTION", "Rest up - I'll keep watch while you sleep.", "SLEEPING", "REST", null, null);
        }
        if (containsAny(text, "confused", "i don't get it", "i dont get it", "makes no sense")) {
            return chat("EMOTION", "Let me clear that up for you.", "CONFUSED", "SCRATCH", null, null);
        }
        if (containsAny(text, "i'm happy", "im happy", "feeling great", "so good", "wonderful")) {
            return chat("EMOTION", "That makes me happy too!", "HAPPY", "CLAP", null, null);
        }
        return null;
    }

    private Plan trySocial(String text) {
        if (containsAny(text, "thank you", "thanks", "appreciate it", "cheers")) {
            return chat("SOCIAL", "You're very welcome!", "HAPPY", "THUMBS_UP", null, null);
        }
        if (text.startsWith("hi") || text.startsWith("hello") || text.startsWith("hey")
                || text.contains("good morning") || text.contains("good afternoon") || text.contains("good evening")) {
            return chat("GREETING", "Hey there! Great to see you.", "HAPPY", "WAVE", null, null);
        }
        if (containsAny(text, "how are you", "how's it going", "how are u", "what's up", "whats up", "sup")) {
            return chat("SOCIAL", "I'm running great, thanks for asking! How about you?", "HAPPY", "WAVE", null, null);
        }
        if (containsAny(text, "bye", "goodbye", "see you", "see ya", "good night argos")) {
            return chat("SOCIAL", "See you soon! I'll be right here.", "SAD", "WAVE", null, null);
        }
        if (containsAny(text, "your name", "who are you", "what are you")) {
            return chat("SOCIAL", "I'm Argos, your friendly companion robot.", "HAPPY", "WAVE", null, null);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Plan chat(String intent, String reply, String expression, String gesture,
                      ActionDto action, List<String> actionTags) {
        String expr = PromptConstants.normalizeExpression(expression);
        String hand = PromptConstants.normalizeGesture(gesture);

        List<String> tags = new ArrayList<>();
        tags.add(PromptConstants.expr(expr));
        if (!"NONE".equals(hand)) {
            tags.add(PromptConstants.hand(hand));
        }
        if (actionTags != null) {
            tags.addAll(actionTags);
        }
        return new Plan(intent, expr, hand, action, List.copyOf(tags), reply);
    }

    private String resolvePackage(String app) {
        String key = app.toLowerCase(Locale.ROOT).trim();
        if (key.contains(".")) {
            return key; // already looks like a package name
        }
        String exact = APP_PACKAGES.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : APP_PACKAGES.entrySet()) {
            if (key.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return key.replaceAll("\\s+", ".");
    }

    private boolean looksLikeQuestion(String text) {
        return text.endsWith("?") || text.startsWith("what ") || text.startsWith("why ")
                || text.startsWith("how ") || text.startsWith("when ") || text.startsWith("who ")
                || text.startsWith("where ") || text.startsWith("can you tell") || text.startsWith("do you");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String afterKeyword(String original, String keyword) {
        int idx = original.toLowerCase(Locale.ROOT).indexOf(keyword);
        if (idx < 0) {
            return "";
        }
        return original.substring(idx + keyword.length()).trim();
    }

    private static String stripOpenWords(String original) {
        String s = original.trim();
        for (String p : new String[]{"open", "launch", "start", "run"}) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p + " ")) {
                s = s.substring(p.length() + 1).trim();
                break;
            }
        }
        s = s.replaceAll("(?i)\\b(the|app|application|for me|please)\\b", "").replaceAll("\\s{2,}", " ").trim();
        return s;
    }

    private static String stripSearchWords(String original) {
        String s = original.trim();
        s = s.replaceAll("(?i)^(search for|search|google|look up|find)\\s+", "");
        s = s.replaceAll("(?i)\\b(for me|please|on the web|online)\\b", "").replaceAll("\\s{2,}", " ").trim();
        return s;
    }

    private static String stripScheduleWords(String s) {
        return s.replaceAll("(?i)\\b(remind me|reminder|remind|schedule|alarm|set a timer|at|to|please)\\b", "")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private static String extractPath(String original, String[] keywords) {
        String s = original.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            int idx = lower.indexOf(k);
            if (idx >= 0) {
                s = s.substring(idx + k.length()).trim();
                break;
            }
        }
        // Drop leading punctuation / filler words, keep the token that looks like a path/name.
        s = s.replaceAll("^[\\s:|-]+", "").trim();
        s = s.replaceAll("(?i)^(the|a|an|called|named)\\s+", "");
        // Cut at trailing filler.
        s = s.split("(?i)\\s+(with content|content|please)\\b")[0].trim();
        return s.isEmpty() ? "note.txt" : s;
    }

    private static String[] splitPipe(String original) {
        return original.split("\\|", -1);
    }

    private static String[] splitOn(String original, String[] separators) {
        String regex = String.join("|", separators).replace(" ", "\\s+");
        return original.split("(?i)" + regex, -1);
    }

    // ------------------------------------------------------------------
    // Tag extraction from an arbitrary reply (used to fill response fields)
    // ------------------------------------------------------------------

    /** Extract the expression named in a reply's {@code [TOOL:EXPR:X]} tag. */
    public String extractExpression(String reply) {
        if (reply == null) {
            return PromptConstants.DEFAULT_EXPRESSION;
        }
        Matcher m = EXPR_TAG.matcher(reply);
        if (m.find()) {
            return PromptConstants.normalizeExpression(m.group(1));
        }
        return PromptConstants.DEFAULT_EXPRESSION;
    }

    /** Extract the gesture named in a reply's {@code [TOOL:HAND:X]} tag. */
    public String extractGesture(String reply) {
        if (reply == null) {
            return PromptConstants.DEFAULT_GESTURE;
        }
        Matcher m = HAND_TAG.matcher(reply);
        if (m.find()) {
            return PromptConstants.normalizeGesture(m.group(1));
        }
        return PromptConstants.DEFAULT_GESTURE;
    }
}
