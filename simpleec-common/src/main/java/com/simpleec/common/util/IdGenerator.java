package com.simpleec.common.util;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;

/**
 * 統一 ID 產生器 — VARCHAR(20) NanoID
 * 與 01-schema.sql PK 規格一致
 */
public final class IdGenerator {

    private static final int ID_LENGTH = 20;

    private IdGenerator() {}

    public static String next() {
        return NanoIdUtils.randomNanoId(
            NanoIdUtils.DEFAULT_NUMBER_GENERATOR,
            NanoIdUtils.DEFAULT_ALPHABET,
            ID_LENGTH
        );
    }
}
