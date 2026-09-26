package io.github.sye1321.paylab.conformance;

import java.io.StringReader;
import java.util.List;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import io.github.sye1321.paylab.run.ScenarioId;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JUnitXmlReportRendererTests {

    @Test
    void mapsAssertionVerdictsAndPreservesXmlSensitiveExplanations() throws Exception {
        String explanation = "Unsafe amount < expected & retry was ambiguous";
        ConformanceEvaluation evaluation = new ConformanceEvaluation(
                UUID.fromString("9f784e92-8f44-4e2f-bdef-f29c8f186f78"),
                ScenarioId.SAME_KEY_RETRY, 1, ConformanceVerdict.FAIL, List.of(
                        new AssertionEvaluation("PASS_ASSERTION", "INV-01", ConformanceVerdict.PASS,
                                "Safe & complete", List.of(1L)),
                        new AssertionEvaluation("FAIL_ASSERTION", "INV-02", ConformanceVerdict.FAIL,
                                explanation, List.of(2L, 3L)),
                        new AssertionEvaluation("INCONCLUSIVE_ASSERTION", "INV-03",
                                ConformanceVerdict.INCONCLUSIVE, explanation, List.of())));

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document document = factory.newDocumentBuilder().parse(
                new InputSource(new StringReader(new JUnitXmlReportRenderer().render(evaluation))));

        Element suite = document.getDocumentElement();
        assertEquals("3", suite.getAttribute("tests"));
        assertEquals("1", suite.getAttribute("failures"));
        assertEquals("0", suite.getAttribute("errors"));
        assertEquals("1", suite.getAttribute("skipped"));
        NodeList testcases = suite.getElementsByTagName("testcase");
        Element pass = (Element) testcases.item(0);
        Element fail = (Element) testcases.item(1);
        Element inconclusive = (Element) testcases.item(2);
        assertEquals(0, pass.getElementsByTagName("failure").getLength());
        assertEquals(0, pass.getElementsByTagName("skipped").getLength());
        assertEquals(1, fail.getElementsByTagName("failure").getLength());
        assertEquals(explanation, ((Element) fail.getElementsByTagName("failure").item(0)).getAttribute("message"));
        assertEquals(1, inconclusive.getElementsByTagName("skipped").getLength());
        assertEquals(explanation,
                ((Element) inconclusive.getElementsByTagName("skipped").item(0)).getAttribute("message"));
    }
}
