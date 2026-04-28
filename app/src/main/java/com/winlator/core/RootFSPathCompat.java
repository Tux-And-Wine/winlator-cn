package com.ludashi.benchmark.core;

import android.content.Context;

import com.ludashi.benchmark.xenvironment.RootFS;

import java.io.File;
import java.nio.charset.StandardCharsets;

public abstract class RootFSPathCompat {
    private static final String LEGACY_PACKAGE_NAME = "com.winlator";
    private static final String LEGACY_ROOTFS_PATH = "/data/data/com.winlator/files/rootfs";
    private static final String MARKER_FILENAME = ".pkg_path_compat";
    private static final String MARKER_VERSION = "4";
    private static final String COMPAT_LINK_NAME = "rrr";
    private static final String[] GRAPHICS_DRIVER_FILES = new String[]{
        "/usr/lib/libGL.so.1.7.0",
        "/usr/lib/libvulkan_freedreno.so",
        "/usr/lib/libvulkan_vortek.so",
        "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json",
        "/usr/share/vulkan/icd.d/vortek_icd.aarch64.json"
    };

    public static void repairRootFSIfNeeded(Context context, RootFS rootFS) {
        if (!isRequired(context) || rootFS == null) return;

        ensureCompatLink(context, rootFS);
        File markerFile = getMarkerFile(rootFS);
        String markerContent = getMarkerContent(context, rootFS);
        if (markerFile.isFile() && markerContent.equals(FileUtils.readString(markerFile))) return;

        repairRootFS(context, rootFS);
    }

    public static void repairRootFS(Context context, RootFS rootFS) {
        if (!isRequired(context) || rootFS == null) return;

        ensureCompatLink(context, rootFS);
        String actualRootPath = getActualRootPath(rootFS);
        String compatRootPath = getCompatRootPath(context);
        repairDirectory(rootFS.getRootDir(), actualRootPath, compatRootPath);
        File markerFile = getMarkerFile(rootFS);
        File parent = markerFile.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        FileUtils.writeString(markerFile, getMarkerContent(context, rootFS));
    }

    public static void repairGraphicsDriverFiles(Context context, RootFS rootFS) {
        if (!isRequired(context) || rootFS == null) return;

        ensureCompatLink(context, rootFS);
        String actualRootPath = getActualRootPath(rootFS);
        String compatRootPath = getCompatRootPath(context);
        File rootDir = rootFS.getRootDir();

        for (String relativePath : GRAPHICS_DRIVER_FILES) {
            repairFile(new File(rootDir, relativePath), actualRootPath, compatRootPath);
        }
    }

    public static boolean repairRootFSFile(Context context, RootFS rootFS, File file) {
        if (!isRequired(context) || rootFS == null || file == null || !file.exists()) return false;

        ensureCompatLink(context, rootFS);
        return repairFile(file, getActualRootPath(rootFS), getCompatRootPath(context));
    }

    private static boolean isRequired(Context context) {
        return context != null && !LEGACY_PACKAGE_NAME.equals(context.getPackageName());
    }

    private static File getMarkerFile(RootFS rootFS) {
        return new File(rootFS.getRFSVersionFile().getParentFile(), MARKER_FILENAME);
    }

    public static String getCompatRootPath(Context context) {
        return "/data/data/" + context.getPackageName() + "/" + COMPAT_LINK_NAME;
    }

    public static String getActualRootPath(RootFS rootFS) {
        return rootFS != null ? rootFS.getRootDir().getPath() : "";
    }

    private static String getMarkerContent(Context context, RootFS rootFS) {
        return MARKER_VERSION + "|" + getActualRootPath(rootFS) + "|" + getCompatRootPath(context);
    }

    private static void ensureCompatLink(Context context, RootFS rootFS) {
        File linkFile = new File(context.getDataDir(), COMPAT_LINK_NAME);
        String targetPath = rootFS.getRootDir().getPath();

        if (linkFile.exists() && !targetPath.equals(FileUtils.readSymlink(linkFile))) {
            FileUtils.delete(linkFile);
        }
        if (!linkFile.exists()) {
            FileUtils.symlink(targetPath, linkFile.getPath());
        }
    }

    private static void repairDirectory(File file, String actualRootPath, String compatRootPath) {
        if (file == null || !file.exists() || FileUtils.isSymlink(file)) return;

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;

            for (File child : children) {
                repairDirectory(child, actualRootPath, compatRootPath);
            }
        }
        else repairFile(file, actualRootPath, compatRootPath);
    }

    private static boolean repairFile(File file, String actualRootPath, String compatRootPath) {
        byte[] data = FileUtils.read(file);
        if (data == null || indexOf(data, LEGACY_ROOTFS_PATH.getBytes(StandardCharsets.UTF_8)) == -1) return false;

        if (looksBinary(data)) {
            byte[] patched = replaceBinaryPath(data, actualRootPath);
            if (patched != null) return FileUtils.write(file, patched);

            patched = replaceBinaryPath(data, compatRootPath);
            return patched != null && FileUtils.write(file, patched);
        }

        String content = new String(data, StandardCharsets.UTF_8);
        return FileUtils.writeString(file, content.replace(LEGACY_ROOTFS_PATH, actualRootPath));
    }

    private static boolean looksBinary(byte[] data) {
        int max = Math.min(data.length, 4096);
        for (int i = 0; i < max; i++) {
            if (data[i] == 0) return true;
        }
        return false;
    }

    private static byte[] replaceBinaryPath(byte[] data, String compatRootPath) {
        byte[] patched = data.clone();
        byte[] source = LEGACY_ROOTFS_PATH.getBytes(StandardCharsets.UTF_8);
        byte[] replacement = compatRootPath.getBytes(StandardCharsets.UTF_8);
        if (replacement.length != source.length) return null;

        int index = 0;
        while ((index = indexOf(patched, source, index)) != -1) {
            System.arraycopy(replacement, 0, patched, index, replacement.length);
            index += source.length;
        }
        return patched;
    }

    private static int indexOf(byte[] data, byte[] target) {
        return indexOf(data, target, 0);
    }

    private static int indexOf(byte[] data, byte[] target, int start) {
        if (data == null || target == null || target.length == 0) return -1;

        outer:
        for (int i = Math.max(0, start); i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
