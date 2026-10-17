package net.kdt.pojavlaunch.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** File operations for Gimmy-style local mod management. No authentication or game launch logic. */
public final class GimmyModFiles {
    private GimmyModFiles() { }

    public static File modsDirectory(File gameDirectory) {
        if (gameDirectory == null) throw new IllegalArgumentException("Missing game directory");
        return new File(gameDirectory, "mods");
    }

    public static List<File> list(File modsDirectory) {
        File[] entries = modsDirectory.listFiles(file -> file.isFile() && isModName(file.getName()));
        if (entries == null) return new ArrayList<>();
        Arrays.sort(entries, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return new ArrayList<>(Arrays.asList(entries));
    }

    public static boolean isModName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".jar.disabled");
    }

    /** Import a mod selected through Android's document picker; never overwrite silently. */
    public static File importMod(File source, File modsDirectory) throws IOException {
        if (source == null || !source.isFile() || !source.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
            throw new IOException("Select a .jar file");
        if (!modsDirectory.isDirectory() && !modsDirectory.mkdirs()) throw new IOException("Cannot create mods directory");
        File destination = new File(modsDirectory, source.getName());
        if (source.getCanonicalFile().equals(destination.getCanonicalFile())) return destination;
        Files.copy(source.toPath(), destination.toPath()); // Existing file raises FileAlreadyExistsException
        return destination;
    }

    /** Toggle a mod by adding/removing .disabled, matching Gimmy V2.1. */
    public static File toggle(File modsDirectory, String filename) throws IOException {
        File source = checkedFile(modsDirectory, filename);
        String targetName = filename.endsWith(".jar.disabled")
                ? filename.substring(0, filename.length() - ".disabled".length())
                : filename + ".disabled";
        File target = new File(modsDirectory, targetName);
        if (target.exists()) throw new IOException("Target already exists: " + targetName);
        Files.move(source.toPath(), target.toPath());
        return target;
    }

    public static boolean remove(File modsDirectory, String filename) throws IOException {
        return Files.deleteIfExists(checkedFile(modsDirectory, filename).toPath());
    }

    private static File checkedFile(File directory, String filename) throws IOException {
        if (directory == null || filename == null || filename.contains("/") || filename.contains("\\") || filename.equals(".") || filename.equals("..") || !isModName(filename))
            throw new IOException("Invalid mod filename");
        File file = new File(directory, filename);
        if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()) || !file.isFile())
            throw new IOException("Mod does not exist in selected directory");
        return file;
    }
}
