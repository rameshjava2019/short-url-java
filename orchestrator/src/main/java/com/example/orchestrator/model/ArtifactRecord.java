package com.example.orchestrator.model;

import java.util.ArrayList;
import java.util.List;

/** One version of an artifact. The content is stored in a file next to the run state. */
public class ArtifactRecord {

    public String id;
    public int version;
    public String hash;
    public String producedBy;
    public List<Ref> derivedFrom = new ArrayList<>();
    /** Path of the content file, relative to the run folder. */
    public String path;
    public String createdAt;

    public record Ref(String id, int version) {
    }
}
