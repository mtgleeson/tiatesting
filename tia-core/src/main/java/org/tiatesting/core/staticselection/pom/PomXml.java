package org.tiatesting.core.staticselection.pom;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * DOM helpers for reading POMs: a hardened, namespace-agnostic parser and a canonical text form of
 * an element subtree used to compare two versions of a POM section while ignoring whitespace,
 * comments, attribute order and the order of identity-keyed list entries.
 */
final class PomXml {

    /**
     * List elements whose entries are keyed by identity rather than position, so reordering them
     * does not change the canonical form.
     */
    private static final Set<String> UNORDERED_LISTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "dependencies", "exclusions", "executions", "plugins", "repositories", "pluginRepositories")));

    private PomXml() {
    }

    /**
     * Parse POM text into a DOM document. DTDs, external entities and XInclude are disabled so a
     * POM read from the repository cannot make the parser fetch or expand anything.
     *
     * @param xml the POM text.
     * @return the parsed document.
     * @throws PomParseException if the text is not well-formed XML or the parser can't be configured.
     */
    static Document parse(final String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // DefaultHandler rethrows fatal errors and ignores the rest, instead of printing to stderr.
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new PomParseException("Could not parse POM: " + e.getMessage(), e);
        }
    }

    /**
     * The element's name without any namespace prefix.
     *
     * @param element the element.
     * @return the local name.
     */
    static String name(final Element element) {
        String nodeName = element.getNodeName();
        int colon = nodeName.indexOf(':');
        return colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
    }

    /**
     * The element's direct child elements, in document order.
     *
     * @param element the parent element; may be {@code null}.
     * @return the child elements; empty when {@code element} is {@code null}.
     */
    static List<Element> children(final Element element) {
        List<Element> children = new ArrayList<>();
        if (element == null) {
            return children;
        }
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) node);
            }
        }
        return children;
    }

    /**
     * The first direct child element with the given name.
     *
     * @param element the parent element; may be {@code null}.
     * @param childName the child's local name.
     * @return the child, or {@code null} when absent.
     */
    static Element child(final Element element, final String childName) {
        for (Element child : children(element)) {
            if (name(child).equals(childName)) {
                return child;
            }
        }
        return null;
    }

    /**
     * The direct child elements with the given name, in document order.
     *
     * @param element the parent element; may be {@code null}.
     * @param childName the children's local name.
     * @return the matching children; empty when none.
     */
    static List<Element> children(final Element element, final String childName) {
        List<Element> matching = new ArrayList<>();
        for (Element child : children(element)) {
            if (name(child).equals(childName)) {
                matching.add(child);
            }
        }
        return matching;
    }

    /**
     * The trimmed text of the first direct child element with the given name.
     *
     * @param element the parent element; may be {@code null}.
     * @param childName the child's local name.
     * @return the trimmed text, or {@code null} when the child is absent.
     */
    static String childText(final Element element, final String childName) {
        Element child = child(element, childName);
        return child == null ? null : text(child);
    }

    /**
     * The element's own text (direct text and CDATA nodes, not descendants), trimmed.
     *
     * @param element the element.
     * @return the trimmed text; empty when there is none.
     */
    static String text(final Element element) {
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        return text.toString().trim();
    }

    /**
     * Collect every text and attribute value in the element's subtree, for scanning property
     * references.
     *
     * @param element the subtree root; may be {@code null}.
     * @param values the accumulator.
     */
    static void collectValues(final Element element, final List<String> values) {
        if (element == null) {
            return;
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            values.add(attributes.item(i).getNodeValue());
        }
        String text = text(element);
        if (!text.isEmpty()) {
            values.add(text);
        }
        for (Element child : children(element)) {
            collectValues(child, values);
        }
    }

    /**
     * Build the canonical text form of an element subtree with property references resolved:
     * name, sorted attributes, trimmed text and child forms. Comments and whitespace are ignored,
     * and the entries of identity-keyed lists (dependencies, plugins, executions, ...) are sorted
     * so reordering them is not a change. Two subtrees are equivalent when their canonical forms
     * are equal.
     *
     * @param element the subtree root; may be {@code null}.
     * @param resolver resolves {@code ${...}} references in text and attribute values.
     * @return the canonical form; empty for {@code null}.
     */
    static String canonical(final Element element, final PomPropertyResolver resolver) {
        if (element == null) {
            return "";
        }
        StringBuilder form = new StringBuilder(name(element));
        NamedNodeMap attributes = element.getAttributes();
        if (attributes.getLength() > 0) {
            Map<String, String> sorted = new TreeMap<>();
            for (int i = 0; i < attributes.getLength(); i++) {
                Attr attribute = (Attr) attributes.item(i);
                if (!attribute.getName().startsWith("xmlns")) {
                    sorted.put(attribute.getName(), resolver.resolve(attribute.getValue()));
                }
            }
            form.append(sorted);
        }

        List<String> childForms = new ArrayList<>();
        for (Element child : children(element)) {
            childForms.add(canonical(child, resolver));
        }
        if (UNORDERED_LISTS.contains(name(element))) {
            Collections.sort(childForms);
        }
        form.append('{').append(resolver.resolve(text(element)));
        for (String childForm : childForms) {
            form.append('|').append(childForm);
        }
        return form.append('}').toString();
    }
}
