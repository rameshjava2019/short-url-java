package com.example.orchestrator.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Append-only, hash-chained audit log (one JSON object per line).
 *
 * Every entry stores the hash of the previous entry, and its own hash covers its
 * full content. Editing, deleting or reordering any line breaks the chain, which
 * {@link #verify(Path)} detects. The engine refuses to resume a run whose audit
 * trail fails verification.
 */
public final class AuditLog {

    private static final String GENESIS_HASH = "0".repeat(64);
    // Compact, single-line JSON for the log file.
    private static final ObjectMapper LINE_MAPPER = Json.MAPPER.copy().disable(SerializationFeature.INDENT_OUTPUT);

    /** One audit event. {@code data} holds event-specific details. */
    public record Entry(int seq, String ts, String type, String actor, String stageId, Integer attempt, Map<String, Object> data,
            String prevHash, String hash) {
    }

    public record Verification(boolean valid, List<Entry> entries, Integer brokenAt, String reason) {
    }

    private final Path file;
    private String lastHash;
    private int sequence;

    public AuditLog(Path file) {
        this.file = file;
        List<Entry> entries = readEntries(file);
        this.lastHash = entries.isEmpty() ? GENESIS_HASH : entries.get(entries.size() - 1).hash();
        this.sequence = entries.size();
    }

    public synchronized Entry append(String type, String actor, String stageId, Integer attempt, Map<String, Object> data) {
        Entry unsigned = new Entry(sequence + 1, Instant.now().toString(), type, actor, stageId, attempt, data, lastHash, null);
        Entry entry = new Entry(unsigned.seq(), unsigned.ts(), type, actor, stageId, attempt, data, lastHash, hashOf(unsigned));
        try {
            Files.writeString(file, LINE_MAPPER.writeValueAsString(entry) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        lastHash = entry.hash();
        sequence = entry.seq();
        return entry;
    }

    public static List<Entry> readEntries(Path file) {
        if (!Files.exists(file)) return List.of();
        try {
            List<Entry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) entries.add(LINE_MAPPER.readValue(line, Entry.class));
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Recomputes the whole chain. */
    public static Verification verify(Path file) {
        List<Entry> entries;
        try {
            entries = readEntries(file);
        } catch (RuntimeException e) {
            return new Verification(false, List.of(), null, "Unreadable audit log: " + e.getMessage());
        }

        String previous = GENESIS_HASH;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.seq() != i + 1) return new Verification(false, entries, i + 1, "Sequence gap or reordering");
            if (!entry.prevHash().equals(previous)) return new Verification(false, entries, entry.seq(), "Chain link broken (previous hash mismatch)");
            if (!hashOf(entry).equals(entry.hash())) return new Verification(false, entries, entry.seq(), "Entry content was modified");
            previous = entry.hash();
        }
        return new Verification(true, entries, null, null);
    }

    // The hash covers every field except the hash itself.
    private static String hashOf(Entry entry) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("seq", entry.seq());
        content.put("ts", entry.ts());
        content.put("type", entry.type());
        content.put("actor", entry.actor());
        content.put("stageId", entry.stageId());
        content.put("attempt", entry.attempt());
        content.put("data", entry.data());
        content.put("prevHash", entry.prevHash());
        return Json.hash(content);
    }
}
