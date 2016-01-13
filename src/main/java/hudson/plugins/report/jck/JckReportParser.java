package hudson.plugins.report.jck;

import hudson.plugins.report.jck.model.Report;
import hudson.plugins.report.jck.model.Test;
import hudson.plugins.report.jck.model.TestOutput;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import static javax.xml.stream.XMLStreamConstants.CDATA;
import static javax.xml.stream.XMLStreamConstants.CHARACTERS;
import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

class JckReportParser {

    private final XMLInputFactory inputFactory = createInputFactory();

    public Report parseReport(InputStream reportStream) throws Exception {
        try (Reader reader = new InputStreamReader(reportStream, "UTF-8")) {
            Report report = parseReport(reader);
            return report;
        }
    }

    private Report parseReport(Reader reader) throws Exception {
        XMLStreamReader in = inputFactory.createXMLStreamReader(reader);
        if (!fastForwardToElement(in, "TestResults")) {
            throw new Exception("TestResults element was not found in provided XML stream");
        }
        return processTestResults(in);
    }

    @SuppressWarnings("empty-statement")
    private boolean fastForwardToElement(XMLStreamReader reader, String element) throws Exception {
        while (reader.hasNext()) {
            if (reader.next() == START_ELEMENT && element.equals(reader.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    private XMLInputFactory createInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty("http://java.sun.com/xml/stream/properties/report-cdata-event", Boolean.TRUE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.FALSE);
        return factory;
    }

    private Report processTestResults(XMLStreamReader in) throws Exception {
        Map<String, AtomicInteger> countersMap = createCountersMap();
        List<Test> list = new ArrayList<>();
        while (in.hasNext()) {
            int event = in.next();
            if (event == END_ELEMENT && "TestResults".equals(in.getLocalName())) {
                break;
            }
            if (event == START_ELEMENT && "TestResult".equals(in.getLocalName())) {
                String testStatus = findAttributeValue(in, "status");
                incrementCounters(testStatus, countersMap);
                if (isProblematic(testStatus)) {
                    list.add(processProblematicTest(in));
                }
            }
        }
        Collections.sort(list);
        return new Report(
                countersMap.get("PASSED").get(),
                countersMap.get("NOT_RUN").get(),
                countersMap.get("FAILED").get(),
                countersMap.get("ERROR").get(),
                countersMap.get("TOTAL").get(),
                list);
    }

    private Test processProblematicTest(XMLStreamReader in) throws Exception {
        String url = findAttributeValue(in, "url");
        String status = findAttributeValue(in, "status");
        List<TestOutput> testOutputs = Collections.emptyList();
        String statusLine = null;
        while (in.hasNext()) {
            int event = in.next();
            if (event == END_ELEMENT && "TestResult".equals(in.getLocalName())) {
                break;
            }
            if (event != START_ELEMENT) {
                continue;
            }
            if ("ResultProperties".equals(in.getLocalName())) {
                statusLine = processStatusLine(in);
            }
            if ("Sections".equals(in.getLocalName())) {
                testOutputs = processTestOutputs(in);
            }
        }
        return new Test(url, status, statusLine, testOutputs);
    }

    private String processStatusLine(XMLStreamReader in) throws Exception {
        String line = "";
        while (in.hasNext()) {
            int event = in.next();
            if (event == END_ELEMENT && "ResultProperties".equals(in.getLocalName())) {
                break;
            }
            if (event == START_ELEMENT && "Property".equals(in.getLocalName())) {
                if ("execStatus".equals(findAttributeValue(in, "name"))) {
                    line = findAttributeValue(in, "value");
                }
            }
        }
        return line;
    }

    private List<TestOutput> processTestOutputs(XMLStreamReader in) throws Exception {
        List<TestOutput> list = new ArrayList<>();
        while (in.hasNext()) {
            int event = in.next();
            if (event == END_ELEMENT && "Sections".equals(in.getLocalName())) {
                break;
            }
            if (event == START_ELEMENT && "Section".equals(in.getLocalName())) {
                list.addAll(processOutputSection(in));
            }
        }
        Collections.sort(list);
        return list;
    }

    private List<TestOutput> processOutputSection(XMLStreamReader in) throws Exception {
        String title = findAttributeValue(in, "title");
        List<TestOutput> list = new ArrayList<>();
        while (in.hasNext()) {
            int event = in.next();
            if (event == END_ELEMENT && "Section".equals(in.getLocalName())) {
                break;
            }
            if (event == START_ELEMENT && "Output".equals(in.getLocalName())) {
                list.add(processTestOutput(title, in));
            }
        }
        return list;
    }

    private TestOutput processTestOutput(String sectionTitle, XMLStreamReader in) throws Exception {
        String title = findAttributeValue(in, "title");
        String resultTitle = sectionTitle + " / " + title;
        while (in.hasNext()) {
            int event = in.next();
            if (event == END_ELEMENT && "Output".equals(in.getLocalName())) {
                break;
            }
            if (event == CDATA || event == CHARACTERS) {
                return new TestOutput(resultTitle, in.getText());
            }
        }
        return new TestOutput(resultTitle, "");
    }

    private void incrementCounters(String testStatus, Map<String, AtomicInteger> countersMap) throws Exception {
        AtomicInteger aInt = countersMap.get(testStatus);
        if (aInt == null) {
            throw new Exception("Invalid 'status' attribute value: " + testStatus);
        }
        aInt.incrementAndGet();
        countersMap.get("TOTAL").incrementAndGet();
    }

    private boolean isProblematic(String testStatus) {
        switch (testStatus) {
            case "FAILED":
            case "ERROR":
                return true;
            default:
                return false;
        }
    }

    private String findAttributeValue(XMLStreamReader in, String name) {
        int count = in.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if (name.equals(in.getAttributeLocalName(i))) {
                return in.getAttributeValue(i);
            }
        }
        return null;
    }

    private Map<String, AtomicInteger> createCountersMap() {
        Map<String, AtomicInteger> map = new HashMap<>();
        map.put("NOT_RUN", new AtomicInteger());
        map.put("PASSED", new AtomicInteger());
        map.put("FAILED", new AtomicInteger());
        map.put("ERROR", new AtomicInteger());
        map.put("TOTAL", new AtomicInteger());
        return map;
    }

}
