package qa.triage.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import qa.triage.FailureBundle;
import qa.triage.TriageResult;
import qa.triage.engine.CascadeTriageEngine;
import qa.triage.engine.CiTriageEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a FailureBundle as JSON (from a file argument, or stdin if no argument is
 * given) and prints the resulting TriageResult as JSON on stdout.
 */
public final class TriageCli {

    private TriageCli() {
    }

    public static void main(String[] args) throws IOException {
        String input = args.length > 0
                ? Files.readString(Path.of(args[0]))
                : new String(System.in.readAllBytes(), StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper();
        FailureBundle bundle = mapper.readValue(input, FailureBundle.class);

        CiTriageEngine engine = new CascadeTriageEngine();
        TriageResult result = engine.triage(bundle);

        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
