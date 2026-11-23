// Title: Miniforge.ai
// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0.
// Read-only Darwin ACL probe, run in its own bounded osascript process.
ObjC.import("Foundation");

const signatures = {
    filesec_init: ["void *", []],
    filesec_free: ["void", ["void *"]],
    lstatx_np: ["int", ["char *", "void *", "void *"]],
    filesec_query_property: ["int", ["void *", "int", "int *"]],
    filesec_get_property: ["int", ["void *", "int", "void **"]],
    acl_valid: ["int", ["void *"]],
    acl_get_entry: ["int", ["void *", "int", "void **"]],
    acl_get_tag_type: ["int", ["void *", "int *"]],
    acl_free: ["int", ["void *"]],
    __error: ["int *", []]
};

for (const name of Object.keys(signatures)) ObjC.bindFunction(name, signatures[name]);

// Public constants from Darwin sys/fcntl.h, sys/acl.h, and sys/errno.h.
const aclProperty = 5;
const denyTag = 2;
const maxAclEntries = 128;
const invalidArgument = 22;
// Scratch capacity exceeds sizeof(struct stat) on supported 64-bit macOS.
const statCapacity = 512;

function onlyDenials(acl) {
    if ($.acl_valid(acl) !== 0) return false;
    const entry = Ref();
    const tag = Ref();
    const errno = $.__error();
    for (let index = 0; index <= maxAclEntries; index++) {
        errno[0] = 0;
        if ($.acl_get_entry(acl, index, entry) !== 0) {
            // For a validated ACL and nonnegative index, EINVAL marks its end.
            return errno[0] === invalidArgument;
        }
        if ($.acl_get_tag_type(entry[0], tag) !== 0 || tag[0] !== denyTag) return false;
    }
    return false;
}

function protectedAccessControl(path) {
    const scratch = $.NSMutableData.dataWithLength(statCapacity);
    const security = $.filesec_init();
    try {
        const present = Ref();
        if ($.lstatx_np(path, scratch.mutableBytes, security) !== 0 ||
            $.filesec_query_property(security, aclProperty, present) !== 0) return false;
        if (present[0] === 0) return true;
        const acl = Ref();
        if ($.filesec_get_property(security, aclProperty, acl) !== 0) return false;
        try {
            return onlyDenials(acl[0]);
        } finally {
            $.acl_free(acl[0]);
        }
    } finally {
        $.filesec_free(security);
    }
}

function run(paths) {
    if (paths.length === 0 || !paths.every(protectedAccessControl)) throw Error("Unverified ACL");
    return "verified";
}
