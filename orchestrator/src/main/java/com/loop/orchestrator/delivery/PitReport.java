package com.loop.orchestrator.delivery;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The mutation score, read out of PIT's XML report, scoped to the lines the change touched.
 *
 * <p>This is the gate an agent cannot talk its way past. Coverage asks whether a line ran;
 * mutation asks whether a test would have noticed the line being wrong. The phase 5 check
 * measured exactly that difference: a single assertion-free test scored 100% coverage and
 * 19% mutation on the same class.
 *
 * <p>Scoped to the diff for the same reason coverage is. A project-wide score moves so
 * slowly that one story's tests cannot shift it, so it would gate on the repository's
 * history rather than on the change in front of it.
 */
final class PitReport {

    private record Mutation(String classPath, int line, boolean detected, boolean viable) {
    }

    private final List<Mutation> mutations;

    private PitReport(List<Mutation> mutations) {
        this.mutations = mutations;
    }

    static PitReport parse(byte[] xml) {
        List<Mutation> mutations = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

            NodeList nodes = document.getElementsByTagName("mutation");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);

                // An attribute, not a child element. Read as an element it came back
                // empty, which made every NON_VIABLE mutation count as a real one and
                // quietly lowered the mutation score of any change PIT could not compile
                // a mutant for. Found by a test using the real report's shape.
                String status = node.getAttribute("status");
                mutations.add(new Mutation(
                        pathOf(text(node, "mutatedClass")),
                        Integer.parseInt(text(node, "lineNumber")),
                        "true".equals(node.getAttribute("detected")),
                        // A mutation the JVM rejects says nothing about the tests, so PIT
                        // leaves it out of its own score and so does this.
                        !"NON_VIABLE".equals(status)));
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "could not read the PIT report: " + e.getMessage(), e);
        }

        return new PitReport(mutations);
    }

    /** Every mutation PIT generated, for the case where nothing landed on the diff. */
    Score overall() {
        return score(mutations);
    }

    Score forDiff(Diff diff) {
        List<Mutation> onChangedLines = mutations.stream()
                .filter(mutation -> diff.javaFiles().stream().anyMatch(file ->
                        file.path().endsWith(mutation.classPath())
                                && file.changedLines().contains(mutation.line())))
                .toList();

        return score(onChangedLines);
    }

    private static Score score(List<Mutation> considered) {
        List<Mutation> viable = considered.stream().filter(Mutation::viable).toList();
        long detected = viable.stream().filter(Mutation::detected).count();
        return new Score(viable.size(), (int) detected);
    }

    record Score(int considered, int detected) {
        boolean hasAnything() {
            return considered > 0;
        }

        double ratio() {
            return considered == 0 ? 1.0 : (double) detected / considered;
        }
    }

    /** {@code com.loop.Foo$Inner} -> {@code com/loop/Foo.java}, to match against diff paths. */
    private static String pathOf(String mutatedClass) {
        String topLevel = mutatedClass.contains("$")
                ? mutatedClass.substring(0, mutatedClass.indexOf('$'))
                : mutatedClass;
        return topLevel.replace('.', '/') + ".java";
    }

    private static String text(Element mutation, String tag) {
        NodeList nodes = mutation.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
