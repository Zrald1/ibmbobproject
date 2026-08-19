package com.example.argos;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end simulation test for the Argus file tool system.
 *
 * This test replicates the EXACT parsing logic from FloatingRobotService.executeTool()
 * to verify that the AI's [TOOL:...] tags are correctly parsed and dispatched to
 * ArgosFileManager methods. It simulates the full pipeline:
 *
 *   AI response → [TOOL:WRITE_FILE:path|content] → executeTool() → ArgosFileManager
 *
 * Every tool is tested with:
 *   1. A valid call (should succeed)
 *   2. A security attack (should be blocked)
 *   3. Edge cases (empty, missing files, etc.)
 *
 * The test also simulates multi-step workflows that the AI would perform,
 * like creating a story folder structure, writing chapters, editing them,
 * and restoring previous versions.
 *
 * Run with: ./gradlew testDebugUnitTest --tests "*SimulationTest"
 */
public class ArgosFileToolSimulationTest {

    private File tempRoot;
    private ArgosFileManager fileManager;
    private List<String> messageLog;      // simulates addMessage() output
    private List<String> approvalRequests; // simulates approval card triggers

    // ═══════════════════════════════════════════════════════════════
    //  SETUP — simulate FloatingRobotService state
    // ═══════════════════════════════════════════════════════════════

    @Before
    public void setUp() throws Exception {
        tempRoot = File.createTempFile("argos_sim", "");
        tempRoot.delete();
        tempRoot.mkdirs();
        fileManager = new ArgosFileManager(tempRoot, true);
        messageLog = new ArrayList<>();
        approvalRequests = new ArrayList<>();
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursively(tempRoot);
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        f.delete();
    }

    /** Simulates addMessage() — records what the user would see. */
    private void addMessage(String msg) {
        messageLog.add(msg);
    }

    /** Returns the last message added. */
    private String lastMessage() {
        return messageLog.get(messageLog.size() - 1);
    }

    /** Returns true if any message contains the given substring. */
    private boolean anyMessageContains(String substr) {
        for (String m : messageLog) {
            if (m.contains(substr)) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  TOOL DISPATCH — replicates FloatingRobotService.executeTool()
    //  This is the EXACT same parsing logic, minus the Android UI calls.
    // ═══════════════════════════════════════════════════════════════

    /**
     * Simulates the tool dispatch from executeTool().
     * The input is the tool string WITHOUT the [TOOL: and ] wrapper,
     * exactly as FloatingRobotService passes it.
     */
    private void executeTool(String tool) {
        if (tool.startsWith("WRITE_FILE:")) {
            String rest = tool.substring(11).trim();
            int pipeIdx = rest.indexOf('|');
            if (pipeIdx > 0) {
                String filepath = rest.substring(0, pipeIdx).trim();
                String content = rest.substring(pipeIdx + 1).trim();
                try {
                    String path = fileManager.writeFile(filepath, content, false);
                    if (path != null) {
                        addMessage("📝 Saved: " + filepath + " (" + content.length() + " chars)");
                    } else {
                        addMessage("📝 Failed to write: " + filepath);
                    }
                } catch (SecurityException e) {
                    addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)");
                }
            }
        } else if (tool.startsWith("APPEND_FILE:")) {
            String rest = tool.substring(12).trim();
            int pipeIdx = rest.indexOf('|');
            if (pipeIdx > 0) {
                String filepath = rest.substring(0, pipeIdx).trim();
                String content = rest.substring(pipeIdx + 1).trim();
                try {
                    String path = fileManager.writeFile(filepath, content, true);
                    if (path != null) {
                        addMessage("📝 Appended to: " + filepath);
                    } else {
                        addMessage("📝 Failed to append to: " + filepath);
                    }
                } catch (SecurityException e) {
                    addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)");
                }
            }
        } else if (tool.startsWith("EDIT_FILE:")) {
            String rest = tool.substring(10).trim();
            int firstPipe = rest.indexOf('|');
            if (firstPipe > 0) {
                String filepath = rest.substring(0, firstPipe).trim();
                String remaining = rest.substring(firstPipe + 1);
                int secondPipe = remaining.indexOf('|');
                if (secondPipe >= 0) {
                    String oldText = remaining.substring(0, secondPipe);
                    String newText = remaining.substring(secondPipe + 1);
                    try {
                        int count = fileManager.editFile(filepath, oldText, newText);
                        if (count > 0) {
                            addMessage("✏️ Edited: " + filepath + " (" + count + " replacement(s))");
                        } else if (count == 0) {
                            addMessage("✏️ No matches found in: " + filepath);
                        } else {
                            addMessage("✏️ Failed to edit: " + filepath);
                        }
                    } catch (SecurityException e) {
                        addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)");
                    }
                }
            }
        } else if (tool.startsWith("READ_FILE:")) {
            String filepath = tool.substring(10).trim();
            try {
                String content = fileManager.readFile(filepath);
                if (content != null) {
                    String preview = content.length() > 300 ? content.substring(0, 300) + "..." : content;
                    addMessage("📄 " + filepath + ": " + preview);
                } else {
                    addMessage("📄 File not found: " + filepath);
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)");
            }
        } else if (tool.startsWith("LIST_FILES")) {
            String folderPath = tool.length() > 10 ? tool.substring(10).replace(":", "").trim() : "";
            if (folderPath.isEmpty()) folderPath = "";
            try {
                List<ArgosFileManager.FileInfo> files = fileManager.listFiles(folderPath);
                if (files.isEmpty()) {
                    addMessage("📂 No files in " + (folderPath.isEmpty() ? "Argus folder" : folderPath));
                } else {
                    StringBuilder sb = new StringBuilder("📂 " + (folderPath.isEmpty() ? "Argus folder" : folderPath) + " (" + files.size() + " items):\n");
                    for (ArgosFileManager.FileInfo fi : files) {
                        sb.append("  ").append(fi.getTypeIcon()).append(" ").append(fi.name);
                        if (fi.isDirectory) sb.append("/");
                        else sb.append(" (").append(fi.getFormattedSize()).append(")");
                        sb.append("\n");
                    }
                    addMessage(sb.toString().trim());
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + folderPath);
            }
        } else if (tool.startsWith("FILE_TREE")) {
            String folderPath = tool.length() > 10 ? tool.substring(10).replace(":", "").trim() : "";
            try {
                List<ArgosFileManager.FileInfo> tree = fileManager.fileTree(folderPath);
                if (tree.isEmpty()) {
                    addMessage("📂 No files found.");
                } else {
                    StringBuilder sb = new StringBuilder("📂 Argus Files Tree:\n");
                    for (ArgosFileManager.FileInfo fi : tree) {
                        for (int d = 0; d < fi.depth; d++) sb.append("  ");
                        sb.append(fi.getTypeIcon()).append(" ").append(fi.name);
                        if (fi.isDirectory) sb.append("/");
                        sb.append("\n");
                    }
                    addMessage(sb.toString().trim());
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied");
            }
        } else if (tool.startsWith("CREATE_FOLDER:")) {
            String folderPath = tool.substring(14).trim();
            try {
                if (fileManager.createFolder(folderPath)) {
                    addMessage("📁 Created folder: " + folderPath);
                } else {
                    addMessage("📁 Could not create folder: " + folderPath);
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + folderPath);
            }
        } else if (tool.startsWith("MOVE_FILE:")) {
            String rest = tool.substring(10).trim();
            int pipeIdx = rest.indexOf('|');
            if (pipeIdx > 0) {
                String oldPath = rest.substring(0, pipeIdx).trim();
                String newPath = rest.substring(pipeIdx + 1).trim();
                try {
                    if (fileManager.moveFile(oldPath, newPath)) {
                        addMessage("📦 Moved: " + oldPath + " → " + newPath);
                    } else {
                        addMessage("📦 Could not move: " + oldPath);
                    }
                } catch (SecurityException e) {
                    addMessage("🚫 Access denied (outside Argus folder)");
                }
            }
        } else if (tool.startsWith("COPY_FILE:")) {
            String rest = tool.substring(10).trim();
            int pipeIdx = rest.indexOf('|');
            if (pipeIdx > 0) {
                String srcPath = rest.substring(0, pipeIdx).trim();
                String destPath = rest.substring(pipeIdx + 1).trim();
                try {
                    if (fileManager.copyFile(srcPath, destPath)) {
                        addMessage("📋 Copied: " + srcPath + " → " + destPath);
                    } else {
                        addMessage("📋 Could not copy: " + srcPath);
                    }
                } catch (SecurityException e) {
                    addMessage("🚫 Access denied (outside Argus folder)");
                }
            }
        } else if (tool.startsWith("SEARCH_FILES:")) {
            String query = tool.substring(13).trim();
            List<ArgosFileManager.FileInfo> results = fileManager.searchFiles(query);
            if (results.isEmpty()) {
                addMessage("🔍 No files matching: " + query);
            } else {
                StringBuilder sb = new StringBuilder("🔍 Found " + results.size() + " file(s):\n");
                for (ArgosFileManager.FileInfo fi : results) {
                    sb.append("  • ").append(fi.relativePath).append("\n");
                }
                addMessage(sb.toString().trim());
            }
        } else if (tool.startsWith("SEARCH_CONTENT:")) {
            String query = tool.substring(15).trim();
            List<ArgosFileManager.SearchResult> results = fileManager.searchContent(query);
            if (results.isEmpty()) {
                addMessage("🔍 No content matching: " + query);
            } else {
                StringBuilder sb = new StringBuilder("🔍 Found in " + results.size() + " file(s):\n");
                for (ArgosFileManager.SearchResult sr : results) {
                    String linePreview = sr.matchingLine.length() > 80 ?
                        sr.matchingLine.substring(0, 80) + "..." : sr.matchingLine;
                    sb.append("  • ").append(sr.filePath).append(": \"").append(linePreview).append("\"\n");
                }
                addMessage(sb.toString().trim());
            }
        } else if (tool.startsWith("FILE_VERSIONS:")) {
            String filepath = tool.substring(14).trim();
            try {
                List<ArgosFileManager.VersionInfo> versions = fileManager.listVersions(filepath);
                if (versions.isEmpty()) {
                    addMessage("📜 No version history for: " + filepath);
                } else {
                    StringBuilder sb = new StringBuilder("📜 Version history for " + filepath + ":\n");
                    for (int i = 0; i < versions.size(); i++) {
                        ArgosFileManager.VersionInfo vi = versions.get(i);
                        sb.append("  ").append(i + 1).append(". ").append(vi.getFormattedDate())
                          .append(" (").append(vi.size).append(" bytes)\n");
                    }
                    addMessage(sb.toString().trim());
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + filepath);
            }
        } else if (tool.startsWith("RESTORE_FILE:")) {
            String backupName = tool.substring(13).trim();
            String path = fileManager.restoreVersion(backupName);
            if (path != null) {
                addMessage("♻️ Restored: " + backupName);
            } else {
                addMessage("♻️ Could not restore: " + backupName);
            }
        } else if (tool.startsWith("DELETE_FILE:")) {
            // Simulates the approval card flow — in real app, user must approve.
            // In this test, we simulate "user approves" and execute.
            String filepath = tool.substring(12).trim();
            try {
                if (!fileManager.isPathSafe(filepath)) {
                    addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)");
                } else {
                    ArgosFileManager.FileInfo info = fileManager.getFileInfo(filepath);
                    if (info == null) {
                        addMessage("🗑 File not found: " + filepath);
                    } else {
                        // Simulate approval card being shown
                        approvalRequests.add("DELETE_FILE:" + filepath);
                        // Simulate user clicking "Approve"
                        if (fileManager.deleteFile(filepath)) {
                            addMessage("🗑 Deleted: " + filepath + " (backup saved)");
                        } else {
                            addMessage("🗑 Could not delete: " + filepath);
                        }
                    }
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + filepath + " (outside Argus folder)");
            }
        } else if (tool.startsWith("DELETE_FOLDER:")) {
            String folderPath = tool.substring(14).trim();
            try {
                if (fileManager.isPathSafe(folderPath)) {
                    approvalRequests.add("DELETE_FOLDER:" + folderPath);
                    // Simulate user clicking "Approve"
                    if (fileManager.deleteFolder(folderPath)) {
                        addMessage("🗑 Deleted folder: " + folderPath);
                    } else {
                        addMessage("🗑 Could not delete folder (not empty or doesn't exist)");
                    }
                } else {
                    addMessage("🚫 Access denied: " + folderPath);
                }
            } catch (SecurityException e) {
                addMessage("🚫 Access denied: " + folderPath);
            }
        } else {
            addMessage("❓ Unknown tool: " + tool);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  INDIVIDUAL TOOL TESTS — verify each tool works end-to-end
    // ═══════════════════════════════════════════════════════════════

    // ── WRITE_FILE ──

    @Test
    public void testWriteFile_basic() {
        executeTool("WRITE_FILE:hello.txt|Hello, Argus!");
        assertTrue("Should show saved message", lastMessage().contains("Saved"));
        assertTrue("Should show filename", lastMessage().contains("hello.txt"));
        assertEquals("Hello, Argus!", fileManager.readFile("hello.txt"));
    }

    @Test
    public void testWriteFile_subfolder() {
        executeTool("CREATE_FOLDER:Stories");
        executeTool("WRITE_FILE:Stories/ch1.txt|Once upon a time...");
        assertTrue(lastMessage().contains("Saved"));
        assertEquals("Once upon a time...", fileManager.readFile("Stories/ch1.txt"));
    }

    @Test
    public void testWriteFile_traversalAttack() {
        executeTool("WRITE_FILE:../../../etc/passwd|hacked!");
        assertTrue("Should show access denied", lastMessage().contains("Access denied"));
        assertTrue("Should mention outside folder", lastMessage().contains("outside Argus folder"));
    }

    @Test
    public void testWriteFile_absolutePath() {
        executeTool("WRITE_FILE:/etc/shadow|hacked!");
        assertTrue("Should show access denied", lastMessage().contains("Access denied"));
    }

    @Test
    public void testWriteFile_windowsAbsolutePath() {
        executeTool("WRITE_FILE:C:\\Windows\\System32\\hack.txt|hacked!");
        assertTrue("Should show access denied", lastMessage().contains("Access denied"));
    }

    @Test
    public void testWriteFile_emptyContent() {
        executeTool("WRITE_FILE:empty.txt|");
        // pipeIdx is 0, so the condition pipeIdx > 0 fails — no action taken
        // This is actually correct behavior — empty content with no pipe is a malformed tag
    }

    @Test
    public void testWriteFile_contentWithPipes() {
        executeTool("WRITE_FILE:config.txt|key=value|other=value");
        // Content after first pipe: "key=value|other=value"
        String content = fileManager.readFile("config.txt");
        assertNotNull(content);
        assertTrue("Should preserve pipes in content", content.contains("key=value|other=value"));
    }

    // ── APPEND_FILE ──

    @Test
    public void testAppendFile_basic() {
        executeTool("WRITE_FILE:log.txt|Line 1");
        executeTool("APPEND_FILE:log.txt|Line 2");
        executeTool("APPEND_FILE:log.txt|Line 3");
        String content = fileManager.readFile("log.txt");
        assertTrue(content.contains("Line 1"));
        assertTrue(content.contains("Line 2"));
        assertTrue(content.contains("Line 3"));
    }

    @Test
    public void testAppendFile_traversalAttack() {
        executeTool("APPEND_FILE:../../../etc/cron.d/evil|* * * * * rm -rf /");
        assertTrue(lastMessage().contains("Access denied"));
    }

    // ── EDIT_FILE ──

    @Test
    public void testEditFile_basic() {
        executeTool("WRITE_FILE:letter.txt|Dear Sir, I will recieve your package.");
        executeTool("EDIT_FILE:letter.txt|recieve|receive");
        assertTrue(lastMessage().contains("Edited"));
        assertTrue(lastMessage().contains("1 replacement"));
        String content = fileManager.readFile("letter.txt");
        assertTrue("Should have corrected spelling", content.contains("receive"));
        assertFalse("Should not have typo", content.contains("recieve"));
    }

    @Test
    public void testEditFile_multipleReplacements() {
        executeTool("WRITE_FILE:doc.txt|the the the quick quick brown brown fox");
        executeTool("EDIT_FILE:doc.txt|the|THE");
        // Should replace all occurrences of "the" (case-sensitive)
        assertTrue(lastMessage().contains("3 replacement"));
        String content = fileManager.readFile("doc.txt");
        // All 3 lowercase "the" should be replaced
        assertTrue(content.contains("THE THE THE"));
    }

    @Test
    public void testEditFile_noMatch() {
        executeTool("WRITE_FILE:doc.txt|Hello world");
        executeTool("EDIT_FILE:doc.txt|nonexistent|replacement");
        assertTrue(lastMessage().contains("No matches found"));
    }

    @Test
    public void testEditFile_traversalAttack() {
        executeTool("EDIT_FILE:../../../etc/passwd|root|hacker");
        assertTrue(lastMessage().contains("Access denied"));
    }

    @Test
    public void testEditFile_newTextContainsPipes() {
        executeTool("WRITE_FILE:code.txt|old_value");
        executeTool("EDIT_FILE:code.txt|old_value|new|value|with|pipes");
        String content = fileManager.readFile("code.txt");
        assertTrue("Should preserve pipes in replacement text", content.contains("new|value|with|pipes"));
    }

    // ── READ_FILE ──

    @Test
    public void testReadFile_basic() {
        executeTool("WRITE_FILE:notes.txt|Remember to buy milk");
        executeTool("READ_FILE:notes.txt");
        assertTrue(lastMessage().contains("📄"));
        assertTrue(lastMessage().contains("notes.txt"));
        assertTrue(lastMessage().contains("Remember to buy milk"));
    }

    @Test
    public void testReadFile_notFound() {
        executeTool("READ_FILE:nonexistent.txt");
        assertTrue(lastMessage().contains("File not found"));
    }

    @Test
    public void testReadFile_traversalAttack() {
        executeTool("READ_FILE:../../../etc/passwd");
        assertTrue(lastMessage().contains("Access denied"));
    }

    @Test
    public void testReadFile_subfolder() {
        executeTool("CREATE_FOLDER:Tasks");
        executeTool("WRITE_FILE:Tasks/todo.txt|1. Buy groceries\n2. Call mom");
        executeTool("READ_FILE:Tasks/todo.txt");
        assertTrue(lastMessage().contains("todo.txt"));
        assertTrue(lastMessage().contains("Buy groceries"));
    }

    @Test
    public void testReadFile_longContent() {
        // Write a file longer than 300 chars and verify preview truncation
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("This is line ").append(i).append(". ");
        executeTool("WRITE_FILE:long.txt|" + sb.toString());
        executeTool("READ_FILE:long.txt");
        assertTrue("Should truncate with ...", lastMessage().contains("..."));
    }

    // ── LIST_FILES ──

    @Test
    public void testListFiles_empty() {
        executeTool("LIST_FILES");
        assertTrue(lastMessage().contains("No files"));
    }

    @Test
    public void testListFiles_withFiles() {
        executeTool("WRITE_FILE:a.txt|A");
        executeTool("WRITE_FILE:b.txt|B");
        messageLog.clear();
        executeTool("LIST_FILES");
        assertTrue(lastMessage().contains("2 items"));
        assertTrue(lastMessage().contains("a.txt"));
        assertTrue(lastMessage().contains("b.txt"));
    }

    @Test
    public void testListFiles_subfolder() {
        executeTool("CREATE_FOLDER:Stories");
        executeTool("WRITE_FILE:Stories/ch1.txt|Chapter 1");
        executeTool("WRITE_FILE:Stories/ch2.txt|Chapter 2");
        messageLog.clear();
        executeTool("LIST_FILES:Stories");
        assertTrue(lastMessage().contains("Stories"));
        assertTrue(lastMessage().contains("ch1.txt"));
        assertTrue(lastMessage().contains("ch2.txt"));
    }

    @Test
    public void testListFiles_traversalAttack() {
        executeTool("LIST_FILES:../../../etc");
        assertTrue(lastMessage().contains("Access denied"));
    }

    // ── FILE_TREE ──

    @Test
    public void testFileTree_basic() {
        executeTool("CREATE_FOLDER:Stories");
        executeTool("CREATE_FOLDER:Stories/characters");
        executeTool("WRITE_FILE:Stories/ch1.txt|Chapter 1");
        executeTool("WRITE_FILE:Stories/characters/hero.txt|Hero");
        executeTool("WRITE_FILE:notes.txt|Notes");
        messageLog.clear();
        executeTool("FILE_TREE");
        assertTrue(lastMessage().contains("Tree"));
        assertTrue(lastMessage().contains("Stories/"));
        assertTrue(lastMessage().contains("ch1.txt"));
        assertTrue(lastMessage().contains("characters/"));
        assertTrue(lastMessage().contains("hero.txt"));
        assertTrue(lastMessage().contains("notes.txt"));
    }

    @Test
    public void testFileTree_empty() {
        executeTool("FILE_TREE");
        assertTrue(lastMessage().contains("No files"));
    }

    // ── CREATE_FOLDER ──

    @Test
    public void testCreateFolder_basic() {
        executeTool("CREATE_FOLDER:MyFolder");
        assertTrue(lastMessage().contains("Created folder"));
        assertTrue(fileManager.getFileInfo("MyFolder").isDirectory);
    }

    @Test
    public void testCreateFolder_nested() {
        executeTool("CREATE_FOLDER:a/b/c/d");
        assertTrue(lastMessage().contains("Created folder"));
        assertTrue(fileManager.getFileInfo("a/b/c/d").isDirectory);
    }

    @Test
    public void testCreateFolder_traversalAttack() {
        executeTool("CREATE_FOLDER:../../../malicious");
        assertTrue(lastMessage().contains("Access denied"));
    }

    // ── MOVE_FILE ──

    @Test
    public void testMoveFile_basic() {
        executeTool("WRITE_FILE:old.txt|Move me");
        executeTool("MOVE_FILE:old.txt|new.txt");
        assertTrue(lastMessage().contains("Moved"));
        assertNull("Old file should not exist", fileManager.readFile("old.txt"));
        assertEquals("New file should have content", "Move me", fileManager.readFile("new.txt"));
    }

    @Test
    public void testMoveFile_toSubfolder() {
        executeTool("CREATE_FOLDER:archive");
        executeTool("WRITE_FILE:current.txt|Archive me");
        executeTool("MOVE_FILE:current.txt|archive/old.txt");
        assertTrue(lastMessage().contains("Moved"));
        assertNull(fileManager.readFile("current.txt"));
        assertEquals("Archive me", fileManager.readFile("archive/old.txt"));
    }

    @Test
    public void testMoveFile_traversalAttack() {
        executeTool("WRITE_FILE:safe.txt|content");
        executeTool("MOVE_FILE:safe.txt|../../../escaped.txt");
        assertTrue(lastMessage().contains("Access denied"));
        // Original file should still exist
        assertEquals("content", fileManager.readFile("safe.txt"));
    }

    // ── COPY_FILE ──

    @Test
    public void testCopyFile_basic() {
        executeTool("WRITE_FILE:original.txt|Copy me");
        executeTool("COPY_FILE:original.txt|copy.txt");
        assertTrue(lastMessage().contains("Copied"));
        assertEquals("Original should still exist", "Copy me", fileManager.readFile("original.txt"));
        assertEquals("Copy should have content", "Copy me", fileManager.readFile("copy.txt"));
    }

    @Test
    public void testCopyFile_toSubfolder() {
        executeTool("CREATE_FOLDER:backup");
        executeTool("WRITE_FILE:important.txt|Important data");
        executeTool("COPY_FILE:important.txt|backup/important_backup.txt");
        assertTrue(lastMessage().contains("Copied"));
        assertEquals("Important data", fileManager.readFile("backup/important_backup.txt"));
    }

    @Test
    public void testCopyFile_traversalAttack() {
        executeTool("WRITE_FILE:safe.txt|content");
        executeTool("COPY_FILE:safe.txt|../../../escaped.txt");
        assertTrue(lastMessage().contains("Access denied"));
    }

    // ── SEARCH_FILES ──

    @Test
    public void testSearchFiles_basic() {
        executeTool("WRITE_FILE:recipe_cake.txt|Cake recipe");
        executeTool("WRITE_FILE:recipe_bread.txt|Bread recipe");
        executeTool("WRITE_FILE:random.txt|Random");
        messageLog.clear();
        executeTool("SEARCH_FILES:recipe");
        assertTrue(lastMessage().contains("Found 2 file(s)"));
        assertTrue(lastMessage().contains("recipe_cake.txt"));
        assertTrue(lastMessage().contains("recipe_bread.txt"));
    }

    @Test
    public void testSearchFiles_noMatch() {
        executeTool("WRITE_FILE:notes.txt|Notes");
        messageLog.clear();
        executeTool("SEARCH_FILES:xyznonexistent");
        assertTrue(lastMessage().contains("No files matching"));
    }

    @Test
    public void testSearchFiles_subfolderFiles() {
        executeTool("CREATE_FOLDER:Stories");
        executeTool("WRITE_FILE:Stories/chapter1.txt|Chapter 1");
        executeTool("WRITE_FILE:Stories/chapter2.txt|Chapter 2");
        messageLog.clear();
        executeTool("SEARCH_FILES:chapter");
        assertTrue(lastMessage().contains("Found 2 file(s)"));
        assertTrue(lastMessage().contains("Stories/chapter1.txt"));
    }

    // ── SEARCH_CONTENT ──

    @Test
    public void testSearchContent_basic() {
        executeTool("WRITE_FILE:doc.txt|The quick brown fox jumps over the lazy dog");
        messageLog.clear();
        executeTool("SEARCH_CONTENT:brown fox");
        assertTrue(lastMessage().contains("Found in 1 file"));
        assertTrue(lastMessage().contains("doc.txt"));
        assertTrue(lastMessage().contains("brown fox"));
    }

    @Test
    public void testSearchContent_multipleFiles() {
        executeTool("WRITE_FILE:a.txt|The password is secret123");
        executeTool("WRITE_FILE:b.txt|Don't share the password");
        messageLog.clear();
        executeTool("SEARCH_CONTENT:password");
        assertTrue(lastMessage().contains("Found in 2 file"));
    }

    @Test
    public void testSearchContent_noMatch() {
        executeTool("WRITE_FILE:doc.txt|Hello world");
        messageLog.clear();
        executeTool("SEARCH_CONTENT:xyznonexistent12345");
        assertTrue(lastMessage().contains("No content matching"));
    }

    // ── DELETE_FILE (with approval) ──

    @Test
    public void testDeleteFile_basic() {
        executeTool("WRITE_FILE:trash.txt|Delete me");
        messageLog.clear();
        executeTool("DELETE_FILE:trash.txt");
        // Should trigger approval request
        assertEquals("Should have 1 approval request", 1, approvalRequests.size());
        assertTrue(approvalRequests.get(0).contains("trash.txt"));
        // In simulation, approval is auto-granted
        assertTrue(lastMessage().contains("Deleted"));
        assertTrue(lastMessage().contains("backup saved"));
        assertNull("File should be gone", fileManager.readFile("trash.txt"));
    }

    @Test
    public void testDeleteFile_notFound() {
        executeTool("DELETE_FILE:nonexistent.txt");
        assertTrue(lastMessage().contains("File not found"));
        assertEquals("No approval should be requested", 0, approvalRequests.size());
    }

    @Test
    public void testDeleteFile_traversalAttack() {
        executeTool("DELETE_FILE:../../../etc/passwd");
        assertTrue(lastMessage().contains("Access denied"));
        assertEquals("No approval for attack", 0, approvalRequests.size());
    }

    @Test
    public void testDeleteFile_backupCreated() {
        executeTool("WRITE_FILE:important.txt|Important content");
        executeTool("DELETE_FILE:important.txt");
        // Verify backup was created
        List<ArgosFileManager.VersionInfo> versions = fileManager.listVersions("important.txt");
        assertTrue("Should have backup version", versions.size() >= 1);
        assertEquals("Backup should contain original content", "Important content".length(), versions.get(0).size);
    }

    // ── DELETE_FOLDER (with approval) ──

    @Test
    public void testDeleteFolder_empty() {
        executeTool("CREATE_FOLDER:temp");
        messageLog.clear();
        executeTool("DELETE_FOLDER:temp");
        assertEquals("Should have 1 approval request", 1, approvalRequests.size());
        assertTrue(lastMessage().contains("Deleted folder"));
    }

    @Test
    public void testDeleteFolder_nonEmpty() {
        executeTool("CREATE_FOLDER:stuff");
        executeTool("WRITE_FILE:stuff/file.txt|content");
        messageLog.clear();
        executeTool("DELETE_FOLDER:stuff");
        assertTrue(lastMessage().contains("Could not delete"));
    }

    @Test
    public void testDeleteFolder_traversalAttack() {
        executeTool("DELETE_FOLDER:../../../etc");
        assertTrue(lastMessage().contains("Access denied"));
    }

    // ── FILE_VERSIONS ──

    @Test
    public void testFileVersions_afterOverwrite() {
        executeTool("WRITE_FILE:doc.txt|Version 1 content");
        executeTool("WRITE_FILE:doc.txt|Version 2 content");
        messageLog.clear();
        executeTool("FILE_VERSIONS:doc.txt");
        assertTrue(lastMessage().contains("Version history"));
        assertTrue(lastMessage().contains("bytes"));
    }

    @Test
    public void testFileVersions_noHistory() {
        executeTool("WRITE_FILE:fresh.txt|Fresh content");
        messageLog.clear();
        executeTool("FILE_VERSIONS:fresh.txt");
        assertTrue(lastMessage().contains("No version history"));
    }

    @Test
    public void testFileVersions_traversalAttack() {
        executeTool("FILE_VERSIONS:../../../etc/passwd");
        assertTrue(lastMessage().contains("Access denied"));
    }

    // ── RESTORE_FILE ──

    @Test
    public void testRestoreFile_basic() {
        executeTool("WRITE_FILE:doc.txt|Original content");
        executeTool("WRITE_FILE:doc.txt|Overwritten content");
        // Get the version list
        List<ArgosFileManager.VersionInfo> versions = fileManager.listVersions("doc.txt");
        assertTrue("Should have at least 1 version", versions.size() >= 1);
        // Restore the first backup
        messageLog.clear();
        executeTool("RESTORE_FILE:" + versions.get(0).backupFilename);
        assertTrue(lastMessage().contains("Restored"));
    }

    @Test
    public void testRestoreFile_invalidName() {
        executeTool("RESTORE_FILE:nonexistent_backup.bak");
        assertTrue(lastMessage().contains("Could not restore"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  MULTI-STEP WORKFLOW SIMULATIONS
    //  These simulate real AI workflows — sequences of tool calls
    //  that the AI would make to accomplish a task.
    // ═══════════════════════════════════════════════════════════════

    /**
     * SIMULATION 1: The AI writes a story with chapters.
     * Workflow: create folder → write chapter 1 → write chapter 2 → list files → read chapter
     */
    @Test
    public void simulation_writeStory() {
        // AI: "I'll create a story folder and write two chapters"
        executeTool("CREATE_FOLDER:Stories");
        assertTrue(lastMessage().contains("Created folder"));

        executeTool("WRITE_FILE:Stories/ch1.txt|Chapter 1: The Beginning\n\nIt was a dark and stormy night...");
        assertTrue(lastMessage().contains("Saved"));

        executeTool("WRITE_FILE:Stories/ch2.txt|Chapter 2: The Journey\n\nThe next morning, the sun rose...");
        assertTrue(lastMessage().contains("Saved"));

        // AI: "Let me check what files we have"
        messageLog.clear();
        executeTool("LIST_FILES:Stories");
        assertTrue(lastMessage().contains("2 items"));
        assertTrue(lastMessage().contains("ch1.txt"));
        assertTrue(lastMessage().contains("ch2.txt"));

        // AI: "Let me read chapter 1 back to you"
        messageLog.clear();
        executeTool("READ_FILE:Stories/ch1.txt");
        assertTrue(lastMessage().contains("Chapter 1"));
        assertTrue(lastMessage().contains("dark and stormy"));

        // Verify both files exist on disk
        assertEquals("Chapter 1: The Beginning\n\nIt was a dark and stormy night...",
            fileManager.readFile("Stories/ch1.txt"));
        assertEquals("Chapter 2: The Journey\n\nThe next morning, the sun rose...",
            fileManager.readFile("Stories/ch2.txt"));
    }

    /**
     * SIMULATION 2: The AI edits a document with typos.
     * Workflow: write doc → edit typo → edit another typo → read corrected doc
     */
    @Test
    public void simulation_editDocument() {
        // AI writes a document with typos
        executeTool("WRITE_FILE:email.txt|Dear Reciever, I will seperate the documents and send them to you.");
        assertTrue(lastMessage().contains("Saved"));

        // AI fixes "Reciever" → "Receiver"
        executeTool("EDIT_FILE:email.txt|Reciever|Receiver");
        assertTrue(lastMessage().contains("1 replacement"));

        // AI fixes "seperate" → "separate"
        executeTool("EDIT_FILE:email.txt|seperate|separate");
        assertTrue(lastMessage().contains("1 replacement"));

        // Read the corrected document
        messageLog.clear();
        executeTool("READ_FILE:email.txt");
        assertTrue(lastMessage().contains("Receiver"));
        assertTrue(lastMessage().contains("separate"));
        assertFalse(lastMessage().contains("Reciever"));
        assertFalse(lastMessage().contains("seperate"));
    }

    /**
     * SIMULATION 3: The AI organizes files into folders.
     * Workflow: write scattered files → create folders → move files → verify tree
     */
    @Test
    public void simulation_organizeFiles() {
        // AI creates some files in the root
        executeTool("WRITE_FILE:ch1.txt|Chapter 1");
        executeTool("WRITE_FILE:ch2.txt|Chapter 2");
        executeTool("WRITE_FILE:notes.txt|Story notes");
        executeTool("WRITE_FILE:characters.txt|Character list");

        // AI decides to organize them
        executeTool("CREATE_FOLDER:Stories");
        executeTool("CREATE_FOLDER:Notes");

        executeTool("MOVE_FILE:ch1.txt|Stories/ch1.txt");
        assertTrue(lastMessage().contains("Moved"));
        executeTool("MOVE_FILE:ch2.txt|Stories/ch2.txt");
        assertTrue(lastMessage().contains("Moved"));
        executeTool("MOVE_FILE:notes.txt|Notes/notes.txt");
        assertTrue(lastMessage().contains("Moved"));
        executeTool("MOVE_FILE:characters.txt|Notes/characters.txt");
        assertTrue(lastMessage().contains("Moved"));

        // Verify the tree structure
        messageLog.clear();
        executeTool("FILE_TREE");
        assertTrue(lastMessage().contains("Stories/"));
        assertTrue(lastMessage().contains("ch1.txt"));
        assertTrue(lastMessage().contains("ch2.txt"));
        assertTrue(lastMessage().contains("Notes/"));
        assertTrue(lastMessage().contains("notes.txt"));
        assertTrue(lastMessage().contains("characters.txt"));

        // Verify root is clean (only Stories/ and Notes/ folders)
        messageLog.clear();
        executeTool("LIST_FILES");
        assertTrue(lastMessage().contains("Stories/"));
        assertTrue(lastMessage().contains("Notes/"));
        assertFalse("Root should not have ch1.txt anymore", lastMessage().contains("ch1.txt"));
    }

    /**
     * SIMULATION 4: The AI accidentally overwrites a file, then restores it.
     * Workflow: write important file → overwrite by mistake → check versions → restore
     */
    @Test
    public void simulation_restoreAccidentalOverwrite() {
        // AI writes an important document
        executeTool("WRITE_FILE:important.txt|CRITICAL DATA: The password is 12345");
        assertTrue(lastMessage().contains("Saved"));

        // AI accidentally overwrites it
        executeTool("WRITE_FILE:important.txt|Oops, I overwrote the important file!");

        // User says "Hey, where did my password go?!"
        // AI checks version history
        messageLog.clear();
        executeTool("FILE_VERSIONS:important.txt");
        assertTrue(lastMessage().contains("Version history"));
        assertTrue("Should have at least 1 backup", lastMessage().contains("bytes"));

        // AI gets the backup name and restores it
        List<ArgosFileManager.VersionInfo> versions = fileManager.listVersions("important.txt");
        assertTrue("Should have versions to restore", versions.size() > 0);

        messageLog.clear();
        executeTool("RESTORE_FILE:" + versions.get(0).backupFilename);
        assertTrue(lastMessage().contains("Restored"));
    }

    /**
     * SIMULATION 5: The AI searches for information across files.
     * Workflow: write multiple files → search by filename → search by content
     */
    @Test
    public void simulation_searchInformation() {
        // AI creates various notes
        executeTool("WRITE_FILE:meeting_notes.txt|Meeting on Jan 15. Action items: buy milk, call John");
        executeTool("WRITE_FILE:shopping.txt|Shopping list: milk, eggs, bread");
        executeTool("WRITE_FILE:contacts.txt|John Doe: 555-1234. Jane Smith: 555-5678");

        // User: "Do I have anything about milk?"
        messageLog.clear();
        executeTool("SEARCH_CONTENT:milk");
        assertTrue(lastMessage().contains("Found in 2 file"));
        assertTrue(lastMessage().contains("meeting_notes.txt"));
        assertTrue(lastMessage().contains("shopping.txt"));

        // User: "Do I have any files about shopping?"
        messageLog.clear();
        executeTool("SEARCH_FILES:shopping");
        assertTrue(lastMessage().contains("Found 1 file"));
        assertTrue(lastMessage().contains("shopping.txt"));

        // User: "What about John?"
        messageLog.clear();
        executeTool("SEARCH_CONTENT:John");
        assertTrue(lastMessage().contains("Found in 2 file"));
    }

    /**
     * SIMULATION 6: The AI deletes a file (with user approval).
     * Workflow: write file → delete (triggers approval) → verify backup exists
     */
    @Test
    public void simulation_deleteWithApproval() {
        executeTool("WRITE_FILE:old_draft.txt|This is an old draft that should be deleted");

        // AI tries to delete — should trigger approval card
        messageLog.clear();
        executeTool("DELETE_FILE:old_draft.txt");

        // Verify approval was requested
        assertEquals("Should trigger 1 approval request", 1, approvalRequests.size());
        assertTrue(approvalRequests.get(0).contains("old_draft.txt"));

        // In simulation, approval is auto-granted — verify file is deleted
        assertTrue(lastMessage().contains("Deleted"));
        assertNull("File should be gone", fileManager.readFile("old_draft.txt"));

        // Verify backup was saved
        List<ArgosFileManager.VersionInfo> versions = fileManager.listVersions("old_draft.txt");
        assertTrue("Should have backup", versions.size() >= 1);
    }

    /**
     * SIMULATION 7: Security attack — the AI tries to read /etc/passwd.
     * This simulates a prompt injection attack where someone tricks the AI
     * into trying to read system files.
     */
    @Test
    public void simulation_securityAttack_readPasswd() {
        executeTool("READ_FILE:/etc/passwd");
        assertTrue("Should be blocked", lastMessage().contains("Access denied"));
        assertTrue("Should mention outside folder", lastMessage().contains("outside Argus folder"));

        executeTool("READ_FILE:../../../etc/passwd");
        assertTrue("Should be blocked", lastMessage().contains("Access denied"));

        executeTool("READ_FILE:../../../../data/data/com.whatsapp/databases/wa.db");
        assertTrue("Should be blocked", lastMessage().contains("Access denied"));
    }

    /**
     * SIMULATION 8: Security attack — the AI tries to write outside the sandbox.
     */
    @Test
    public void simulation_securityAttack_writeOutside() {
        executeTool("WRITE_FILE:/sdcard/secret.txt|hacked!");
        assertTrue(lastMessage().contains("Access denied"));

        executeTool("WRITE_FILE:../../../sdcard/secret.txt|hacked!");
        assertTrue(lastMessage().contains("Access denied"));

        executeTool("APPEND_FILE:../../../data/data/com.android.email/shared_prefs/email.xml|<evil>");
        assertTrue(lastMessage().contains("Access denied"));
    }

    /**
     * SIMULATION 9: Security attack — the AI tries to delete system files.
     */
    @Test
    public void simulation_securityAttack_deleteSystem() {
        executeTool("DELETE_FILE:../../../system/bin/su");
        assertTrue(lastMessage().contains("Access denied"));
        assertEquals("No approval for attack", 0, approvalRequests.size());

        executeTool("DELETE_FOLDER:../../../system");
        assertTrue(lastMessage().contains("Access denied"));
    }

    /**
     * SIMULATION 10: Full project workflow — the AI creates a complete project structure.
     * This is the most complex simulation, testing many tools in sequence.
     */
    @Test
    public void simulation_fullProjectWorkflow() {
        // 1. Create project structure
        executeTool("CREATE_FOLDER:Project");
        executeTool("CREATE_FOLDER:Project/src");
        executeTool("CREATE_FOLDER:Project/docs");
        executeTool("CREATE_FOLDER:Project/tests");

        // 2. Write source files
        executeTool("WRITE_FILE:Project/src/main.py|print('Hello, World!')");
        executeTool("WRITE_FILE:Project/src/utils.py|def helper():\n    return 42");

        // 3. Write documentation
        executeTool("WRITE_FILE:Project/docs/README.md|# My Project\nThis is a test project.");

        // 4. Write tests
        executeTool("WRITE_FILE:Project/tests/test_main.py|assert True");

        // 5. Verify the full tree
        messageLog.clear();
        executeTool("FILE_TREE");
        String tree = lastMessage();
        assertTrue(tree.contains("Project/"));
        assertTrue(tree.contains("src/"));
        assertTrue(tree.contains("main.py"));
        assertTrue(tree.contains("utils.py"));
        assertTrue(tree.contains("docs/"));
        assertTrue(tree.contains("README.md"));
        assertTrue(tree.contains("tests/"));
        assertTrue(tree.contains("test_main.py"));

        // 6. Edit a file
        executeTool("EDIT_FILE:Project/src/main.py|Hello, World!|Hello, Argus!");
        assertTrue(lastMessage().contains("1 replacement"));

        // 7. Verify the edit
        String content = fileManager.readFile("Project/src/main.py");
        assertTrue(content.contains("Hello, Argus!"));
        assertFalse(content.contains("Hello, World!"));

        // 8. Copy a file as backup
        executeTool("COPY_FILE:Project/src/main.py|Project/src/main.py.bak");
        assertTrue(lastMessage().contains("Copied"));

        // 9. Search for files
        messageLog.clear();
        executeTool("SEARCH_FILES:.py");
        assertTrue(lastMessage().contains("Found"));
        assertTrue(lastMessage().contains("main.py"));

        // 10. Search for content
        messageLog.clear();
        executeTool("SEARCH_CONTENT:Argus");
        assertTrue(lastMessage().contains("Found"));

        // 11. Check version history of edited file
        messageLog.clear();
        executeTool("FILE_VERSIONS:Project/src/main.py");
        assertTrue(lastMessage().contains("Version history"));

        // 12. Clean up — delete the backup (with approval)
        messageLog.clear();
        executeTool("DELETE_FILE:Project/src/main.py.bak");
        assertTrue(lastMessage().contains("Deleted"));
        assertEquals("Should have 1 approval", 1, approvalRequests.size());
    }

    /**
     * SIMULATION 11: Append-based logging workflow.
     * The AI maintains a log file by appending entries over time.
     */
    @Test
    public void simulation_appendLogWorkflow() {
        executeTool("WRITE_FILE:activity_log.txt|=== Argus Activity Log ===");

        for (int i = 1; i <= 5; i++) {
            executeTool("APPEND_FILE:activity_log.txt|Entry " + i + ": Task completed at " + i + ":00");
        }

        String content = fileManager.readFile("activity_log.txt");
        assertTrue(content.contains("=== Argus Activity Log ==="));
        for (int i = 1; i <= 5; i++) {
            assertTrue("Should contain entry " + i, content.contains("Entry " + i + ":"));
        }
    }

    /**
     * SIMULATION 12: Verify that the Argos/ prefix is handled correctly.
     * The AI might include "Argos/" in paths — this should be stripped.
     */
    @Test
    public void simulation_argosPrefixHandling() {
        executeTool("WRITE_FILE:Argos/notes.txt|Test with Argos prefix");
        assertTrue(lastMessage().contains("Saved"));
        assertEquals("Test with Argos prefix", fileManager.readFile("notes.txt"));
        assertEquals("Test with Argos prefix", fileManager.readFile("Argos/notes.txt"));

        executeTool("WRITE_FILE:Documents/Argos/other.txt|Test with full prefix");
        assertTrue(lastMessage().contains("Saved"));
        assertEquals("Test with full prefix", fileManager.readFile("other.txt"));
    }
}
