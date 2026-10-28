package com.example.orchestrator.model;

import java.util.ArrayList;
import java.util.List;

/** A decision made by an agent or a human, with its rationale and the exact inputs it was based on. */
public class Decision {

    public String id;
    public String at;
    public String stageId;
    public String actor;
    public String decision;
    public String rationale;
    public List<String> alternatives = new ArrayList<>();
    public List<ArtifactRecord.Ref> inputs = new ArrayList<>();
}
