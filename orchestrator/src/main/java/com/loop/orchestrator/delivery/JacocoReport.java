package com.loop.orchestrator.delivery;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Diff coverage, read out of JaCoCo's XML report.
 *
 * <p>Project coverage is the wrong number and it is the one everybody reports. A repository
 * at 73% overall tells you nothing about whether the twelve lines an agent just wrote are
 * tested, and a project-wide threshold on a codebase that is still mostly stubs fails for
 * reasons nobody can act on — which teaches people to pass {@code -DskipTests}. The
 * question worth gating on is narrower: of the executable lines <b>this change</b> added,
 * how many did a test execute?
 *
 * <p>Lines the diff touched that JaCoCo has no record of are not counted either way. Blank
 * lines, comments, imports and declarations are not executable, and counting them as missed
 * would make a well-tested change with a long licence header look untested.
 */
final class JacocoReport {

    /** Covered and missed instruction counts for one source line. */
    private record Line(int covered, int missed) {
        boolean executable() {
            return covered + missed > 0;
        }
    }

    /** Keyed by the report's own path form, {@code com/loop/orchestrator/Foo.java}. */
    private final Map<String, Map<Integer, Line>> byFile;

    private JacocoReport(Map<String, Map<Integer, Line>> byFile) {
        this.byFile = byFile;
    }

    static JacocoReport parse(byte[] xml) {
        Map<String, Map<Integer, Line>> byFile = new LinkedHashMap<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The report names a DTD by URL. Resolving it would make parsing depend on the
            // network, and an external entity in a file we did not write is not something
            // to fetch at all.
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            var document = builder.parse(new ByteArrayInputStream(xml));

            NodeList packages = document.getElementsByTagName("package");
            for (int p = 0; p < packages.getLength(); p++) {
                Element pkg = (Element) packages.item(p);
                String packagePath = pkg.getAttribute("name");

                NodeList sources = pkg.getElementsByTagName("sourcefile");
                for (int s = 0; s < sources.getLength(); s++) {
                    Element source = (Element) sources.item(s);
                    String path = packagePath + "/" + source.getAttribute("name");

                    Map<Integer, Line> lines = new LinkedHashMap<>();
                    NodeList lineNodes = source.getElementsByTagName("line");
                    for (int l = 0; l < lineNodes.getLength(); l++) {
                        Element line = (Element) lineNodes.item(l);
                        lines.put(
                                Integer.parseInt(line.getAttribute("nr")),
                                new Line(intAttribute(line, "ci"), intAttribute(line, "mi")));
                    }
                    byFile.put(path, lines);
                }
            }
        } catch (Exception e) {
            // An unreadable report is not zero coverage. Returning 0.0 would read as a
            // tested-and-failing change when nothing was measured at all, and the gate
            // upstream is built to tell those apart.
            throw new IllegalStateException(
                    "could not read the JaCoCo report: " + e.getMessage(), e);
        }

        return new JacocoReport(byFile);
    }

    /**
     * @return the covered share of the diff's executable lines, and how many there were.
     *         An empty result means nothing executable changed, which is not a failure —
     *         a documentation-only change has no coverage to have.
     */
    Measurement diffCoverage(Diff diff) {
        int executable = 0;
        int covered = 0;

        for (Diff.ChangedFile file : diff.javaFiles()) {
            Map<Integer, Line> lines = linesFor(file.path());
            if (lines == null) {
                continue;
            }
            Set<Integer> changed = file.changedLines();
            for (Integer number : changed) {
                Line line = lines.get(number);
                if (line == null || !line.executable()) {
                    continue;
                }
                executable++;
                if (line.covered() > 0) {
                    covered++;
                }
            }
        }

        return new Measurement(executable, covered);
    }

    record Measurement(int executableLines, int coveredLines) {
        boolean hasAnything() {
            return executableLines > 0;
        }

        double ratio() {
            return executableLines == 0 ? 1.0 : (double) coveredLines / executableLines;
        }
    }

    /**
     * The diff gives repository paths; the report gives package paths. Matching on the
     * suffix is what joins them without either side having to know the module layout.
     */
    private Map<Integer, Line> linesFor(String repositoryPath) {
        Map<Integer, Line> exact = byFile.get(repositoryPath);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Map<Integer, Line>> entry : byFile.entrySet()) {
            if (repositoryPath.endsWith("/" + entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static int intAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }
}
