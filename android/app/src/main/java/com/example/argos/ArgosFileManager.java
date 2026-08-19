package com.example.argos;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Manages the dedicated "Argus" folder where the AI can create, read, edit,
 * and delete files — but ONLY within this sandbox.
 *
 * Security model:
 *  - All paths are resolved via resolveSecurePath() which uses canonical path
 *    normalization + prefix validation against the Argos folder root.
 *  - Path traversal (../), absolute paths, and symlinks are blocked.
 *  - The AI CANNOT access any file outside Documents/Argos/.
 *  - Before any overwrite or delete, the previous version is backed up to
 *    .argos_meta/versions/ so the user can always restore it.
 *
 * Folder structure:
 *  Documents/Argos/
 *  ├── Stories/          (AI-organized subfolders)
 *  ├── Notes/
 *  ├── Tasks/
 *  ├── Media/
 *  ├── Scheduled_Tasks.txt
 *  └── .argos_meta/
 *      └── versions/     (auto-backups before overwrite/delete)
 *
 * Folder location strategy:
 *  - Android 9 and below: /sdcard/Documents/Argos/  (WRITE_EXTERNAL_STORAGE)
 *  - Android 10:          /sdcard/Documents/Argos/  (requestLegacyExternalStorage)
 *  - Android 11+:         app-specific external dir  (no permission needed)
 *                         /sdcard/Android/data/<pkg>/files/Documents/Argos/
 */
public class ArgosFileManager {

    private static final String TAG = "ArgosFiles";
    private static final String FOLDER_NAME = "Argos";
    private static final String TASKS_FILE = "Scheduled_Tasks.txt";
    private static final String META_DIR = ".argos_meta";
    private static final String VERSIONS_DIR = "versions";
    private static final int MAX_VERSIONS_PER_FILE = 10; // keep last 10 versions
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB max per file

    private final Context context;
    private File argosFolder;
    private File versionsFolder;

    /** Production constructor — resolves the Argos folder using Android APIs. */
    public ArgosFileManager(Context context) {
        this.context = context;
        this.argosFolder = getOrCreateFolder();
        this.versionsFolder = ensureVersionsDir();
    }

    /**
     * Test-only constructor — uses a specified directory as the sandbox root.
     * Bypasses Android folder resolution so tests can run on a plain JVM.
     */
    protected ArgosFileManager(File testRoot, boolean isTest) {
        this.context = null;
        this.argosFolder = testRoot;
        if (!argosFolder.exists()) argosFolder.mkdirs();
        this.versionsFolder = ensureVersionsDir();
    }

    /** Returns the Argos folder, creating it if necessary. */
    private File getOrCreateFolder() {
        File folder = null;

        // Try the public Documents/Argos folder first (most user-accessible)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (docs != null) {
                folder = new File(docs, FOLDER_NAME);
            }
        }

        // If public folder isn't writable, fall back to app-specific external storage
        if (folder == null || !canWrite(folder)) {
            File externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (externalDir != null) {
                folder = new File(externalDir, FOLDER_NAME);
            } else {
                folder = new File(context.getFilesDir(), FOLDER_NAME);
            }
        }

        if (!folder.exists()) {
            boolean created = folder.mkdirs();
            if (!created && !folder.exists()) {
                folder.getParentFile().mkdirs();
                folder.mkdir();
            }
        }

        Log.i(TAG, "Argos folder: " + folder.getAbsolutePath());
        return folder;
    }

    /** Ensure the .argos_meta/versions/ directory exists for backups. */
    private File ensureVersionsDir() {
        File meta = new File(argosFolder, META_DIR);
        if (!meta.exists()) meta.mkdirs();
        File versions = new File(meta, VERSIONS_DIR);
        if (!versions.exists()) versions.mkdirs();
        return versions;
    }

    private boolean canWrite(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            File test = new File(dir, ".argos_test");
            boolean ok = test.createNewFile();
            if (ok) test.delete();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SECURE PATH RESOLUTION — THE SANDBOX ENFORCER
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolves a user/AI-provided path to a secure File within the Argos sandbox.
     *
     * This is the critical security method. It:
     *  1. Strips any leading "Argos/" or "Documents/Argos/" prefix
     *  2. Resolves the path relative to the Argos folder
     *  3. Canonicalizes the result (resolves .., ., symlinks)
     *  4. Verifies the canonical path starts with the Argos folder's canonical path
     *  5. Throws SecurityException if the path escapes the sandbox
     *
     * @param inputPath  relative path like "Stories/chapter1.txt" or "notes.txt"
     * @return a verified File within the Argos folder
     * @throws SecurityException if the path attempts to escape the sandbox
     */
    private File resolveSecurePath(String inputPath) throws SecurityException {
        if (inputPath == null || inputPath.trim().isEmpty()) {
            throw new SecurityException("Empty path");
        }

        String path = inputPath.trim();

        // Strip leading "Argos/" or "Documents/Argos/" if the AI includes it
        if (path.startsWith("Documents/Argos/")) {
            path = path.substring("Documents/Argos/".length());
        } else if (path.startsWith("Argos/")) {
            path = path.substring("Argos/".length());
        }

        // Reject absolute paths — they are always an attempt to escape the sandbox.
        // On Unix: /etc/passwd, /sdcard/secret.txt
        // On Windows: C:\Windows\System32, D:\secret.txt
        if (path.startsWith("/") || path.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new SecurityException(
                "Absolute paths are not allowed: '" + inputPath + "'. Use relative paths within the Argus folder.");
        }

        // Strip leading slashes (make it relative) — for paths like "/foo" that
        // weren't caught by the absolute path check (e.g. just "/")
        while (path.startsWith("/")) path = path.substring(1);

        // Block obvious traversal attempts early (defense in depth)
        // The canonical path check below is the real enforcer, but this
        // catches the most common attacks before filesystem operations.
        if (path.contains("..")) {
            // Don't immediately reject — canonical path check will handle it.
            // Some legitimate paths might contain ".." in folder names (unlikely but safe to let canonical check decide)
        }

        // Resolve relative to the Argos folder
        File resolved = new File(argosFolder, path);

        // Canonicalize — this resolves "..", ".", and symlinks to the real path
        String canonicalArgos;
        String canonicalResolved;
        try {
            canonicalArgos = argosFolder.getCanonicalPath();
            canonicalResolved = resolved.getCanonicalPath();
        } catch (IOException e) {
            throw new SecurityException("Cannot resolve path: " + e.getMessage());
        }

        // THE critical check: resolved path must be inside the Argos folder
        // Use File.separator to avoid prefix matching issues (e.g. /Argos2 matching /Argos)
        if (!canonicalResolved.equals(canonicalArgos) &&
            !canonicalResolved.startsWith(canonicalArgos + File.separator)) {
            throw new SecurityException(
                "Access denied: path '" + inputPath + "' resolves outside the Argus folder. " +
                "Resolved: " + canonicalResolved + " | Sandbox: " + canonicalArgos);
        }

        return resolved;
    }

    /**
     * Check if a path is within the sandbox (for read-only checks).
     * Returns true if safe, false otherwise.
     */
    public boolean isPathSafe(String inputPath) {
        try {
            resolveSecurePath(inputPath);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Returns the absolute path of the Argos folder (for display). */
    public String getFolderPath() {
        return argosFolder.getAbsolutePath();
    }

    // ═══════════════════════════════════════════════════════════════
    //  VERSION HISTORY — SAFETY NET FOR DESTRUCTIVE OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Back up a file to .argos_meta/versions/ before it's overwritten or deleted.
     * Filename format: originalname.YYYY-MM-DD_HHmmss.bak
     */
    private void backupVersion(File file) {
        if (file == null || !file.exists()) return;

        // Use millisecond precision to avoid collisions when multiple writes
        // happen within the same second
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.getDefault()).format(new Date());
        // Replace path separators in the relative path with underscores for the backup filename
        String relativePath = getRelativePath(file);
        String safeBackupName = relativePath.replace("/", "_").replace("\\", "_") + "." + timestamp + ".bak";
        File backup = new File(versionsFolder, safeBackupName);

        try (FileInputStream fis = new FileInputStream(file);
             FileOutputStream fos = new FileOutputStream(backup)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            Log.i(TAG, "Backed up: " + file.getName() + " → " + backup.getName());
        } catch (Exception e) {
            Log.w(TAG, "Could not back up " + file.getName() + ": " + e.getMessage());
        }

        // Prune old versions — keep only the most recent MAX_VERSIONS_PER_FILE
        pruneOldVersions(relativePath);
    }

    /** Remove old backup versions, keeping only the most recent N. */
    private void pruneOldVersions(String relativePath) {
        String prefix = relativePath.replace("/", "_").replace("\\", "_") + ".";
        File[] backups = versionsFolder.listFiles((dir, name) ->
            name.startsWith(prefix) && name.endsWith(".bak"));

        if (backups == null || backups.length <= MAX_VERSIONS_PER_FILE) return;

        // Sort by last-modified descending (newest first)
        List<File> backupList = new ArrayList<>();
        Collections.addAll(backupList, backups);
        Collections.sort(backupList, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        // Delete everything after the first MAX_VERSIONS_PER_FILE
        for (int i = MAX_VERSIONS_PER_FILE; i < backupList.size(); i++) {
            backupList.get(i).delete();
            Log.i(TAG, "Pruned old version: " + backupList.get(i).getName());
        }
    }

    /** Get the path of a file relative to the Argos folder. */
    private String getRelativePath(File file) {
        String root = argosFolder.getAbsolutePath();
        String abs = file.getAbsolutePath();
        if (abs.startsWith(root)) {
            String rel = abs.substring(root.length());
            while (rel.startsWith("/") || rel.startsWith("\\")) rel = rel.substring(1);
            // Normalize to forward slashes for consistency across platforms
            return rel.replace("\\", "/");
        }
        return file.getName();
    }

    /**
     * List all backup versions for a given file path.
     * @return list of VersionInfo objects, newest first
     */
    public List<VersionInfo> listVersions(String filePath) {
        List<VersionInfo> result = new ArrayList<>();
        // Let SecurityException propagate so callers can show "Access denied"
        File target = resolveSecurePath(filePath);
        String relativePath = getRelativePath(target);
        String prefix = relativePath.replace("/", "_").replace("\\", "_") + ".";
        File[] backups = versionsFolder.listFiles((dir, name) ->
            name.startsWith(prefix) && name.endsWith(".bak"));

        if (backups == null) return result;

        for (File b : backups) {
            result.add(new VersionInfo(b.getName(), b.length(), b.lastModified(), b.getAbsolutePath()));
        }

        Collections.sort(result, (a, b) -> Long.compare(b.timestamp, a.timestamp));
        return result;
    }

    /**
     * Restore a file from a backup version.
     * @param backupFilename  the .bak filename in the versions directory
     * @return the restored file path, or null on failure
     */
    public String restoreVersion(String backupFilename) {
        // Sanitize the backup filename — it must be a simple filename, no paths
        String safeName = backupFilename.replace("/", "_").replace("\\", "_").replace("..", "");
        File backup = new File(versionsFolder, safeName);
        if (!backup.exists()) return null;

        // Extract the original relative path from the backup filename
        // Format: originalname_With_Underscores.YYYY-MM-DD_HHmmss.bak
        String name = backup.getName();
        // Remove .bak
        name = name.substring(0, name.length() - 4);
        // Remove timestamp (last .YYYY-MM-DD_HHmmss)
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            name = name.substring(0, lastDot);
        }
        // Convert underscores back to path separators — but we can't know which underscores
        // were originally separators vs. literal underscores. So we restore to a flat filename
        // in the root of the Argos folder.
        String restoredName = name;
        File restored = new File(argosFolder, restoredName);

        try (FileInputStream fis = new FileInputStream(backup);
             FileOutputStream fos = new FileOutputStream(restored)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            Log.i(TAG, "Restored: " + backup.getName() + " → " + restored.getAbsolutePath());
            return restored.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  FILE OPERATIONS — ALL SANDBOXED
    // ═══════════════════════════════════════════════════════════════

    /**
     * Write a file to the Argos folder (supports subfolders).
     * @param filepath  e.g. "shopping_list.txt" or "Stories/chapter1.txt"
     * @param content   the text content to write
     * @param append    if true, append to existing file; if false, overwrite
     * @return the file path on success, null on failure
     * @throws SecurityException if the path escapes the sandbox
     */
    public String writeFile(String filepath, String content, boolean append) {
        File file = resolveSecurePath(filepath);

        // Check file size limit
        if (content.length() > MAX_FILE_SIZE) {
            Log.e(TAG, "Content too large: " + content.length() + " chars (max " + MAX_FILE_SIZE + ")");
            return null;
        }

        // Create parent directories if needed
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // Back up existing file before overwriting
        if (!append && file.exists()) {
            backupVersion(file);
        }

        try (FileWriter writer = new FileWriter(file, append)) {
            if (append && file.exists()) {
                writer.write("\n");
            }
            writer.write(content);
            Log.i(TAG, "Wrote " + content.length() + " chars to " + file.getAbsolutePath());
            return file.getAbsolutePath();
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write file " + filepath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Read a file from the Argos folder.
     * @param filepath  e.g. "shopping_list.txt" or "Notes/ideas.txt"
     * @return the file content, or null if the file doesn't exist or can't be read
     * @throws SecurityException if the path escapes the sandbox
     */
    public String readFile(String filepath) {
        File file = resolveSecurePath(filepath);

        if (!file.exists()) return null;
        if (file.isDirectory()) return null; // can't read a directory as text

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), MAX_FILE_SIZE)];
            int len = fis.read(buffer);
            if (len > 0) {
                return new String(buffer, 0, len, "UTF-8");
            }
            return "";
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read file " + filepath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Delete a file from the Argos folder.
     * Backs up the file before deleting.
     * @param filepath  e.g. "old_notes.txt" or "Stories/draft.txt"
     * @return true if deleted, false otherwise
     * @throws SecurityException if the path escapes the sandbox
     */
    public boolean deleteFile(String filepath) {
        File file = resolveSecurePath(filepath);

        if (!file.exists()) return false;
        if (file.isDirectory()) return false; // use deleteFolder for directories

        // Back up before deleting
        backupVersion(file);

        boolean deleted = file.delete();
        if (deleted) {
            Log.i(TAG, "Deleted: " + filepath);
        }
        return deleted;
    }

    /**
     * Create a folder within the Argos sandbox.
     * @param folderPath  e.g. "Stories" or "Stories/characters"
     * @return true if created or already exists, false on failure
     * @throws SecurityException if the path escapes the sandbox
     */
    public boolean createFolder(String folderPath) {
        File folder = resolveSecurePath(folderPath);
        if (folder.exists()) return folder.isDirectory();
        return folder.mkdirs();
    }

    /**
     * Delete a folder within the Argos sandbox (only if empty).
     * @param folderPath  e.g. "Stories/old_drafts"
     * @return true if deleted, false otherwise
     * @throws SecurityException if the path escapes the sandbox
     */
    public boolean deleteFolder(String folderPath) {
        File folder = resolveSecurePath(folderPath);
        if (!folder.exists() || !folder.isDirectory()) return false;
        // Only delete empty folders — don't recursively delete
        File[] children = folder.listFiles();
        if (children != null && children.length > 0) return false;
        return folder.delete();
    }

    /**
     * Move/rename a file within the Argos sandbox.
     * @param oldPath  source path
     * @param newPath  destination path
     * @return true if moved, false otherwise
     * @throws SecurityException if either path escapes the sandbox
     */
    public boolean moveFile(String oldPath, String newPath) {
        File source = resolveSecurePath(oldPath);
        File dest = resolveSecurePath(newPath);

        if (!source.exists() || source.isDirectory()) return false;

        // Create parent dirs for destination
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // Back up if destination already exists
        if (dest.exists()) backupVersion(dest);

        return source.renameTo(dest);
    }

    /**
     * Copy a file within the Argos sandbox.
     * @param srcPath  source path
     * @param destPath  destination path
     * @return true if copied, false otherwise
     * @throws SecurityException if either path escapes the sandbox
     */
    public boolean copyFile(String srcPath, String destPath) {
        File source = resolveSecurePath(srcPath);
        File dest = resolveSecurePath(destPath);

        if (!source.exists() || source.isDirectory()) return false;

        // Create parent dirs for destination
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // Back up if destination already exists
        if (dest.exists()) backupVersion(dest);

        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy " + srcPath + " → " + destPath + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Edit a file by finding and replacing text.
     * @param filepath  the file to edit
     * @param oldText   the text to find
     * @param newText   the text to replace it with
     * @return number of replacements made, or -1 on error
     * @throws SecurityException if the path escapes the sandbox
     */
    public int editFile(String filepath, String oldText, String newText) {
        File file = resolveSecurePath(filepath);
        if (!file.exists() || file.isDirectory()) return -1;

        String content = readFile(filepath);
        if (content == null) return -1;

        // Count occurrences
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(oldText, idx)) >= 0) {
            count++;
            idx += oldText.length();
        }

        if (count == 0) return 0; // no matches found

        // Replace all occurrences
        String newContent = content.replace(oldText, newText);

        // Back up before overwriting
        backupVersion(file);

        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(newContent);
            Log.i(TAG, "Edited " + filepath + ": " + count + " replacement(s)");
            return count;
        } catch (Exception e) {
            Log.e(TAG, "Failed to edit " + filepath + ": " + e.getMessage());
            return -1;
        }
    }

    /**
     * List files and folders in a directory within the Argos sandbox.
     * @param folderPath  folder to list (empty string or "/" for root)
     * @return list of FileInfo objects
     * @throws SecurityException if the path escapes the sandbox
     */
    public List<FileInfo> listFiles(String folderPath) {
        File folder;
        if (folderPath == null || folderPath.trim().isEmpty() || folderPath.equals("/")) {
            folder = argosFolder;
        } else {
            folder = resolveSecurePath(folderPath);
        }

        if (!folder.exists() || !folder.isDirectory()) return new ArrayList<>();

        List<FileInfo> result = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files == null) return result;

        for (File f : files) {
            // Skip hidden meta directory
            if (f.getName().startsWith(".")) continue;
            result.add(new FileInfo(
                f.getName(),
                f.length(),
                f.lastModified(),
                f.isDirectory(),
                getRelativePath(f)
            ));
        }

        // Sort: folders first, then by name
        Collections.sort(result, (a, b) -> {
            if (a.isDirectory && !b.isDirectory) return -1;
            if (!a.isDirectory && b.isDirectory) return 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return result;
    }

    /** List files in the root of the Argos folder (backward compatibility). */
    public List<FileInfo> listFiles() {
        return listFiles("");
    }

    /**
     * Get a recursive directory tree of the Argos folder.
     * @param folderPath  starting folder (empty for root)
     * @return list of FileInfo objects representing the tree (depth-first)
     * @throws SecurityException if the path escapes the sandbox
     */
    public List<FileInfo> fileTree(String folderPath) {
        List<FileInfo> result = new ArrayList<>();
        File start;
        if (folderPath == null || folderPath.trim().isEmpty() || folderPath.equals("/")) {
            start = argosFolder;
        } else {
            start = resolveSecurePath(folderPath);
        }

        if (!start.exists() || !start.isDirectory()) return result;

        buildTree(start, result, 0);
        return result;
    }

    private void buildTree(File dir, List<FileInfo> result, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return;

        // Sort: folders first, then by name
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, files);
        Collections.sort(sorted, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File f : sorted) {
            if (f.getName().startsWith(".")) continue; // skip hidden
            FileInfo info = new FileInfo(f.getName(), f.length(), f.lastModified(),
                f.isDirectory(), getRelativePath(f));
            info.depth = depth;
            result.add(info);
            if (f.isDirectory()) {
                buildTree(f, result, depth + 1);
            }
        }
    }

    /**
     * Search for files by name (case-insensitive substring match).
     * @param query  search query
     * @return list of matching FileInfo objects
     */
    public List<FileInfo> searchFiles(String query) {
        List<FileInfo> all = fileTree("");
        List<FileInfo> result = new ArrayList<>();
        String lower = query.toLowerCase();
        for (FileInfo fi : all) {
            if (fi.name.toLowerCase().contains(lower)) {
                result.add(fi);
            }
        }
        return result;
    }

    /**
     * Search file contents for a query (case-insensitive).
     * @param query  text to search for
     * @return list of SearchResult objects with file path and matching line
     */
    public List<SearchResult> searchContent(String query) {
        List<SearchResult> results = new ArrayList<>();
        List<FileInfo> all = fileTree("");
        String lower = query.toLowerCase();

        for (FileInfo fi : all) {
            if (fi.isDirectory) continue;
            try {
                String content = readFile(fi.relativePath);
                if (content != null && content.toLowerCase().contains(lower)) {
                    // Find the matching line
                    String[] lines = content.split("\n");
                    for (String line : lines) {
                        if (line.toLowerCase().contains(lower)) {
                            results.add(new SearchResult(fi.relativePath, line.trim(), fi.size));
                            break; // one match per file
                        }
                    }
                }
            } catch (Exception e) {
                // Skip unreadable files
            }
        }
        return results;
    }

    /**
     * Get detailed info about a specific file.
     * @param filepath  the file path
     * @return FileInfo object, or null if file doesn't exist
     */
    public FileInfo getFileInfo(String filepath) {
        try {
            File file = resolveSecurePath(filepath);
            if (!file.exists()) return null;
            return new FileInfo(file.getName(), file.length(), file.lastModified(),
                file.isDirectory(), getRelativePath(file));
        } catch (SecurityException e) {
            return null;
        }
    }

    /** Save the scheduled tasks list as a viewable text file. */
    public void saveScheduledTasks(List<String> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════\n");
        sb.append("       ARGOS SCHEDULED TASKS\n");
        sb.append("═══════════════════════════════════════\n");
        sb.append("Last updated: ").append(
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date())).append("\n\n");

        if (tasks == null || tasks.isEmpty()) {
            sb.append("No scheduled tasks.\n");
        } else {
            for (int i = 0; i < tasks.size(); i++) {
                sb.append(String.format(Locale.getDefault(), "%2d. %s\n", i + 1, tasks.get(i)));
            }
        }

        sb.append("\n═══════════════════════════════════════\n");
        sb.append("Generated by Argos AI Companion\n");
        writeFile(TASKS_FILE, sb.toString(), false);
    }

    /** Get the full path to the scheduled tasks file (for display). */
    public String getTasksFilePath() {
        return new File(argosFolder, TASKS_FILE).getAbsolutePath();
    }

    // ═══════════════════════════════════════════════════════════════
    //  DATA HOLDERS
    // ═══════════════════════════════════════════════════════════════

    /** File info holder with subfolder support. */
    public static class FileInfo {
        public final String name;
        public final long size;
        public final long lastModified;
        public final boolean isDirectory;
        public final String relativePath;
        public int depth; // for tree view

        public FileInfo(String name, long size, long lastModified, boolean isDirectory, String relativePath) {
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
            this.isDirectory = isDirectory;
            this.relativePath = relativePath;
            this.depth = 0;
        }

        /** Backward-compatible constructor (file only). */
        public FileInfo(String name, long size, long lastModified) {
            this(name, size, lastModified, false, name);
        }

        public String getFormattedSize() {
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
            return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0));
        }

        public String getFormattedDate() {
            return new SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
                .format(new Date(lastModified));
        }

        public String getTypeIcon() {
            if (isDirectory) return "📁";
            String lower = name.toLowerCase();
            if (lower.endsWith(".txt")) return "📄";
            if (lower.endsWith(".md")) return "📝";
            if (lower.endsWith(".json")) return "📋";
            if (lower.endsWith(".csv")) return "📊";
            if (lower.endsWith(".mp3") || lower.endsWith(".wav")) return "🎵";
            if (lower.endsWith(".mp4") || lower.endsWith(".mov")) return "🎬";
            if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".gif")) return "🖼";
            return "📄";
        }
    }

    /** Search result holder. */
    public static class SearchResult {
        public final String filePath;
        public final String matchingLine;
        public final long fileSize;

        public SearchResult(String filePath, String matchingLine, long fileSize) {
            this.filePath = filePath;
            this.matchingLine = matchingLine;
            this.fileSize = fileSize;
        }
    }

    /** Version history entry holder. */
    public static class VersionInfo {
        public final String backupFilename;
        public final long size;
        public final long timestamp;
        public final String absolutePath;

        public VersionInfo(String backupFilename, long size, long timestamp, String absolutePath) {
            this.backupFilename = backupFilename;
            this.size = size;
            this.timestamp = timestamp;
            this.absolutePath = absolutePath;
        }

        public String getFormattedDate() {
            return new SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date(timestamp));
        }
    }
}
