package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.ProjectMetadata;
import com.google.refine.importers.ImportingParserBase;
import com.google.refine.importers.SeparatorBasedImporter;
import com.google.refine.importing.ImportingJob;
import com.google.refine.model.Project;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public class FilesImporter {
    private static final Logger logger = LoggerFactory.getLogger("FilesImporter");

    public static long generateFileList(File file, ObjectNode options) throws IOException {
        long length = 0;

        JsonNode directoryInput = options.get("directoryJsonValue");
        boolean mfileContentColumn = options.get("fileContentColumn").asBoolean();
        FileOutputStream fos = new FileOutputStream(file);

        for (JsonNode directoryPath : directoryInput) {
            length += getFileList(directoryPath.get("directory").asText(), fos);
        }
        return length;
    }

    public static void loadData(Project project, ProjectMetadata metadata, ImportingJob job, ArrayNode fileRecords) throws Exception {
        ObjectNode options = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(options, "includeArchiveFileName", true);
        JSONUtilities.safePut(options, "includeFileSources", false);
        ArrayNode columns = ParsingUtilities.mapper.createArrayNode();
        columns.add("fileName");
        columns.add("fileSize(KB)");
        columns.add("fileExtension");
        columns.add("lastModifiedTime");
        columns.add("creationTime");
        columns.add("author");
        columns.add("filePath");
        columns.add("filePermissions");
        columns.add("fileChecksum");
        columns.add("fileContent");
        JSONUtilities.safePut(options, "columnNames", columns);
        JSONUtilities.safePut(options, "separator", ",");


        ImportingParserBase parser = new SeparatorBasedImporter();
        List<Exception> exceptions = new ArrayList<Exception>();

        parser.parse(
                project,
                metadata,
                job,
                JSONUtilities.getObjectList(fileRecords),
                "csv",
                -1,
                options,
                exceptions);

        if(exceptions.size() > 0) {
            throw new Exception("Failed to process file list");
        }
        project.update();
    }

    private static long getFileList(String directoryPath, FileOutputStream fos) throws IOException {
        int depth = 1;
        final long[] length = {0};
        try {
            Path rootPath = Paths.get(directoryPath);
            Files.walkFileTree(rootPath, EnumSet.noneOf(FileVisitOption.class), depth, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileRecord = "";
                    try {
                        if (!attrs.isDirectory()) {
                            String fileName = file.getFileName().toString();
                            String filePath = file.toAbsolutePath().toString();
                            String author = "";
                            try {
                                author = Files.getOwner(file).getName(); // File owner (may not always be available)
                            } catch (Exception e) {
                                // ignore
                            }
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                            String dateCreated = sdf.format(attrs.creationTime().toMillis());
                            String dateModified = sdf.format(attrs.lastModifiedTime().toMillis());
                            long fileSize = (long) Math.ceil(attrs.size() / 1024.0);
                            String fileExt = getFileExt(fileName);
                            String filePermissions = getFilePermissions(file);
                            String fileChecksum = calculateFileChecksum(file, "SHA-256");
                            String fileContent = getFileContent(file);

                            fileRecord = String.format("%s,%d,%s,%s,%s,%s,%s,%s,%s,%s\n",
                                    fileName, fileSize, fileExt, dateModified, dateCreated, author, filePath, filePermissions, fileChecksum, fileContent);
                            fos.write(fileRecord.getBytes(UTF_8), 0, fileRecord.length());
                            length[0] += fileRecord.length();
                        }
                    } catch (Exception e) {
                        logger.info("--- importDirectory. Error processing file: " + file + " - " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return length[0];
        } catch (Exception e) {
            logger.info("--- importDirectory. Error reading directory: " + e.getMessage());
            throw e;
        }
    }

    private static String getFileExt(String fileName) {
        String fileExt = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            fileExt = fileName.substring(dotIndex + 1);
        }
        return fileExt;
    }

    private static String getFilePermissions(Path path) {
        String filePermissions = "";
        try {
            if (Files.exists(path)) {
                FileStore store = Files.getFileStore(path);
                if (!store.supportsFileAttributeView(PosixFileAttributeView.class)) {
                    logger.info("--- importDirectory. POSIX file attributes are not supported on this system.");
                }
                else {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                    filePermissions = PosixFilePermissions.toString(permissions);
                }
            }
        } catch (Exception e) {
            logger.info("--- importDirectory. Failed to retrieve file permissions: " + e.getMessage());
        }
        return filePermissions;
    }

    private static String calculateFileChecksum(Path path, String algorithm) throws Exception {
        if (Files.exists(path)) {
            try (var fileChannel = FileChannel.open(path, StandardOpenOption.READ);
                 var lock = fileChannel.lock(0, Long.MAX_VALUE, true)) {
                    MessageDigest digest = MessageDigest.getInstance(algorithm);
                    try (var inputStream = Files.newInputStream(path)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            digest.update(buffer, 0, bytesRead);
                        }
                    }
                    return bytesToHex(digest.digest());
            }
        }
        return "";
    }

    private static String bytesToHex(byte[] bytes) {
        try (Formatter formatter = new Formatter()) {
            for (byte b : bytes) {
                formatter.format("%02x", b);
            }
            return formatter.toString();
        }
    }

    private static String getFileContent(Path path) {
        if (Files.exists(path) && ! isBinaryFile(path)) {
            try {
                byte[] fileBytes = Files.readAllBytes(path);
                int maxBytes = Math.min(fileBytes.length, 1 * 1024); // Max 32KB
                return escapeForCsv(new String(fileBytes, 0, maxBytes, StandardCharsets.UTF_8)); // Convert to UTF-8 string
            }
            catch (IOException e) {
                logger.info("--- importDirectory. Failed to read file content: " + e.getMessage());
            }
        }
        return "";
    }

    private static boolean isBinaryFile(Path path)  {
        byte[] buffer = new byte[1024];
        try (var inputStream = Files.newInputStream(path)) {
            int bytesRead = inputStream.read(buffer);
            for (int i = 0; i < bytesRead; i++) {
                if (buffer[i] < 0x09 || (buffer[i] > 0x0D && buffer[i] < 0x20 && buffer[i] != 0x7F)) {
                    return true;
                }
            }
        }
        catch (IOException e) {
            // do nothing
            logger.info("--- importDirectory. Failed to read file content: " + e.getMessage());
        }
        return false;
    }

    private static String escapeForCsv(String content) {
        content = content.replace("\"", "\"\"");
        content = content.replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .trim();
        return "\"" + content + "\"";
    }
}