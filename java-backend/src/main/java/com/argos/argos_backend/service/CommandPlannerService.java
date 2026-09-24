package com.argos.argos_backend.service;

import java.util.Locale;

import org.springframework.stereotype.Service;

import com.argos.argos_backend.dto.ActionDto;

@Service
public class CommandPlannerService {

    public record PlannedToolResult(
        String reply,
        String expression,
        String handGesture,
        ActionDto action,
        String toolTag
    ) {}

    public PlannedToolResult plan(String message, String screenContext) {
        String msg = message == null ? "" : message.trim();
        String lower = msg.toLowerCase(Locale.ROOT);

        // 1. App Launching / Opening
        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            String target = extractAfter(msg, lower.startsWith("open ") ? "open " : "launch ");
            String pkg = resolvePackageName(target);
            String toolTag = "[TOOL:OPEN:" + pkg + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:THUMBS_UP]";
            String reply = "Opening " + target + " for you now! " + toolTag;
            return new PlannedToolResult(reply, "HAPPY", "THUMBS_UP", new ActionDto("OPEN_APP", pkg, true), toolTag);
        }

        // 2. Installed Apps List
        if (lower.contains("list apps") || lower.contains("installed apps") || lower.contains("what apps")) {
            String toolTag = "[TOOL:LIST_APPS] [TOOL:EXPR:THINKING] [TOOL:HAND:OPEN]";
            String reply = "Retrieving the list of installed applications on your device. " + toolTag;
            return new PlannedToolResult(reply, "THINKING", "OPEN", new ActionDto("LIST_APPS", "ALL", true), toolTag);
        }

        // 3. Browser & URL Navigation
        if (lower.startsWith("browse ") || lower.contains("open url") || lower.contains("open link")) {
            String url = extractUrlOrSearch(msg, "browse ");
            String toolTag = "[TOOL:BROWSER:" + url + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:POINT]";
            String reply = "Opening browser to " + url + ". " + toolTag;
            return new PlannedToolResult(reply, "HAPPY", "POINT", new ActionDto("OPEN_BROWSER", url, true), toolTag);
        }

        // 4. Web Search
        if (lower.startsWith("search ") || lower.startsWith("google ") || lower.contains("search for")) {
            String query = extractQuery(msg);
            String toolTag = "[TOOL:SEARCH:" + query + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:POINT]";
            String reply = "Searching Google for \"" + query + "\"! " + toolTag;
            return new PlannedToolResult(reply, "HAPPY", "POINT", new ActionDto("SEARCH_WEB", query, true), toolTag);
        }

        // 5. File Management Tools (Argos Desktop Notes/Files)
        if (lower.contains("write file") || lower.contains("create note") || lower.contains("save note")) {
            String pathAndContent = extractFileWritePayload(msg);
            String toolTag = "[TOOL:WRITE_FILE:" + pathAndContent + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:THUMBS_UP]";
            String reply = "Writing file to your Argos notes folder. " + toolTag;
            return new PlannedToolResult(reply, "HAPPY", "THUMBS_UP", new ActionDto("WRITE_FILE", pathAndContent, true), toolTag);
        }

        if (lower.contains("read file") || lower.contains("open note") || lower.contains("view file")) {
            String path = extractFilePath(msg, "read file", "notes/note.txt");
            String toolTag = "[TOOL:READ_FILE:" + path + "] [TOOL:EXPR:THINKING] [TOOL:HAND:POINT]";
            String reply = "Reading contents of " + path + ". " + toolTag;
            return new PlannedToolResult(reply, "THINKING", "POINT", new ActionDto("READ_FILE", path, true), toolTag);
        }

        if (lower.contains("list files") || lower.contains("show notes") || lower.contains("show files")) {
            String toolTag = "[TOOL:LIST_FILES] [TOOL:EXPR:HAPPY] [TOOL:HAND:OPEN]";
            String reply = "Listing all files in your Argos storage. " + toolTag;
            return new PlannedToolResult(reply, "HAPPY", "OPEN", new ActionDto("LIST_FILES", "ROOT", true), toolTag);
        }

        if (lower.contains("file tree") || lower.contains("directory tree")) {
            String toolTag = "[TOOL:FILE_TREE] [TOOL:EXPR:THINKING] [TOOL:HAND:OPEN]";
            String reply = "Generating full file tree structure. " + toolTag;
            return new PlannedToolResult(reply, "THINKING", "OPEN", new ActionDto("FILE_TREE", "ALL", true), toolTag);
        }

        if (lower.contains("delete file") || lower.contains("remove file")) {
            String path = extractFilePath(msg, "delete file", "notes/temp.txt");
            String toolTag = "[TOOL:DELETE_FILE:" + path + "] [TOOL:EXPR:CONFUSED] [TOOL:HAND:SHRUG]";
            String reply = "Preparing to delete file " + path + ". " + toolTag;
            return new PlannedToolResult(reply, "CONFUSED", "SHRUG", new ActionDto("DELETE_FILE", path, true), toolTag);
        }

        if (lower.contains("create folder") || lower.contains("new folder")) {
            String folder = extractFilePath(msg, "folder", "documents");
            String toolTag = "[TOOL:CREATE_FOLDER:" + folder + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:THUMBS_UP]";
            String reply = "Creating folder \"" + folder + "\". " + toolTag;
            return new PlannedToolResult(reply, "HAPPY", "THUMBS_UP", new ActionDto("CREATE_FOLDER", folder, true), toolTag);
        }

        // 6. Scheduling / Alarms
        if (lower.contains("schedule") || lower.contains("remind me") || lower.contains("alarm")) {
            String scheduleSpec = extractScheduleSpec(msg);
            String toolTag = "[TOOL:SCHEDULE:" + scheduleSpec + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:THUMBS_UP]";
            String reply = "Scheduled reminder: " + scheduleSpec + ". " + toolTag;
            return new PlannedToolResult(reply, "HAPPY", "THUMBS_UP", new ActionDto("SCHEDULE_TASK", scheduleSpec, true), toolTag);
        }

        // 7. Typing & Clipboard
        if (lower.startsWith("type ")) {
            String text = extractAfter(msg, "type ");
            String toolTag = "[TOOL:TYPE:" + text + "] [TOOL:EXPR:TALKING]";
            String reply = "Typing into active field: \"" + text + "\". " + toolTag;
            return new PlannedToolResult(reply, "TALKING", "REST", new ActionDto("TYPE_TEXT", text, true), toolTag);
        }

        if (lower.startsWith("copy ")) {
            String text = extractAfter(msg, "copy ");
            String toolTag = "[TOOL:COPY:" + text + "] [TOOL:EXPR:HAPPY] [TOOL:HAND:THUMBS_UP]";
            String reply = "Copied text to clipboard. " + toolTag;
            return new PlannedToolResult(reply, "HAPPY", "THUMBS_UP", new ActionDto("COPY_TEXT", text, true), toolTag);
        }

        if (lower.contains("paste")) {
            String toolTag = "[TOOL:PASTE:text] [TOOL:EXPR:HAPPY]";
            String reply = "Pasting into focused input. " + toolTag;
            return new PlannedToolResult(reply, "HAPPY", "REST", new ActionDto("PASTE_TEXT", "clipboard", true), toolTag);
        }

        // 8. Look at user / screen
        if (lower.contains("look at me") || lower.contains("look at screen") || lower.contains("turn around")) {
            String toolTag = "[TOOL:LOOK] [TOOL:EXPR:HAPPY] [TOOL:HAND:WAVE]";
            String reply = "Turning to look at the screen! " + toolTag;
            return new PlannedToolResult(reply, "HAPPY", "WAVE", new ActionDto("LOOK_SCREEN", "SCREEN", true), toolTag);
        }

        // 9. Emotional Sequence Trigger
        if (lower.contains("celebrate") || lower.contains("surprise me") || lower.contains("dance")) {
            String seq = "[TOOL:EXPRSEQ:[{\"expr\":\"SURPRISED\",\"duration\":1.0},{\"expr\":\"EXCITED\",\"duration\":1.5},{\"expr\":\"HAPPY\",\"duration\":2.0}]] [TOOL:HAND:RAISED]";
            String reply = "Woohoo! Let's celebrate! " + seq;
            return new PlannedToolResult(reply, "EXCITED", "RAISED", new ActionDto("EXPR_SEQUENCE", "CELEBRATE", true), seq);
        }

        // Default conversational assistance
        String contextNote = (screenContext != null && !screenContext.isBlank()) ? " [TOOL:LOOK]" : "";
        String toolTag = "[TOOL:EXPR:HAPPY] [TOOL:HAND:REST]" + contextNote;
        String reply = "Argos is listening and ready to assist! " + toolTag;
        return new PlannedToolResult(reply, "HAPPY", "REST", null, toolTag);
    }

    private String resolvePackageName(String target) {
        String lower = target.toLowerCase(Locale.ROOT).trim();
        if (lower.contains("whatsapp")) return "com.whatsapp";
        if (lower.contains("spotify")) return "com.spotify.music";
        if (lower.contains("youtube")) return "com.google.android.youtube";
        if (lower.contains("chrome") || lower.contains("browser")) return "com.android.chrome";
        if (lower.contains("camera")) return "com.android.camera";
        if (lower.contains("settings")) return "com.android.settings";
        if (lower.contains("gallery") || lower.contains("photos")) return "com.google.android.apps.photos";
        return lower.contains(".") ? lower : "com.example." + lower.replaceAll("[^a-zA-Z0-9_]", "");
    }

    private String extractAfter(String msg, String prefix) {
        if (msg.length() <= prefix.length()) return "";
        return msg.substring(prefix.length()).trim();
    }

    private String extractUrlOrSearch(String msg, String prefix) {
        String rest = extractAfter(msg, prefix);
        if (rest.startsWith("http://") || rest.startsWith("https://")) {
            return rest;
        }
        return "https://" + rest.replaceAll("\\s+", "");
    }

    private String extractQuery(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.startsWith("search for ")) return msg.substring(11).trim();
        if (lower.startsWith("search ")) return msg.substring(7).trim();
        if (lower.startsWith("google ")) return msg.substring(7).trim();
        return msg;
    }

    private String extractFileWritePayload(String msg) {
        String rest = msg;
        int colon = msg.indexOf(':');
        if (colon > 0) {
            String path = msg.substring(0, colon).replaceAll("(?i)(write file|create note|save note)", "").trim();
            String content = msg.substring(colon + 1).trim();
            if (path.isBlank()) path = "notes/quick_note.txt";
            return path + "|" + content;
        }
        return "notes/argos_note.txt|" + msg;
    }

    private String extractFilePath(String msg, String kw, String fallback) {
        int idx = msg.toLowerCase(Locale.ROOT).indexOf(kw);
        if (idx >= 0 && idx + kw.length() < msg.length()) {
            String p = msg.substring(idx + kw.length()).replaceAll("^[:\\s]+", "").trim();
            if (!p.isBlank()) return p;
        }
        return fallback;
    }

    private String extractScheduleSpec(String msg) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2})");
        java.util.regex.Matcher m = p.matcher(msg);
        String time = "09:00";
        if (m.find()) {
            time = m.group(1);
        }
        String desc = msg.replaceAll("(?i)(schedule|remind me to|alarm for|at \\d{1,2}:\\d{2})", "").trim();
        if (desc.isBlank()) desc = "Argos reminder";
        return time + ":" + desc;
    }
}
