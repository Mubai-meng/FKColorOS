package com.android.internal.os;

import dalvik.system.PathClassLoader;

public class SystemServerClassLoaderFactory {
    static PathClassLoader createClassLoader(String path, ClassLoader parent) {
        throw new RuntimeException("STUB");
    }
    
    public static PathClassLoader getOrCreateClassLoader(String path, ClassLoader parent, boolean isTestOnly) {
        throw new RuntimeException("STUB");
    }
}
