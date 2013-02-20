/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test.vm.libvirt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import org.midonet.functional_test.vm.HypervisorType;
import org.midonet.functional_test.vm.VMController;
import static org.midonet.functional_test.vm.libvirt.LibvirtUtils.uriForHypervisorType;

/**
 * Author: Toader Mihai Claudiu <mtoader@midokura.com>
 * <p/>
 * Date: 11/10/11
 * Time: 10:06 AM
 */
public class DomainController implements VMController {

    private final static Logger log = LoggerFactory.getLogger(DomainController.class);

    Domain domain;
    private String hostName;
    HypervisorType hypervisorType;
    String domainName;

    protected DomainController(HypervisorType hypervisorType, String domainName, String hostName) {
        this.hypervisorType = hypervisorType;
        this.domainName = domainName;
        this.hostName = hostName;
        this.domain = locateDomain();
    }

    public DomainController(HypervisorType hypervisorType, Domain domain, String hostName) {
        this.hypervisorType = hypervisorType;
        this.domain = domain;
        this.hostName = hostName;
        this.domainName = getDomainName();
    }

    public String getDomainName() {
        return executeWithDomain(new DomainAwareExecutor<String>() {
            @Override
            public String execute(Domain domain) throws LibvirtException {
                return domain.getName();
            }
        });
    }

    private Domain locateDomain() {
        return executeWithDomain(new DomainAwareExecutor<Domain>() {
            @Override
            public Domain execute(Domain domain) throws LibvirtException {
                return domain;
            }
        });
    }

    public void startup() {
        executeWithDomain(new DomainAwareExecutor<Integer>() {
            @Override
            public Integer execute(Domain domain) throws LibvirtException {
                return domain.create();
            }
        });
    }

    @Override
    public void destroy() {
        executeWithDomain(new DomainAwareExecutor<Void>() {
            @Override
            public Void execute(Domain domain) throws LibvirtException {
                try {
                    domain.getJobInfo();
                    domain.destroy();
                } catch (LibvirtException ex) {
                    // if the domain is not active it will throw an error.
                }

                domain.undefine();

                return null;
            }
        });
    }

    @Override
    public boolean isRunning() {
        Boolean isRunningStatus = executeWithDomain(new DomainAwareExecutor<Boolean>() {
            @Override
            public Boolean execute(Domain domain) throws LibvirtException {
                try {
                    domain.getJobInfo();
                    return true;
                } catch (LibvirtException e) {
                    return false;
                }
            }
        });

        return isRunningStatus != null && isRunningStatus;
    }

    @Override
    public String getNetworkMacAddress() {
        return executeWithDomain(new DomainAwareExecutor<String>() {
            @Override
            public String execute(Domain domain) throws LibvirtException {

                /*Domain.XMLFlags.VIR_DOMAIN_XML_INACTIVE*/
                String xmlDescription = domain.getXMLDesc(2);

                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

                try {

                    //Using factory get an instance of document builder
                    DocumentBuilder db = dbf.newDocumentBuilder();

                    //parse using builder to get DOM representation of the XML file
                    Document dom = db.parse(
                        new ByteArrayInputStream(
                            ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                xmlDescription).getBytes("UTF-8")));

                    XPathFactory xPathFactory = XPathFactory.newInstance();

                    XPath xPath = xPathFactory.newXPath();
                    XPathExpression xPathExpression =
                        xPath.compile("/domain/devices/interface/mac/@address");

                    return xPathExpression.evaluate(dom);
                } catch (ParserConfigurationException pce) {
                    pce.printStackTrace();
                } catch (SAXException se) {
                    se.printStackTrace();
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                } catch (XPathExpressionException e) {
                    e.printStackTrace();
                }

                return "";
            }
        });
    }

    @Override
    public String getHostName() {
        return hostName;
    }

    public void shutdown() {
        executeWithDomain(new DomainAwareExecutor<Void>() {
            @Override
            public Void execute(Domain domain) throws LibvirtException {
                domain.destroy();
                return null;
            }
        });
    }

    private <T> T executeWithDomain(DomainAwareExecutor<T> callback) {
        return executeWithDomain(callback, false);
    }

    private <T> T executeWithDomain(DomainAwareExecutor<T> callback, boolean readOnly) {
        try {
            Domain actualDomain = domain;
            if (actualDomain == null) {
                Connect connection = new Connect(uriForHypervisorType(hypervisorType), readOnly);

                actualDomain = connection.domainLookupByName(domainName);
            }

            if (actualDomain != null) {
                return callback.execute(actualDomain);
            }

        } catch (LibvirtException ex) {
            log.error("Exception while working with a Domain: ", ex);
        }

        return null;
    }

    public interface DomainAwareExecutor<T> {
        public T execute(Domain domain) throws LibvirtException;
    }
}