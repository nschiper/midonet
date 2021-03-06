/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.rest_api;

import java.util.HashMap;
import java.util.Map;
import javax.ws.rs.core.Response;

import org.midonet.client.VendorMediaType;
import org.midonet.client.dto.*;
import static org.midonet.client.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_CHAIN_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_PORTGROUP_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_PORT_LINK_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_PORT_V2_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_ROUTER_JSON_V2;
import static org.midonet.client.VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_LOAD_BALANCER_JSON;


/**
 * Class to assist creating a network topology in unit tests. An example usage:
 *
 * <pre>
 * {@code
 *    Topology t;
 *
 *    @Before
 *    void setup() {
 *      t = new Topology.builder()
 *            .create("router1", router1)
 *            .create("router1", "port1", port11)   // Tag each object
 *            .build();  // This actually creates the objects in the server,
 *                       // and verifies that the POST operations succeeded.
 *     }
 *
 *    @Test
 *    void testPortCreate() {
 *       // Get the tagged object
 *       DtoRouter router1 = t.getRouter("router1");
 *       // Run this test in the setup created.
 *    }
 *  }
 * </pre>
 */
public class Topology {
    private final Builder builder;

    public static class Builder {

        private final DtoWebResource resource;

        private DtoApplication app;
        private final String appMediaType;
        private final Map<String, DtoTenant> tenants;
        private final Map<String, DtoRouter> routers;
        private final Map<String, DtoBridge> bridges;
        private final Map<String, DtoRuleChain> chains;
        private final Map<String, DtoRouterPort> routerPorts;
        private final Map<String, DtoBridgePort> bridgePorts;
        private final Map<String, DtoPortGroup> portGroups;
        private final Map<String, DtoLoadBalancer> loadBalancers;

        private final Map<String, String> tagToInChains;
        private final Map<String, String> tagToOutChains;
        private final Map<String, String> tagToRouters;
        private final Map<String, String> tagToBridges;
        private final Map<String, String> tagToLoadBalancers;
        private final Map<String, String> links;

        public Builder(DtoWebResource resource) {
            this(resource, VendorMediaType.APPLICATION_JSON_V5);
        }

        public Builder(DtoWebResource resource, String appMediaType) {
            this.resource = resource;
            this.tenants = new HashMap<String, DtoTenant>();
            this.routers = new HashMap<String, DtoRouter>();
            this.bridges = new HashMap<String, DtoBridge>();
            this.chains = new HashMap<String, DtoRuleChain>();
            this.routerPorts = new HashMap<String, DtoRouterPort>();
            this.bridgePorts = new HashMap<String, DtoBridgePort>();
            this.portGroups = new HashMap<String, DtoPortGroup>();
            this.loadBalancers = new HashMap<String, DtoLoadBalancer>();

            this.links = new HashMap<String, String>();
            this.tagToInChains = new HashMap<String, String>();
            this.tagToOutChains = new HashMap<String, String>();
            this.tagToRouters = new HashMap<String, String>();
            this.tagToBridges = new HashMap<String, String>();
            this.tagToLoadBalancers = new HashMap<String, String>();

            this.appMediaType = appMediaType;
        }

        public DtoWebResource getResource() {
            return this.resource;
        }

        public Builder create(String tag, DtoRouter obj) {
            this.routers.put(tag, obj);
            return this;
        }

        public Builder create(String tag, DtoBridge obj) {
            this.bridges.put(tag, obj);
            return this;
        }

        public Builder create(String tag, DtoRuleChain obj) {
            this.chains.put(tag, obj);
            return this;
        }

        public Builder create(String routerTag, String tag,
                              DtoRouterPort obj) {
            this.routerPorts.put(tag, obj);
            this.tagToRouters.put(tag, routerTag);
            return this;
        }

        public Builder create(String bridgeTag, String tag,
                              DtoBridgePort obj) {
            this.bridgePorts.put(tag, obj);
            this.tagToBridges.put(tag, bridgeTag);
            return this;
        }

        public Builder create(String tag, DtoPortGroup obj) {
            this.portGroups.put(tag, obj);
            return this;
        }

        public Builder create(String tag, DtoLoadBalancer obj) {
            this.loadBalancers.put(tag, obj);
            return this;
        }

        public Builder link(String portTag1, String portTag2) {

            if (!this.routerPorts.containsKey(portTag1)
                && !this.bridgePorts.containsKey(portTag1)) {
                throw new IllegalArgumentException(
                    "portTag1 is not a valid port");
            }

            if (!this.routerPorts.containsKey(portTag2)
                && !this.bridgePorts.containsKey(portTag2)) {
                throw new IllegalArgumentException(
                    "portTag2 is not a valid port");
            }

            this.links.put(portTag1, portTag2);
            return this;
        }

        public Builder applyInChain(String tag, String chainTag) {
            this.tagToInChains.put(tag, chainTag);
            return this;
        }

        public Builder applyOutChain(String tag, String chainTag) {
            this.tagToOutChains.put(tag, chainTag);
            return this;
        }

        public Builder setLoadBalancer(String tag, String loadBalancerTag) {
            this.tagToLoadBalancers.put(tag, loadBalancerTag);
            return this;
        }

        private DtoPort findPort(String tag) {
            if (bridgePorts.containsKey(tag)) {
                return bridgePorts.get(tag);
            } else {
                return routerPorts.get(tag);
            }
        }

        public Topology build() {

            this.app = resource.getWebResource().path("/").accept(
                    appMediaType).get(DtoApplication.class);

            for (Map.Entry<String, DtoRuleChain> entry : chains.entrySet()) {
                DtoRuleChain obj = entry.getValue();
                obj = resource.postAndVerifyCreated(app.getChains(),
                    APPLICATION_CHAIN_JSON, obj, DtoRuleChain.class);
                entry.setValue(obj);
            }

            // Create loadBalancers
            for (Map.Entry<String, DtoLoadBalancer> entry : loadBalancers.entrySet()) {
                DtoLoadBalancer obj = entry.getValue();
                obj = resource.postAndVerifyCreated(app.getLoadBalancers(),
                        APPLICATION_LOAD_BALANCER_JSON, obj, DtoLoadBalancer.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoRouter> entry : routers.entrySet()) {

                DtoRouter obj = entry.getValue();

                // Set the loadBalancer, if any
                String tag = tagToLoadBalancers.get(entry.getKey());
                if (tag != null) {
                    DtoLoadBalancer lb = loadBalancers.get(tag);
                    obj.setLoadBalancerId(lb.getId());
                }

                // Set the inbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setInboundFilterId(c.getId());
                }

                // Set the outbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setOutboundFilterId(c.getId());
                }

                obj = resource.postAndVerifyCreated(app.getRouters(),
                    APPLICATION_ROUTER_JSON_V2, obj, DtoRouter.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoBridge> entry : bridges.entrySet()) {

                DtoBridge obj = entry.getValue();

                // Set the inbound chain ID
                String tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setInboundFilterId(c.getId());
                }

                // Set the outbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setOutboundFilterId(c.getId());
                }
                obj = resource.postAndVerifyCreated(app.getBridges(),
                    APPLICATION_BRIDGE_JSON, obj, DtoBridge.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoPortGroup> entry
                : portGroups.entrySet()) {

                DtoPortGroup obj = entry.getValue();

                obj = resource.postAndVerifyCreated(app.getPortGroups(),
                    APPLICATION_PORTGROUP_JSON, obj, DtoPortGroup.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoRouterPort> entry :
                routerPorts.entrySet()) {

                DtoRouterPort obj = entry.getValue();

                // Set the router ID
                String tag = tagToRouters.get(entry.getKey());
                DtoRouter r = routers.get(tag);
                obj.setDeviceId(r.getId());

                // Set the inbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setInboundFilterId(c.getId());
                }

                // Set the outbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setOutboundFilterId(c.getId());
                }

                obj = resource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_V2_JSON, entry.getValue(),
                    DtoRouterPort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoBridgePort> entry : bridgePorts
                .entrySet()) {

                DtoBridgePort obj = entry.getValue();

                // Set the bridge ID
                String tag = tagToBridges.get(entry.getKey());
                DtoBridge b = bridges.get(tag);
                obj.setDeviceId(b.getId());

                // Set the inbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setInboundFilterId(c.getId());
                }

                // Set the outbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setOutboundFilterId(c.getId());
                }

                obj = resource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_V2_JSON, entry.getValue(),
                    DtoBridgePort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, String> entry : links.entrySet()) {
                // Get the Interior ports
                DtoPort port1 = findPort(entry.getKey());
                DtoPort port2 = findPort(entry.getValue());

                DtoLink link = new DtoLink();
                link.setPeerId(port2.getId());
                resource.postAndVerifyStatus(port1.getLink(),
                    APPLICATION_PORT_LINK_JSON, link,
                    Response.Status.CREATED.getStatusCode());
            }

            // Tenants are created behind the scene.  Get all tenants
            DtoTenant[] tenantList = resource.getAndVerifyOk(app.getTenants(),
                APPLICATION_TENANT_COLLECTION_JSON, DtoTenant[].class);
            if (tenantList != null) {
                for (DtoTenant t : tenantList) {
                    tenants.put(t.getId(), t);
                }
            }

            return new Topology(this);
        }
    }

    private Topology(Builder builder) {
        this.builder = builder;
    }

    public DtoApplication getApplication() {
        return this.builder.app;
    }

    public DtoTenant getTenant(String id) {
        return this.builder.tenants.get(id);
    }

    public DtoRouter getRouter(String tag) {
        return this.builder.routers.get(tag);
    }

    public DtoBridge getBridge(String tag) {
        return this.builder.bridges.get(tag);
    }

    public DtoPortGroup getPortGroup(String tag) {
        return this.builder.portGroups.get(tag);
    }

    public DtoRuleChain getChain(String tag) {
        return this.builder.chains.get(tag);
    }

    public DtoRouterPort getRouterPort(String tag) {
        return this.builder.routerPorts.get(tag);
    }

    public DtoBridgePort getBridgePort(String tag) {
        return this.builder.bridgePorts.get(tag);
    }

    public DtoLoadBalancer getLoadBalancer(String tag) {
        return this.builder.loadBalancers.get(tag);
    }
}
