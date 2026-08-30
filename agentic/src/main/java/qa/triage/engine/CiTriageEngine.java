package qa.triage.engine;

import qa.triage.FailureBundle;
import qa.triage.TriageResult;

/** Classifies one CI failure. See agentic/AGENT-DESIGN.md for the cascade this will run. */
public interface CiTriageEngine {

    TriageResult triage(FailureBundle bundle);
}
