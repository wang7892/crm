package cn.cordys.crm.aiagent.service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

final class AiAgentDatasourceStorage {
    private AiAgentDatasourceStorage() { }

    static void directory(Path path) throws IOException {
        boolean exists = Files.exists(path);
        Files.createDirectories(path);
        if (!exists) ownerOnly(path);
    }

    static void ownerOnly(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(path, Files.isDirectory(path)
                    ? PosixFilePermissions.fromString("rwx------") : PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (Files.isDirectory(path)) builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(builder.build()));
        }
    }
}
