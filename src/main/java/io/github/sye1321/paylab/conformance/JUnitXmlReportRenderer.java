package io.github.sye1321.paylab.conformance;

import java.io.StringWriter;
import java.util.stream.Collectors;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.springframework.stereotype.Component;

@Component
public class JUnitXmlReportRenderer {

    public String render(ConformanceEvaluation evaluation) {
        StringWriter output = new StringWriter();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("testsuite");
            String suiteName = "PayLab." + evaluation.scenario();
            writeAttribute(xml, "name", suiteName);
            writeAttribute(xml, "tests", evaluation.assertions().size());
            writeAttribute(xml, "failures", count(evaluation, ConformanceVerdict.FAIL));
            writeAttribute(xml, "errors", 0);
            writeAttribute(xml, "skipped", count(evaluation, ConformanceVerdict.INCONCLUSIVE));

            xml.writeStartElement("properties");
            writeProperty(xml, "paylab.runId", evaluation.runId().toString());
            writeProperty(xml, "paylab.scenario", evaluation.scenario().name());
            writeProperty(xml, "paylab.scenarioVersion", Integer.toString(evaluation.scenarioVersion()));
            writeProperty(xml, "paylab.verdict", evaluation.verdict().name());
            xml.writeEndElement();

            for (AssertionEvaluation assertion : evaluation.assertions()) {
                xml.writeStartElement("testcase");
                writeAttribute(xml, "classname", suiteName);
                writeAttribute(xml, "name", assertion.assertionId());
                if (assertion.verdict() == ConformanceVerdict.FAIL) {
                    xml.writeStartElement("failure");
                    writeAttribute(xml, "message", assertion.explanation());
                    xml.writeCharacters(details(assertion, false));
                    xml.writeEndElement();
                } else if (assertion.verdict() == ConformanceVerdict.INCONCLUSIVE) {
                    xml.writeEmptyElement("skipped");
                    writeAttribute(xml, "message", assertion.explanation());
                }
                xml.writeStartElement("system-out");
                xml.writeCharacters(details(assertion, true));
                xml.writeEndElement();
                xml.writeEndElement();
            }

            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
            return output.toString();
        } catch (XMLStreamException exception) {
            throw new IllegalStateException("Could not render JUnit XML", exception);
        }
    }

    private static long count(ConformanceEvaluation evaluation, ConformanceVerdict verdict) {
        return evaluation.assertions().stream().filter(assertion -> assertion.verdict() == verdict).count();
    }

    private static String details(AssertionEvaluation assertion, boolean includeExplanation) {
        String evidenceIds = assertion.evidenceEventIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        String details = "Invariant: " + assertion.invariantId() + "\nEvidence event IDs: " + evidenceIds;
        return includeExplanation ? details + "\nExplanation: " + assertion.explanation() : details;
    }

    private static void writeProperty(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
        xml.writeEmptyElement("property");
        writeAttribute(xml, "name", name);
        writeAttribute(xml, "value", value);
    }

    private static void writeAttribute(XMLStreamWriter xml, String name, Object value) throws XMLStreamException {
        xml.writeAttribute(name, String.valueOf(value));
    }
}
