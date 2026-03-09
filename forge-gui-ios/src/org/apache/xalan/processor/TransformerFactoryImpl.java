package org.apache.xalan.processor;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * iOS-only identity implementation of {@code javax.xml.transform.TransformerFactory}.
 *
 * <p>robovmx's robovm-rt inherits Android's libcore, whose
 * {@code javax.xml.transform.TransformerFactory.newInstance()} has a hardcoded
 * fallback that tries to load {@code org.apache.xalan.processor.TransformerFactoryImpl}
 * when no provider is found via ServiceLoader.  On iOS that class is absent
 * from the bundled Xalan, causing:
 * <pre>
 *   java.lang.NoClassDefFoundError: org.apache.xalan.processor.TransformerFactoryImpl
 *       at javax.xml.transform.TransformerFactory.newInstance(TransformerFactory.java:80)
 * </pre>
 *
 * <p>This class lives in {@code forge-gui-ios} so it is compiled into the iOS
 * binary only.  It provides exactly the functionality that Forge uses:
 * zero-argument {@link #newTransformer()} returning an identity
 * {@link Transformer} that serialises a {@link DOMSource} to a
 * {@link StreamResult} (Writer or OutputStream).
 *
 * <p>XSLT stylesheet transforms ({@link #newTransformer(Source)} and
 * {@link #newTemplates(Source)}) are not used by Forge and throw
 * {@link UnsupportedOperationException}.
 */
public final class TransformerFactoryImpl extends javax.xml.transform.TransformerFactory {

    // ── TransformerFactory abstract method implementations ────────────────────

    @Override
    public Transformer newTransformer() throws TransformerConfigurationException {
        return new IdentityTransformer();
    }

    @Override
    public Transformer newTransformer(Source source) throws TransformerConfigurationException {
        throw new UnsupportedOperationException(
                "forge-gui-ios TransformerFactoryImpl: XSLT stylesheet transforms not supported");
    }

    @Override
    public Templates newTemplates(Source source) throws TransformerConfigurationException {
        throw new UnsupportedOperationException(
                "forge-gui-ios TransformerFactoryImpl: newTemplates not supported");
    }

    @Override
    public Source getAssociatedStylesheet(Source source, String media, String title, String charset)
            throws TransformerConfigurationException {
        return null;
    }

    @Override
    public void setURIResolver(URIResolver resolver) { /* no-op */ }

    @Override
    public URIResolver getURIResolver() { return null; }

    @Override
    public void setFeature(String name, boolean value) throws TransformerConfigurationException { /* no-op */ }

    @Override
    public boolean getFeature(String name) { return false; }

    @Override
    public void setAttribute(String name, Object value) { /* no-op */ }

    @Override
    public Object getAttribute(String name) { return null; }

    @Override
    public void setErrorListener(ErrorListener listener) { /* no-op */ }

    @Override
    public ErrorListener getErrorListener() { return null; }

    // ── Identity Transformer ──────────────────────────────────────────────────

    /**
     * An identity {@link Transformer} that serialises a DOM {@link DOMSource}
     * to a {@link StreamResult}.
     *
     * <p>Supports the output properties used by {@code XmlUtil}:
     * <ul>
     *   <li>{@code OutputKeys.OMIT_XML_DECLARATION} — always obeyed (declaration omitted)</li>
     *   <li>{@code OutputKeys.INDENT} — obeyed; 4-space indentation when "yes"</li>
     * </ul>
     */
    private static final class IdentityTransformer extends Transformer {

        private final Map<String, String> outputProperties = new HashMap<>();
        private URIResolver uriResolver;
        private ErrorListener errorListener;

        @Override
        public void transform(Source source, Result result) throws TransformerException {
            if (!(source instanceof DOMSource)) {
                throw new TransformerException(
                        "forge-gui-ios IdentityTransformer: only DOMSource is supported");
            }
            final Node node = ((DOMSource) source).getNode();
            final boolean indent = !"no".equalsIgnoreCase(outputProperties.get("indent"));

            try {
                final Writer writer = resolveWriter(result);
                if (writer == null) {
                    throw new TransformerException(
                            "forge-gui-ios IdentityTransformer: StreamResult has no Writer or OutputStream");
                }
                try {
                    appendNode(node, writer, 0, indent);
                    writer.flush();
                } finally {
                    // Close only if we opened it (i.e. OutputStream wrapped by us).
                    if (result instanceof StreamResult) {
                        final StreamResult sr = (StreamResult) result;
                        if (sr.getOutputStream() != null && sr.getWriter() == null) {
                            writer.close();
                        }
                    }
                }
            } catch (final IOException e) {
                throw new TransformerException("forge-gui-ios IdentityTransformer: IO error", e);
            }
        }

        private static Writer resolveWriter(Result result) throws IOException {
            if (!(result instanceof StreamResult)) return null;
            final StreamResult sr = (StreamResult) result;
            if (sr.getWriter() != null) return sr.getWriter();
            if (sr.getOutputStream() != null) {
                return new java.io.OutputStreamWriter(sr.getOutputStream(), "UTF-8");
            }
            if (sr.getSystemId() != null) {
                // Strip file:// prefix if present.
                String path = sr.getSystemId();
                if (path.startsWith("file://")) path = path.substring(7);
                else if (path.startsWith("file:")) path = path.substring(5);
                return new java.io.FileWriter(path);
            }
            return null;
        }

        // ── DOM serialiser ────────────────────────────────────────────────────

        private static void appendNode(final Node node, final Writer out,
                                       final int depth, final boolean indent)
                throws IOException {
            switch (node.getNodeType()) {
                case Node.DOCUMENT_NODE: {
                    final NodeList kids = node.getChildNodes();
                    for (int i = 0; i < kids.getLength(); i++) {
                        appendNode(kids.item(i), out, 0, indent);
                    }
                    break;
                }
                case Node.ELEMENT_NODE: {
                    final String pad = indent ? mkIndent(depth) : "";
                    out.write(pad);
                    out.write('<');
                    out.write(node.getNodeName());
                    final NamedNodeMap attrs = node.getAttributes();
                    if (attrs != null) {
                        for (int i = 0; i < attrs.getLength(); i++) {
                            final Node a = attrs.item(i);
                            out.write(' ');
                            out.write(a.getNodeName());
                            out.write("=\"");
                            out.write(escapeAttr(a.getNodeValue()));
                            out.write('"');
                        }
                    }
                    final NodeList children = node.getChildNodes();
                    if (children == null || children.getLength() == 0) {
                        out.write("/>");
                        if (indent) out.write('\n');
                    } else {
                        boolean hasElemChild = false;
                        for (int i = 0; i < children.getLength(); i++) {
                            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                                hasElemChild = true;
                                break;
                            }
                        }
                        if (hasElemChild) {
                            out.write('>');
                            if (indent) out.write('\n');
                            for (int i = 0; i < children.getLength(); i++) {
                                appendNode(children.item(i), out, depth + 1, indent);
                            }
                            out.write(pad);
                            out.write("</");
                            out.write(node.getNodeName());
                            out.write('>');
                            if (indent) out.write('\n');
                        } else {
                            out.write('>');
                            for (int i = 0; i < children.getLength(); i++) {
                                appendNode(children.item(i), out, -1, indent);
                            }
                            out.write("</");
                            out.write(node.getNodeName());
                            out.write('>');
                            if (indent) out.write('\n');
                        }
                    }
                    break;
                }
                case Node.TEXT_NODE: {
                    final String val = node.getNodeValue();
                    if (val == null) break;
                    if (depth < 0) {
                        out.write(escapeText(val));
                    } else {
                        final String trimmed = val.trim();
                        if (!trimmed.isEmpty()) {
                            if (indent && depth >= 0) out.write(mkIndent(depth));
                            out.write(escapeText(trimmed));
                            if (indent) out.write('\n');
                        }
                    }
                    break;
                }
                case Node.CDATA_SECTION_NODE: {
                    if (indent && depth >= 0) out.write(mkIndent(depth));
                    out.write("<![CDATA[");
                    out.write(node.getNodeValue() != null ? node.getNodeValue() : "");
                    out.write("]]>");
                    if (indent) out.write('\n');
                    break;
                }
                case Node.COMMENT_NODE: {
                    if (indent) out.write(mkIndent(depth));
                    out.write("<!--");
                    out.write(node.getNodeValue() != null ? node.getNodeValue() : "");
                    out.write("-->");
                    if (indent) out.write('\n');
                    break;
                }
                default:
                    break;
            }
        }

        private static String mkIndent(final int depth) {
            if (depth <= 0) return "";
            final char[] buf = new char[depth * 4];
            for (int i = 0; i < buf.length; i++) buf[i] = ' ';
            return new String(buf);
        }

        private static String escapeAttr(final String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;")
                    .replace("\"", "&quot;").replace("'", "&apos;");
        }

        private static String escapeText(final String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        // ── Transformer boilerplate ───────────────────────────────────────────

        @Override
        public void setParameter(String name, Object value) { /* no-op */ }

        @Override
        public Object getParameter(String name) { return null; }

        @Override
        public void clearParameters() { /* no-op */ }

        @Override
        public void setURIResolver(URIResolver resolver) { this.uriResolver = resolver; }

        @Override
        public URIResolver getURIResolver() { return uriResolver; }

        @Override
        public void setOutputProperties(Properties oformat) {
            if (oformat != null) {
                for (final String key : oformat.stringPropertyNames()) {
                    outputProperties.put(key, oformat.getProperty(key));
                }
            }
        }

        @Override
        public Properties getOutputProperties() {
            final Properties p = new Properties();
            for (final Map.Entry<String, String> e : outputProperties.entrySet()) {
                p.setProperty(e.getKey(), e.getValue());
            }
            return p;
        }

        @Override
        public void setOutputProperty(String name, String value) throws IllegalArgumentException {
            outputProperties.put(name, value);
        }

        @Override
        public String getOutputProperty(String name) throws IllegalArgumentException {
            return outputProperties.get(name);
        }

        @Override
        public void setErrorListener(ErrorListener listener) { this.errorListener = listener; }

        @Override
        public ErrorListener getErrorListener() { return errorListener; }
    }
}
