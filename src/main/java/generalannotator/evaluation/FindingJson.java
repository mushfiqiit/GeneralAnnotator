package generalannotator.evaluation;

import generalannotator.diagnostics.Diagnostic;
import generalannotator.diagnostics.Severity;
import generalannotator.evaluation.matching.ObservedFinding;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/** JSON form of diagnostics and observed findings in evaluation records. */
final class FindingJson {

    private FindingJson() {}

    static Map<String, Object> diagnostic(Diagnostic d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourcePath", d.sourcePath());
        m.put("line", d.line());
        m.put("column", d.column().isPresent() ? d.column().getAsInt() : null);
        m.put("severity", d.severity().label());
        m.put("checker", d.checkerName().orElse(null));
        m.put("message", d.message());
        m.put("sourceSnippet", d.sourceSnippet().orElse(null));
        return m;
    }

    static Diagnostic diagnostic(Map<?, ?> m) {
        Object column = m.get("column");
        return new Diagnostic((String) m.get("sourcePath"), Math.toIntExact((Long) m.get("line")),
                column == null ? OptionalInt.empty() : OptionalInt.of(Math.toIntExact((Long) column)),
                Severity.valueOf(((String) m.get("severity")).toUpperCase(Locale.ROOT)),
                Optional.ofNullable((String) m.get("checker")), (String) m.get("message"),
                Optional.ofNullable((String) m.get("sourceSnippet")));
    }

    static Map<String, Object> finding(ObservedFinding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("path", f.path());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("declaration", f.anchor().declaration());
        a.put("line", f.anchor().line());
        a.put("problem", f.anchor().problem().isEmpty() ? null : f.anchor().problem());
        m.put("anchor", a);
        m.put("diagnostic", diagnostic(f.diagnostic()));
        return m;
    }

    static ObservedFinding finding(Map<?, ?> m) {
        Map<?, ?> a = (Map<?, ?>) m.get("anchor");
        Object problem = a.get("problem");
        return new ObservedFinding((String) m.get("id"), diagnostic((Map<?, ?>) m.get("diagnostic")),
                (String) m.get("path"), new ObservedFinding.Anchor((String) a.get("declaration"),
                Math.toIntExact((Long) a.get("line")), problem == null ? "" : (String) problem));
    }
}
