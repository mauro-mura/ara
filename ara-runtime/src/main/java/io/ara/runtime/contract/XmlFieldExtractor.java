package io.ara.runtime.contract;

import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Extracts the text content of a node from an XML payload using an XPath expression.
 *
 * <p>Speculare a {@link JsonFieldExtractor} ma per output XML/XHTML. Il valore estratto
 * è restituito come stringa plain-text (il testo concatenato di tutti i nodi testo figli).
 *
 * <pre>{@code
 * // Estrae il contenuto del tag <summary>
 * XmlFieldExtractor.xpath("/response/summary")
 *
 * // Estrae un attributo
 * XmlFieldExtractor.xpath("/response/item/@status")
 *
 * // Estrae il testo del primo <finding> in una lista
 * XmlFieldExtractor.xpath("/response/findings/finding[1]")
 * }</pre>
 *
 * <p>Can be used as both an {@link InputProcessor} and an {@link OutputProcessor}.
 */
public final class XmlFieldExtractor implements InputProcessor, OutputProcessor {

    private static final DocumentBuilderFactory DBF;

    static {
        DBF = DocumentBuilderFactory.newInstance();
        // disable XXE
        try {
            DBF.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DBF.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DBF.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
            // older parsers may not support all features; best-effort
        }
    }

    private final String xpathExpr;

    private XmlFieldExtractor(String xpathExpr) {
        this.xpathExpr = Objects.requireNonNull(xpathExpr, "xpathExpr must not be null");
    }

    public static XmlFieldExtractor xpath(String expression) {
        return new XmlFieldExtractor(expression);
    }

    @Override
    public ProcessingResult process(String payload) {
        if (payload == null || payload.isBlank()) {
            return ProcessingResult.reject("XmlFieldExtractor: payload is empty");
        }
        try {
            Document doc = DBF.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
            XPath xp = XPathFactory.newInstance().newXPath();
            NodeList nodes = (NodeList) xp.evaluate(xpathExpr, doc, XPathConstants.NODESET);
            if (nodes == null || nodes.getLength() == 0) {
                return ProcessingResult.reject(
                        "XPath '" + xpathExpr + "' matched no nodes in payload");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(nodes.item(i).getTextContent());
            }
            return ProcessingResult.pass(sb.toString().strip());
        } catch (Exception e) {
            return ProcessingResult.reject(
                    "XmlFieldExtractor: failed to parse XML: " + e.getMessage());
        }
    }
}
