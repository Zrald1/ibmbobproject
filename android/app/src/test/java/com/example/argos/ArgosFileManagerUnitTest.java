package com.example.argos;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;

import java.io.File;
import java.util.List;

/**
 * Unit tests for ArgosFileManager path resolution and security logic.
 *
 * These tests use a temporary directory as the sandbox root, so they can
 * run on the JVM without an Android device. They focus on the security
 * aspects of the file manager: path traversal prevention, sandbox enforcement,
 * and version history.
 *
 * Run with: ./gradlew test
 */
public class ArgosFileManagerUnitTest {

    private File tempRoot;
    private TestableArgosFileManager fileManager;

    /**
     * A subclass of ArgosFileManager that uses a specified temp directory
     * instead of the Android-specific folder resolution. This lets us test
     * the core security logic on a plain JVM.
     */
    private static class TestableArgosFileManager extends ArgosFileManager {
        TestableArgosFileManager(File root) {
            super(root, true); // test constructor
        }
    }

    @Before
    public void setUp() throws Exception {
        tempRoot = File.createTempFile("argos_test", "");
        tempRoot.delete();
        tempRoot.mkdirs();
        fileManager = new TestableArgosFileManager(tempRoot);
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

    // ═══════════════════════════════════════════════════════════════
    //  PATH TRAVERSAL PREVENTION
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testTraversalParentDir() {
        assertFalse("../../../etc/passwd must be blocked",
            fileManager.isPathSafe("../../../etc/passwd"));
    }

    @Test
    public void testTraversalSingleParent() {
        assertFalse("../secret.txt must be blocked",
            fileManager.isPathSafe("../secret.txt"));
    }

    @Test
    public void testTraversalWithPrefix() {
        assertFalse("Argos/../secret.txt must be blocked",
            fileManager.isPathSafe("Argos/../secret.txt"));
    }

    @Test
    public void testAbsolutePathBlocked() {
        assertFalse("/etc/passwd must be blocked",
            fileManager.isPathSafe("/etc/passwd"));
    }

    @Test
    public void testWindowsAbsolutePathBlocked() {
        assertFalse("C:\\Windows\\System32 must be blocked",
            fileManager.isPathSafe("C:\\Windows\\System32"));
    }

    @Test
    public void testEmptyPathBlocked() {
        assertFalse("Empty path must be blocked", fileManager.isPathSafe(""));
    }

    @Test
    public void testNullPathBlocked() {
        assertFalse("Null path must be blocked", fileManager.isPathSafe(null));
    }

    @Test
    public void testWhitespacePathBlocked() {
        assertFalse("Whitespace path must be blocked", fileManager.isPathSafe("   "));
    }

    @Test
    public void testLegitimatePathAllowed() {
        assertTrue("notes.txt should be allowed", fileManager.isPathSafe("notes.txt"));
    }

    @Test
    public void testLegitimateSubfolderPathAllowed() {
        assertTrue("Stories/ch1.txt should be allowed",
            fileManager.isPathSafe("Stories/ch1.txt"));
    }

    @Test
    public void testDeepSubfolderAllowed() {
        assertTrue("a/b/c/d/e/file.txt should be allowed",
            fileManager.isPathSafe("a/b/c/d/e/file.txt"));
    }

    @Test
    public void testArgosPrefixStripped() {
        assertTrue("Argos/notes.txt should be allowed (prefix stripped)",
            fileManager.isPathSafe("Argos/notes.txt"));
    }

    @Test
    public void testDocumentsArgosPrefixStripped() {
        assertTrue("Documents/Argos/notes.txt should be allowed (prefix stripped)",
            fileManager.isPathSafe("Documents/Argos/notes.txt"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  FILE OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testWriteAndRead() {
        assertNotNull(fileManager.writeFile("test.txt", "Hello", false));
        assertEquals("Hello", fileManager.readFile("test.txt"));
    }

    @Test
    public void testAppend() {
        fileManager.writeFile("test.txt", "Line1", false);
        fileManager.writeFile("test.txt", "Line2", true);
        String content = fileManager.readFile("test.txt");
        assertTrue(content.contains("Line1"));
        assertTrue(content.contains("Line2"));
    }

    @Test
    public void testEditFile() {
        fileManager.writeFile("test.txt", "I will recieve it", false);
        int count = fileManager.editFile("test.txt", "recieve", "receive");
        assertEquals(1, count);
        assertTrue(fileManager.readFile("test.txt").contains("receive"));
    }

    @Test
    public void testEditFileNoMatch() {
        fileManager.writeFile("test.txt", "Hello world", false);
        assertEquals(0, fileManager.editFile("test.txt", "xyz", "abc"));
    }

    @Test
    public void testDeleteFile() {
        fileManager.writeFile("test.txt", "content", false);
        assertTrue(fileManager.deleteFile("test.txt"));
        assertNull(fileManager.readFile("test.txt"));
    }

    @Test
    public void testDeleteNonExistent() {
        assertFalse(fileManager.deleteFile("no_such_file.txt"));
    }

    @Test
    public void testReadNonExistent() {
        assertNull(fileManager.readFile("no_such_file.txt"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  SUBFOLDERS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testCreateFolder() {
        assertTrue(fileManager.createFolder("MyFolder"));
        assertTrue(fileManager.getFileInfo("MyFolder").isDirectory);
    }

    @Test
    public void testWriteInSubfolder() {
        fileManager.createFolder("Stories");
        assertNotNull(fileManager.writeFile("Stories/ch1.txt", "Chapter 1", false));
        assertEquals("Chapter 1", fileManager.readFile("Stories/ch1.txt"));
    }

    @Test
    public void testNestedSubfolders() {
        fileManager.createFolder("a/b/c");
        assertNotNull(fileManager.writeFile("a/b/c/deep.txt", "Deep", false));
        assertEquals("Deep", fileManager.readFile("a/b/c/deep.txt"));
    }

    @Test
    public void testListFilesInSubfolder() {
        fileManager.createFolder("sub");
        fileManager.writeFile("sub/x.txt", "X", false);
        fileManager.writeFile("sub/y.txt", "Y", false);
        List<ArgosFileManager.FileInfo> files = fileManager.listFiles("sub");
        assertEquals(2, files.size());
    }

    @Test
    public void testDeleteEmptyFolder() {
        fileManager.createFolder("empty");
        assertTrue(fileManager.deleteFolder("empty"));
    }

    @Test
    public void testDeleteNonEmptyFolderFails() {
        fileManager.createFolder("notEmpty");
        fileManager.writeFile("notEmpty/file.txt", "content", false);
        assertFalse(fileManager.deleteFolder("notEmpty"));
    }

    @Test
    public void testMoveFile() {
        fileManager.writeFile("old.txt", "content", false);
        assertTrue(fileManager.moveFile("old.txt", "new.txt"));
        assertNull(fileManager.readFile("old.txt"));
        assertEquals("content", fileManager.readFile("new.txt"));
    }

    @Test
    public void testCopyFile() {
        fileManager.writeFile("orig.txt", "content", false);
        assertTrue(fileManager.copyFile("orig.txt", "copy.txt"));
        assertEquals("content", fileManager.readFile("orig.txt"));
        assertEquals("content", fileManager.readFile("copy.txt"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  VERSION HISTORY
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testVersionBackupOnOverwrite() {
        fileManager.writeFile("v.txt", "V1", false);
        fileManager.writeFile("v.txt", "V2", false);
        List<ArgosFileManager.VersionInfo> versions = fileManager.listVersions("v.txt");
        assertTrue("Should have at least 1 backup", versions.size() >= 1);
    }

    @Test
    public void testVersionBackupOnDelete() {
        fileManager.writeFile("v.txt", "before delete", false);
        fileManager.deleteFile("v.txt");
        List<ArgosFileManager.VersionInfo> versions = fileManager.listVersions("v.txt");
        assertTrue("Should have backup from before deletion", versions.size() >= 1);
    }

    @Test
    public void testMultipleVersionBackups() {
        for (int i = 1; i <= 5; i++) {
            fileManager.writeFile("v.txt", "V" + i, false);
        }
        List<ArgosFileManager.VersionInfo> versions = fileManager.listVersions("v.txt");
        assertTrue("Should have multiple backups", versions.size() >= 2);
    }

    @Test
    public void testRestoreVersion() {
        fileManager.writeFile("v.txt", "Original", false);
        fileManager.writeFile("v.txt", "Overwritten", false);

        List<ArgosFileManager.VersionInfo> versions = fileManager.listVersions("v.txt");
        assertTrue("Should have versions", versions.size() > 0);

        String restored = fileManager.restoreVersion(versions.get(0).backupFilename);
        assertNotNull("Restore should succeed", restored);
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testSearchFiles() {
        fileManager.writeFile("recipe_cake.txt", "cake recipe", false);
        fileManager.writeFile("recipe_bread.txt", "bread recipe", false);
        fileManager.writeFile("random.txt", "random", false);

        List<ArgosFileManager.FileInfo> results = fileManager.searchFiles("recipe");
        assertEquals("Should find 2 recipe files", 2, results.size());
    }

    @Test
    public void testSearchContent() {
        fileManager.writeFile("doc.txt", "The quick brown fox", false);
        List<ArgosFileManager.SearchResult> results = fileManager.searchContent("brown fox");
        assertTrue("Should find content match", results.size() >= 1);
    }

    @Test
    public void testSearchContentNoMatch() {
        fileManager.writeFile("doc.txt", "Hello world", false);
        List<ArgosFileManager.SearchResult> results = fileManager.searchContent("nonexistent_term_12345");
        assertTrue("Should find no matches", results.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════
    //  SECURITY ENFORCEMENT
    // ═══════════════════════════════════════════════════════════════

    @Test(expected = SecurityException.class)
    public void testWriteTraversalThrows() {
        fileManager.writeFile("../../escape.txt", "escaped", false);
    }

    @Test(expected = SecurityException.class)
    public void testReadTraversalThrows() {
        fileManager.readFile("../../etc/passwd");
    }

    @Test(expected = SecurityException.class)
    public void testDeleteTraversalThrows() {
        fileManager.deleteFile("../../important_file");
    }

    @Test(expected = SecurityException.class)
    public void testCreateFolderTraversalThrows() {
        fileManager.createFolder("../../malicious_folder");
    }

    @Test(expected = SecurityException.class)
    public void testMoveToOutsideSandboxThrows() {
        fileManager.writeFile("safe.txt", "content", false);
        fileManager.moveFile("safe.txt", "../../escaped.txt");
    }

    @Test(expected = SecurityException.class)
    public void testCopyToOutsideSandboxThrows() {
        fileManager.writeFile("safe.txt", "content", false);
        fileManager.copyFile("safe.txt", "../../escaped.txt");
    }
}
