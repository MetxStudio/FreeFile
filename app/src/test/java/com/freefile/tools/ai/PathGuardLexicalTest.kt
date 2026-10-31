package com.freefile.tools.ai

import com.freefile.tools.ai.tool.PathGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验收 12：路径规范化与目录穿越拦截（纯词法，不依赖 Android）。 */
class PathGuardLexicalTest {

    @Test
    fun normalizes_plain_absolute_paths() {
        assertEquals(
            "/storage/emulated/0/Download",
            PathGuard.lexicalNormalize("/storage/emulated/0/Download/")
        )
        assertEquals("/a/b", PathGuard.lexicalNormalize("/a/./b"))
        assertEquals("/a/c", PathGuard.lexicalNormalize("/a/b/../c"))
        assertEquals("/a", PathGuard.lexicalNormalize("/a/b/.."))
    }

    @Test
    fun rejects_relative_and_traversal() {
        assertNull(PathGuard.lexicalNormalize("Download/x"))
        assertNull(PathGuard.lexicalNormalize("../etc"))
        assertNull(PathGuard.lexicalNormalize("/.."))
        // 在根之上继续回溯必须拒绝
        assertNull(PathGuard.lexicalNormalize("/storage/../.."))
        assertNull(PathGuard.lexicalNormalize(""))
        assertNull(PathGuard.lexicalNormalize("   "))
        // 越出共享存储但仍在文件系统内：词法折叠为绝对路径，由 checkWrite 白名单兜底拦截
        assertEquals("/etc", PathGuard.lexicalNormalize("/storage/emulated/0/../../../etc"))
        assertEquals("/etc/passwd", PathGuard.lexicalNormalize("/storage/../etc/passwd"))
    }

    @Test
    fun normalizes_remote_virtual_paths() {
        assertEquals(
            "remote://abc/DCIM",
            PathGuard.lexicalNormalize("remote://abc/Download/../DCIM")
        )
        assertEquals(
            "remote://vol1/",
            PathGuard.lexicalNormalize("remote://vol1")
        )
    }
}
