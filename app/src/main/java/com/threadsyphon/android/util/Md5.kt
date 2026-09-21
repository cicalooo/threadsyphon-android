package com.threadsyphon.android.util

import android.util.Base64
import java.io.File
import java.security.MessageDigest

object Md5 {
    /** 4chan JSON md5 field is base64-encoded binary MD5. */
    fun fileMd5Base64(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }
}
