package com.atomeng.erp.rpt.domain.hwpx;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.rpt.domain.RptCodes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** OWPML 네임스페이스 상수 + 안전한 DOM 파서(DTD · 외부 엔터티 금지) · 직렬화 · 탐색 도우미 */
public final class Owpml {

    public static final String HP = "http://www.hancom.co.kr/hwpml/2011/paragraph";
    public static final String HS = "http://www.hancom.co.kr/hwpml/2011/section";
    public static final String HH = "http://www.hancom.co.kr/hwpml/2011/head";
    public static final String HC = "http://www.hancom.co.kr/hwpml/2011/core";
    public static final String OPF = "http://www.idpf.org/2007/opf/";
    public static final String OCF = "urn:oasis:names:tc:opendocument:xmlns:container";

    private Owpml() {}

    public static Document parse(byte[] xml, String name) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new DomainException(RptCodes.HWPX_INVALID, "XML 형식 오류(" + name + "): " + e.getMessage());
        }
    }

    public static byte[] serialize(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            doc.setXmlStandalone(true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("XML 직렬화 실패", e);
        }
    }

    /** 문서 순서대로 ns:local 요소 전부 */
    public static List<Element> all(Node root, String ns, String local) {
        NodeList nl = root instanceof Document d ? d.getElementsByTagNameNS(ns, local)
                : ((Element) root).getElementsByTagNameNS(ns, local);
        List<Element> out = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    /** 직계 자식 ns:local 요소 */
    public static List<Element> children(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    /** 문단(hp:p) 직계 run 의 hp:t 텍스트를 이어 붙인 값 */
    public static String paragraphText(Element p) {
        StringBuilder sb = new StringBuilder();
        for (Element run : children(p, HP, "run")) {
            for (Element t : children(run, HP, "t")) {
                sb.append(t.getTextContent());
            }
        }
        return sb.toString();
    }
}
