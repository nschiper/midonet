/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.duration._

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage.{UpdateOp, CreateOp, NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{Port => TopologyPort, Network => TopologyBridge}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.FlowController
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.{Bridge => SimulationBridge}
import org.midonet.midolman.simulation.Bridge.UntaggedVlanId
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.{MAC, IPv4Addr}
import org.midonet.sdn.flows.FlowTagger.{tagForArpRequests, tagForBridgePort, tagForBroadcast, tagForDevice, tagForFloodedFlowsByDstMac, tagForVlanPort}
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class BridgeMapperTest extends MidolmanSpec with TopologyBuilder
                       with TopologyMatchers with MidonetEventually {

    import TopologyBuilder._

    private var store: Storage = _
    private var vt: VirtualTopology = _
    private var config: MidolmanConfig = _
    private var threadId: Long = _

    private final val timeout = 5 seconds
    private final val macTtl = 1 second
    private final val macExpiration = 3 seconds

    registerActors(FlowController -> (() => new FlowController
                                                with MessageAccumulator))

    def fc = FlowController.as[FlowController with MessageAccumulator]

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        config = injector.getInstance(classOf[MidolmanConfig])
        threadId = Thread.currentThread.getId
    }

    protected override def fillConfig(config: HierarchicalConfiguration)
    : HierarchicalConfiguration = {
        super.fillConfig(config)
        config.setProperty("zookeeper.cluster_storage_enabled", true)
        config.setProperty("midolman.enable_bridge_arp", true)
        config.setProperty("bridge.mac_port_mapping_expire_millis",
                           macTtl.toMillis)
        config
    }

    private def createObserver(count: Int = 1)
    : AwaitableObserver[SimulationBridge] = {
        Given("An observer for the bridge mapper")
        // It is possible to receive the initial notification on the current
        // thread, when the device was notified in the mapper's behavior subject
        // previous to the subscription.
        new AwaitableObserver[SimulationBridge](
            count, assert(vt.threadId == Thread.currentThread.getId ||
                          threadId == Thread.currentThread.getId))
    }

    private def testBridgeCreated(bridgeId: UUID,
                                  obs: AwaitableObserver[SimulationBridge],
                                  count: Int, test: Int)
    : TopologyBridge = {
        Given("A bridge mapper")
        val mapper = new BridgeMapper(bridgeId, vt)

        And("A bridge")
        val bridge = createBridge(id = bridgeId)

        When("The bridge is created")
        store.create(bridge)

        And("The observer subscribes to an observable on the mapper")
        Observable.create(mapper).subscribe(obs)

        Then("The observer should receive the bridge device")
        obs.await(5 seconds, count) shouldBe true
        val device = obs.getOnNextEvents.get(test)
        device shouldBeDeviceOf bridge

        bridge
    }

    private def testBridgeUpdated(bridge: TopologyBridge,
                                  obs: AwaitableObserver[SimulationBridge],
                                  count: Int, test: Int)
    : SimulationBridge = {
        When("The bridge is updated")
        store.update(bridge)

        Then("The observer should receive the update")
        obs.await(timeout, count) shouldBe true
        val device = obs.getOnNextEvents.get(test)
        device shouldBeDeviceOf bridge

        device
    }

    private def testBridgeDeleted(bridgeId: UUID,
                                  obs: AwaitableObserver[SimulationBridge],
                                  count: Int)
    : Unit = {
        When("The bridge is deleted")
        store.delete(classOf[TopologyBridge], bridgeId)

        Then("The observer should receive a completed notification")
        obs.await(timeout, count) shouldBe true
        obs.getOnCompletedEvents should not be empty
    }

    feature("Bridge mapper emits notifications for bridge update") {
        scenario("The mapper emits error for non-existing bridge") {
            Given("A bridge identifier")
            val bridgeId = UUID.randomUUID

            And("A bridge mapper")
            val mapper = new BridgeMapper(bridgeId, vt)

            And("An observer to the bridge mapper")
            val obs = new AwaitableObserver[SimulationBridge]

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.await(timeout) shouldBe true
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyBridge]
            e.id shouldBe bridgeId
        }

        scenario("The mapper emits existing bridge") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            testBridgeCreated(bridgeId, obs, count = 0, test = 0)
        }

        scenario("The mapper emits new device on bridge update") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            testBridgeCreated(bridgeId, obs, count = 1, test = 0)
            val bridgeUpdate = createBridge(id = bridgeId, adminStateUp = true)
            testBridgeUpdated(bridgeUpdate, obs, count = 0, test = 1)
        }

        scenario("The mapper completes on bridge delete") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            testBridgeCreated(bridgeId, obs, count = 1, test = 0)
            testBridgeDeleted(bridgeId, obs, count = 0)
        }
    }

    feature("Test port updates") {
        scenario("Create port neither interior nor exterior") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an exterior port for the bridge")
            val portId = UUID.randomUUID
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            store.create(port)

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf bridge
            device.exteriorPorts shouldBe empty
            device.vlanToPort.isEmpty shouldBe true
        }

        scenario("Create and delete exterior port") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating a first exterior port for the bridge")
            val portId1 = UUID.randomUUID
            val port1 = createBridgePort(id = portId1, bridgeId = Some(bridgeId),
                                         hostId = Some(UUID.randomUUID),
                                         interfaceName = Some("iface"))
            store.create(port1)

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            device1 shouldBeDeviceOf bridge

            And("There should be one exterior port but no VLAN ports")
            device1.exteriorPorts should contain only portId1
            device1.vlanToPort.isEmpty shouldBe true

            When("Creating a second exterior port for the bridge")
            val portId2 = UUID.randomUUID
            val port2 = createBridgePort(id = portId2, bridgeId = Some(bridgeId),
                                         hostId = Some(UUID.randomUUID),
                                         interfaceName = Some("iface"))
            store.create(port2)

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true
            val device2 = obs.getOnNextEvents.get(2)
            device2 shouldBeDeviceOf bridge

            And("There should be the two exterior ports but no VLAN ports")
            device2.exteriorPorts should contain allOf (portId1, portId2)
            device2.vlanToPort.isEmpty shouldBe true

            When("Deleting the first exterior port")
            store.delete(classOf[TopologyPort], portId1)

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true
            val device3 = obs.getOnNextEvents.get(3)
            device3 shouldBeDeviceOf bridge

            And("There should be one exterior port but no VLAN ports")
            device3.exteriorPorts should contain only portId2
            device3.vlanToPort.isEmpty shouldBe true

            When("Deleting the second exterior port")
            store.delete(classOf[TopologyPort], portId2)

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device4 = obs.getOnNextEvents.get(4)
            device4 shouldBeDeviceOf bridge

            And("There should be no exterior ports and VLANs")
            device4.exteriorPorts shouldBe empty
            device4.vlanToPort.isEmpty shouldBe true
        }

        scenario("Update exterior port to exterior port") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating a first exterior port for the bridge")
            val portId = UUID.randomUUID
            val hostId = UUID.randomUUID
            val port1 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         hostId = Some(hostId),
                                         interfaceName = Some("iface0"))
            store.create(port1)

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            device1 shouldBeDeviceOf bridge

            And("There should be one exterior port but no VLAN ports")
            device1.exteriorPorts should contain only portId
            device1.vlanToPort.isEmpty shouldBe true

            When("The exterior port is updated")
            val port2 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         hostId = Some(hostId),
                                         interfaceName = Some("iface1"))
            store.update(port2)

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(2)
            device2 shouldBeDeviceOf bridge

            And("There should be one exterior port but no VLANs")
            device2.exteriorPorts should contain only portId
            device2.vlanToPort.isEmpty shouldBe true
        }

        scenario("Existing non-exterior port becomes exterior") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating a first exterior port for the bridge")
            val portId = UUID.randomUUID
            val hostId = UUID.randomUUID
            val port1 = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            store.create(port1)

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            device1 shouldBeDeviceOf bridge

            And("There should be no exterior and no VLAN ports")
            device1.exteriorPorts shouldBe empty
            device1.vlanToPort.isEmpty shouldBe true

            When("The port becomes exterior")
            val port2 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         hostId = Some(hostId),
                                         interfaceName = Some("iface"))
            store.update(port2)

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(2)
            device2 shouldBeDeviceOf bridge

            And("There should be one exterior port but no VLANs")
            device2.exteriorPorts should contain only portId
            device2.vlanToPort.isEmpty shouldBe true
        }

        scenario("Existing exterior port becomes non-exterior") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating a first exterior port for the bridge")
            val portId = UUID.randomUUID
            val hostId = UUID.randomUUID
            val port1 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         hostId = Some(hostId),
                                         interfaceName = Some("iface"))
            store.create(port1)

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            device1 shouldBeDeviceOf bridge

            And("There should be no exterior and no VLAN ports")
            device1.exteriorPorts should contain only portId
            device1.vlanToPort.isEmpty shouldBe true

            When("The port becomes non-exterior")
            val port2 = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            store.update(port2)

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(2)
            device2 shouldBeDeviceOf bridge

            And("There should no exterior port and no VLANs")
            device2.exteriorPorts shouldBe empty
            device2.vlanToPort.isEmpty shouldBe true
        }

        scenario("Create interior port no VLAN peered to a bridge port no VLAN") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLANs")
            device.exteriorPorts shouldBe empty
            device.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be None")
            device.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device.vlanMacTableMap.keySet should contain only UntaggedVlanId
        }

        scenario("Create interior port no VLAN peer to a bridge port VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId),
                                            vlanId = Some(vlanId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLANs")
            device.exteriorPorts shouldBe empty
            device.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be the peer port")
            device.vlanPortId shouldBe Some(peerPortId)

            And("There should be MAC learning tables for each VLAN")
            device.vlanMacTableMap.keySet should contain only UntaggedVlanId
        }

        scenario("Create interior port VLAN peered to a bridge port no VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        vlanId = Some(vlanId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports")
            device.exteriorPorts shouldBe empty

            And("There should be one one VLAN")
            device.vlanToPort.getPort(vlanId) shouldBe portId
            device.vlanToPort.getVlan(portId) shouldBe vlanId

            And("The bridge VLAN peer port ID should be None")
            device.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device.vlanMacTableMap.keySet should contain allOf(UntaggedVlanId, vlanId)
        }

        scenario("Create interior port VLAN peered to a bridge port VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val peerVlanId: Short = 2
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        vlanId = Some(vlanId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId),
                                            vlanId = Some(peerVlanId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports")
            device.exteriorPorts shouldBe empty

            And("There should be one VLAN")
            device.vlanToPort.getPort(vlanId) shouldBe portId
            device.vlanToPort.getVlan(portId) shouldBe vlanId

            And("The bridge VLAN peer port ID should be None")
            device.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device.vlanMacTableMap.keySet should contain allOf(UntaggedVlanId, vlanId)
        }

        scenario("Update interior port no VLAN to VLAN peered to bridge port no VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            And("Waiting for the first update")
            obs.await(timeout, 1) shouldBe true

            When("Updating the port VLAN")
            store.update(port.setPeerId(peerPortId).setVlanId(vlanId))

            Then("The observer should receive two updates")
            obs.await(timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            val device2 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf bridge
            device2 shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLANs on first update")
            device1.exteriorPorts shouldBe empty
            device1.vlanToPort.isEmpty shouldBe true
            device1.vlanMacTableMap.keySet should contain only UntaggedVlanId

            And("The bridge VLAN peer port ID should be None on first update")
            device1.vlanPortId shouldBe None

            And("There should be one VLAN on second update")
            device2.exteriorPorts shouldBe empty
            device2.vlanToPort.getPort(vlanId) shouldBe portId
            device2.vlanToPort.getVlan(portId) shouldBe vlanId

            And("The bridge VLAN peer port ID should be None on second update")
            device2.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device2.vlanMacTableMap.keySet should contain allOf(UntaggedVlanId, vlanId)
        }

        scenario("Update interior port no VLAN to VLAN peered to bridge port VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val peerVlanId: Short = 2
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId),
                                            vlanId = Some(peerVlanId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            And("Waiting for the first update")
            obs.await(timeout, 1) shouldBe true

            When("Updating the port VLAN")
            store.update(port.setPeerId(peerPortId).setVlanId(vlanId))

            Then("The observer should receive two updates")
            obs.await(timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            val device2 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf bridge
            device2 shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLANs on first update")
            device1.exteriorPorts shouldBe empty
            device1.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be None on first update")
            device1.vlanPortId shouldBe Some(peerPortId)

            And("There should be MAC learning tables for each VLAN")
            device1.vlanMacTableMap.keySet should contain only UntaggedVlanId

            And("There should be one VLAN on second update")
            device2.exteriorPorts shouldBe empty
            device2.vlanToPort.getPort(vlanId) shouldBe portId
            device2.vlanToPort.getVlan(portId) shouldBe vlanId

            And("The bridge VLAN peer port ID should be None on second update")
            device2.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device2.vlanMacTableMap.keySet should contain allOf(UntaggedVlanId, vlanId)
        }

        scenario("Update interior port VLAN to no VLAN peered to bridge port no VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        vlanId = Some(vlanId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            And("Waiting for the first update")
            obs.await(timeout, 1) shouldBe true

            When("Updating the port VLAN")
            store.update(port.setPeerId(peerPortId).clearVlanId())

            Then("The observer should receive two updates")
            obs.await(timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            val device2 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf bridge
            device2 shouldBeDeviceOf bridge

            And("There should be one VLAN on first update")
            device1.exteriorPorts shouldBe empty
            device1.vlanToPort.getPort(vlanId) shouldBe portId
            device1.vlanToPort.getVlan(portId) shouldBe vlanId

            And("The bridge VLAN peer port ID should be None on first update")
            device1.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device1.vlanMacTableMap.keySet should contain allOf(UntaggedVlanId, vlanId)

            And("There should be no exterior ports or VLANs on second update")
            device2.exteriorPorts shouldBe empty
            device2.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be None on second update")
            device2.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device2.vlanMacTableMap.keySet should contain only UntaggedVlanId
        }

        scenario("Update interior port VLAN to no VLAN peered to bridge port VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val peerVlanId: Short = 2
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        vlanId = Some(vlanId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId),
                                            vlanId = Some(peerVlanId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            And("Waiting for the first update")
            obs.await(timeout, 1) shouldBe true

            When("Updating the port VLAN")
            store.update(port.setPeerId(peerPortId).clearVlanId())

            Then("The observer should receive two updates")
            obs.await(timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            val device2 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf bridge
            device2 shouldBeDeviceOf bridge

            And("There should be one VLAN on first update")
            device1.exteriorPorts shouldBe empty
            device1.vlanToPort.getPort(vlanId) shouldBe portId
            device1.vlanToPort.getVlan(portId) shouldBe vlanId

            And("The bridge VLAN peer port ID should be None on first update")
            device1.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device1.vlanMacTableMap.keySet should contain allOf(UntaggedVlanId, vlanId)

            And("There should be no exterior ports or VLANs on second update")
            device2.exteriorPorts shouldBe empty
            device2.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be None on second update")
            device2.vlanPortId shouldBe Some(peerPortId)

            And("There should be MAC learning tables for each VLAN")
            device2.vlanMacTableMap.keySet should contain only UntaggedVlanId
        }

        scenario("Update interior port VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId1: Short = 1
            val vlanId2: Short = 2
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port and a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        vlanId = Some(vlanId1))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            And("Waiting for the first update")
            obs.await(timeout, 1) shouldBe true

            When("Updating the port VLAN")
            store.update(port.setPeerId(peerPortId).setVlanId(vlanId2))

            Then("The observer should receive two updates")
            obs.await(timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            val device2 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf bridge
            device2 shouldBeDeviceOf bridge

            And("There should be one VLAN on first update")
            device1.exteriorPorts shouldBe empty
            device1.vlanToPort.getPort(vlanId1) shouldBe portId
            device1.vlanToPort.getVlan(portId) shouldBe vlanId1

            And("The bridge VLAN peer port ID should be None on first update")
            device1.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device1.vlanMacTableMap.keySet should contain allOf(UntaggedVlanId, vlanId1)

            And("There should be one VLAN on second update")
            device2.exteriorPorts shouldBe empty
            device2.vlanToPort.getPort(vlanId2) shouldBe portId
            device2.vlanToPort.getVlan(portId) shouldBe vlanId2

            And("The bridge VLAN peer port ID should be None on second update")
            device2.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device2.vlanMacTableMap.keySet should contain allOf(UntaggedVlanId, vlanId2)
        }

        scenario("Update peer port no VLAN to VLAN (local port has no VLAN)") {
            val bridgeId = UUID.randomUUID
            val peerVlanId: Short = 1
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            And("Waiting for the first update")
            obs.await(timeout, 1) shouldBe true

            When("Updating the peer port VLAN")
            store.update(peerPort.setPeerId(portId).setVlanId(peerVlanId))

            Then("The observer should receive two updates")
            obs.await(timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            val device2 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf bridge
            device2 shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLANs on first update")
            device1.exteriorPorts shouldBe empty
            device1.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be None on first update")
            device1.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device1.vlanMacTableMap.keySet should contain only UntaggedVlanId

            And("There should be no exterior ports or VLANs on second update")
            device2.exteriorPorts shouldBe empty
            device2.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be the peer port on second update")
            device2.vlanPortId shouldBe Some(peerPortId)

            And("There should be MAC learning tables for each VLAN")
            device2.vlanMacTableMap.keySet should contain only UntaggedVlanId
        }

        scenario("Update peer port no VLAN to VLAN (local port has VLAN)") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val peerVlanId: Short = 2
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port and a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        vlanId = Some(vlanId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            And("Waiting for the first update")
            obs.await(timeout, 1) shouldBe true

            When("Updating the peer port VLAN")
            store.update(peerPort.setPeerId(portId).setVlanId(peerVlanId))

            Then("The observer should receive two updates")
            obs.await(timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            val device2 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf bridge
            device2 shouldBeDeviceOf bridge

            And("There should be one VLAN on first update")
            device1.exteriorPorts shouldBe empty
            device1.vlanToPort.getPort(vlanId) shouldBe portId
            device1.vlanToPort.getVlan(portId) shouldBe vlanId

            And("The bridge VLAN peer port ID should be None on first update")
            device1.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device1.vlanMacTableMap.keySet should contain allOf(UntaggedVlanId, vlanId)

            And("There should be one VLAN on second update")
            device2.exteriorPorts shouldBe empty
            device2.vlanToPort.getPort(vlanId) shouldBe portId
            device2.vlanToPort.getVlan(portId) shouldBe vlanId

            And("The bridge VLAN peer port ID should be None on second update")
            device2.vlanPortId shouldBe None

            And("There should be MAC learning tables for each VLAN")
            device2.vlanMacTableMap.keySet should contain allOf(UntaggedVlanId, vlanId)
        }

        scenario("Update peer port VLAN (local port has no VLAN)") {
            val bridgeId = UUID.randomUUID
            val peerVlanId1: Short = 1
            val peerVlanId2: Short = 2
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId),
                                            vlanId = Some(peerVlanId1))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            And("Waiting for the first update")
            obs.await(timeout, 1) shouldBe true

            When("Updating the peer port VLAN")
            store.update(peerPort.setPeerId(portId).setVlanId(peerVlanId2))

            Then("The observer should receive two updates")
            obs.await(timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            val device2 = obs.getOnNextEvents.get(2)
            device1 shouldBeDeviceOf bridge
            device2 shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLANs on first update")
            device1.exteriorPorts shouldBe empty
            device1.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be the peer port on first update")
            device1.vlanPortId shouldBe Some(peerPortId)

            And("There should be MAC learning tables for each VLAN")
            device1.vlanMacTableMap.keySet should contain only UntaggedVlanId

            And("There should be no exterior ports or VLANs on second update")
            device2.exteriorPorts shouldBe empty
            device2.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be the peer port on second update")
            device2.vlanPortId shouldBe Some(peerPortId)

            And("There should be MAC learning tables for each VLAN")
            device2.vlanMacTableMap.keySet should contain only UntaggedVlanId
        }

        scenario("Update peer port for interior port (local port has no VLAN)") {
            val bridgeId = UUID.randomUUID
            val peerVlanId1: Short = 1
            val peerVlanId2: Short = 2
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId1 = UUID.randomUUID
            val peerPortId2 = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort1 = createBridgePort(id = peerPortId1,
                                             bridgeId = Some(peerBridgeId),
                                             vlanId = Some(peerVlanId1))
            val peerPort2 = createBridgePort(id = peerPortId2,
                                             bridgeId = Some(peerBridgeId),
                                             vlanId = Some(peerVlanId2))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort1), CreateOp(peerPort2),
                            UpdateOp(port.setPeerId(peerPortId1))))

            And("Waiting for the first update")
            obs.await(timeout, 2) shouldBe true

            When("Updating the peer port")
            store.update(port.setPeerId(peerPortId2))

            Then("The observer should receive three updates")
            obs.await(timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            val device3 = obs.getOnNextEvents.get(3)
            device1 shouldBeDeviceOf bridge
            device3 shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLANs on first update")
            device1.exteriorPorts shouldBe empty
            device1.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be the first peer port on first update")
            device1.vlanPortId shouldBe Some(peerPortId1)

            And("There should be MAC learning tables for each VLAN")
            device1.vlanMacTableMap.keySet should contain only UntaggedVlanId

            And("There should be no exterior ports or VLANs on second update")
            device3.exteriorPorts shouldBe empty
            device3.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be the second peer port on second update")
            device3.vlanPortId shouldBe Some(peerPortId2)

            And("There should be MAC learning tables for each VLAN")
            device3.vlanMacTableMap.keySet should contain only UntaggedVlanId
        }

        scenario("Create interior port peered to a router port") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port and a peer router and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerRouterId = UUID.randomUUID
            val peerPortAddr = IPv4Addr.random
            val peerPortMac = MAC.random

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerRouter = createRouter(id = peerRouterId)
            val peerPort = createRouterPort(id = peerPortId,
                                            routerId = Some(peerRouterId),
                                            portAddress = peerPortAddr,
                                            portMac = peerPortMac)

            store.multi(Seq(CreateOp(peerRouter), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports")
            device.exteriorPorts shouldBe empty

            And("The bridge VLAN peer port ID should be None")
            device.vlanPortId shouldBe None

            And("The MAC-port mapping for the router port should be set")
            device.macToLogicalPortId.toSeq should contain only ((peerPortMac, portId))

            And("The IP-MAC mapping for the router port should be set")
            device.ipToMac.toSeq should contain only ((peerPortAddr, peerPortMac))
        }

        scenario("MAC updated on peer router port") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port and a peer router and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerRouterId = UUID.randomUUID
            val peerPortMac1 = MAC.random
            val peerPortMac2 = MAC.random

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerRouter = createRouter(id = peerRouterId)
            val peerPort = createRouterPort(id = peerPortId,
                                            routerId = Some(peerRouterId),
                                            portMac = peerPortMac1)

            store.multi(Seq(CreateOp(peerRouter), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            device1 shouldBeDeviceOf bridge

            And("The MAC-port mapping should contain the first MAC")
            device1.macToLogicalPortId.toSeq should contain only ((peerPortMac1, portId))

            When("The router port MAC is updated")
            store.update(peerPort.setPeerId(portId).setPortMac(peerPortMac2))

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(2)
            device2 shouldBeDeviceOf bridge

            And("The MAC-port mapping should contain the second MAC")
            device2.macToLogicalPortId.toSeq should contain only ((peerPortMac2, portId))
        }

        scenario("IP updated on peer router port") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port and a peer router and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerRouterId = UUID.randomUUID
            val peerPortAddr1 = IPv4Addr.random
            val peerPortAddr2 = IPv4Addr.random
            val peerPortMac = MAC.random

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerRouter = createRouter(id = peerRouterId)
            val peerPort = createRouterPort(id = peerPortId,
                                            routerId = Some(peerRouterId),
                                            portAddress = peerPortAddr1,
                                            portMac = peerPortMac)

            store.multi(Seq(CreateOp(peerRouter), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            device1 shouldBeDeviceOf bridge

            And("The IP-MAC mapping should contain the first address")
            device1.ipToMac.toSeq should contain only ((peerPortAddr1, peerPortMac))

            When("The router port IP is updated")
            store.update(peerPort.setPeerId(portId).setPortAddress(peerPortAddr2))

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(2)
            device2 shouldBeDeviceOf bridge

            And("The IP-MAC mapping should contain the first address")
            device2.ipToMac.toSeq should contain only ((peerPortAddr2, peerPortMac))
        }

        scenario("Update router peer port for interior port") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with two peer router ports")
            val portId = UUID.randomUUID
            val peerPortId1 = UUID.randomUUID
            val peerPortId2 = UUID.randomUUID
            val peerRouterId = UUID.randomUUID
            val peerPortAddr1 = IPv4Addr.random
            val peerPortAddr2 = IPv4Addr.random
            val peerPortMac1 = MAC.random
            val peerPortMac2 = MAC.random

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerRouter = createRouter(id = peerRouterId)
            val peerPort1 = createRouterPort(id = peerPortId1,
                                             routerId = Some(peerRouterId),
                                             portAddress = peerPortAddr1,
                                             portMac = peerPortMac1)
            val peerPort2 = createRouterPort(id = peerPortId2,
                                             routerId = Some(peerRouterId),
                                             portAddress = peerPortAddr2,
                                             portMac = peerPortMac2)

            store.multi(Seq(CreateOp(peerRouter), CreateOp(port),
                            CreateOp(peerPort1), CreateOp(peerPort2),
                            UpdateOp(port.setPeerId(peerPortId1))))

            And("Waiting for the first update")
            obs.await(timeout, 2) shouldBe true

            When("Updating the peer port")
            store.update(port.setPeerId(peerPortId2))

            Then("The observer should receive three updates")
            obs.await(timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(1)
            val device3 = obs.getOnNextEvents.get(3)
            device1 shouldBeDeviceOf bridge
            device3 shouldBeDeviceOf bridge

            And("The first update should see the first peer port")
            device1.exteriorPorts shouldBe empty
            device1.vlanPortId shouldBe None
            device1.macToLogicalPortId.toSeq should contain only ((peerPortMac1, portId))
            device1.ipToMac.toSeq should contain only ((peerPortAddr1, peerPortMac1))

            And("The second update should see the second peer port")
            device3.exteriorPorts shouldBe empty
            device3.vlanPortId shouldBe None
            device3.macToLogicalPortId.toSeq should contain only ((peerPortMac2, portId))
            device3.ipToMac.toSeq should contain only ((peerPortAddr2, peerPortMac2))
        }

        scenario("Delete interior port peered to bridge port") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        vlanId = Some(vlanId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout, 2) shouldBe true

            When("The port is deleted")
            store.delete(classOf[TopologyPort], portId)

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(3)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLANs")
            device.exteriorPorts shouldBe empty
            device.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be None")
            device.vlanPortId shouldBe None
        }

        scenario("Delete interior port peered to router port") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer router and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerRouterId = UUID.randomUUID
            val peerPortMac = MAC.random

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        vlanId = Some(vlanId))
            val peerRouter = createRouter(id = peerRouterId)
            val peerPort = createRouterPort(id = peerPortId,
                                            routerId = Some(peerRouterId),
                                            portMac = peerPortMac)

            store.multi(Seq(CreateOp(peerRouter), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout, 2) shouldBe true

            When("The port is deleted")
            store.delete(classOf[TopologyPort], portId)

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true
            val device = obs.getOnNextEvents.get(3)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLANs")
            device.exteriorPorts shouldBe empty
            device.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be None")
            device.vlanPortId shouldBe None

            And("There should be no MAC-port or IP-MAC mappings")
            device.macToLogicalPortId.toSeq shouldBe empty
            device.ipToMac.toSeq shouldBe empty
        }
    }

    feature("Test flow invalidation") {
        scenario("For changes in exterior ports") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an exterior port for the bridge")
            val portId = UUID.randomUUID
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        hostId = Some(UUID.randomUUID),
                                        interfaceName = Some("iface"))
            store.create(port)

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true

            And("The flow controller should receive a broadcast invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId)),
                InvalidateFlowsByTag(tagForBroadcast(bridgeId)))

            When("Deleting the exterior port for the bridge")
            store.delete(classOf[TopologyPort], portId)

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true

            And("The flow controller should receive a broadcase invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId)),
                InvalidateFlowsByTag(tagForBroadcast(bridgeId)),
                InvalidateFlowsByTag(tagForDevice(portId)),
                InvalidateFlowsByTag(tagForBroadcast(bridgeId)))
        }

        scenario("For added and updated MAC-port mappings") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer router and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerRouterId = UUID.randomUUID
            val peerPortMac1 = MAC.random
            val peerPortMac2 = MAC.random

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            val peerRouter = createRouter(id = peerRouterId)
            val peerPort = createRouterPort(id = peerPortId,
                                            routerId = Some(peerRouterId),
                                            portMac = peerPortMac1)

            store.multi(Seq(CreateOp(peerRouter), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true

            And("The flow controller should receive MAC-port mapping invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId)),
                InvalidateFlowsByTag(tagForDevice(peerPortId)),
                InvalidateFlowsByTag(tagForArpRequests(bridgeId)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, UntaggedVlanId, peerPortMac1)))

            When("The router port MAC is updated")
            store.update(peerPort.setPeerId(portId).setPortMac(peerPortMac2))

            Then("The observer should receive the update")
            obs.await(timeout) shouldBe true

            And("The flow controller should receive MAC-port mapping invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId)),
                InvalidateFlowsByTag(tagForDevice(peerPortId)),
                InvalidateFlowsByTag(tagForArpRequests(bridgeId)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, UntaggedVlanId, peerPortMac1)),
                InvalidateFlowsByTag(tagForDevice(peerPortId)),
                InvalidateFlowsByTag(tagForBridgePort(bridgeId, portId)),
                InvalidateFlowsByTag(tagForArpRequests(bridgeId)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, UntaggedVlanId, peerPortMac2)))
        }

        scenario("For MAC added to port") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating two interior ports with two peer bridge ports")
            val portId1 = UUID.randomUUID
            val portId2 = UUID.randomUUID
            val peerPortId1 = UUID.randomUUID
            val peerPortId2 = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID
            val vlanId: Short = 1

            val port1 = createBridgePort(id = portId1, bridgeId = Some(bridgeId),
                                       vlanId = Some(vlanId))
            val port2 = createBridgePort(id = portId2, bridgeId = Some(bridgeId),
                                       vlanId = Some(vlanId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort1 = createBridgePort(id = peerPortId1,
                                             bridgeId = Some(peerBridgeId))
            val peerPort2 = createBridgePort(id = peerPortId2,
                                             bridgeId = Some(peerBridgeId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port1),
                            CreateOp(port2), CreateOp(peerPort1),
                            CreateOp(peerPort2),
                            UpdateOp(port1.setPeerId(peerPortId1)),
                            UpdateOp(port2.setPeerId(peerPortId2))))

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf bridge
            device.vlanMacTableMap.keySet should contain allOf(
                UntaggedVlanId, vlanId)

            And("The flow controller should receive the device invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId1)),
                InvalidateFlowsByTag(tagForDevice(portId2)),
                InvalidateFlowsByTag(tagForDevice(peerPortId1)),
                InvalidateFlowsByTag(tagForDevice(peerPortId2)))

            Given("The MAC-port replicated map for the bridge")
            val map = vt.state.bridgeMacTable(bridgeId, vlanId, ephemeral = true)
            map.start()

            When("A first MAC is added to the MAC learning table")
            val otherMac1 = MAC.random
            map.put(otherMac1, portId1)

            Then("The flow controller should receive the MAC update invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId1)),
                InvalidateFlowsByTag(tagForDevice(portId2)),
                InvalidateFlowsByTag(tagForDevice(peerPortId1)),
                InvalidateFlowsByTag(tagForDevice(peerPortId2)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, vlanId, otherMac1)))

            When("A second MAC is added to the MAC learning table")
            val otherMac2 = MAC.random
            map.put(otherMac2, portId1)

            Then("The flow controller should receive the MAC update invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId1)),
                InvalidateFlowsByTag(tagForDevice(portId2)),
                InvalidateFlowsByTag(tagForDevice(peerPortId1)),
                InvalidateFlowsByTag(tagForDevice(peerPortId2)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, vlanId, otherMac1)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, vlanId, otherMac2)))

            When("A MAC changes from one port to another")
            map.put(otherMac1, portId2)

            Then("The flow controller should receive the MAC update invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId1)),
                InvalidateFlowsByTag(tagForDevice(portId2)),
                InvalidateFlowsByTag(tagForDevice(peerPortId1)),
                InvalidateFlowsByTag(tagForDevice(peerPortId2)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, vlanId, otherMac1)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, vlanId, otherMac2)),
                InvalidateFlowsByTag(tagForVlanPort(
                    bridgeId, otherMac1, vlanId, portId1)))

            When("A MAC is deleted")
            map.removeIfOwner(otherMac2)

            Then("The flow controller should receive the MAC update invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId1)),
                InvalidateFlowsByTag(tagForDevice(portId2)),
                InvalidateFlowsByTag(tagForDevice(peerPortId1)),
                InvalidateFlowsByTag(tagForDevice(peerPortId2)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, vlanId, otherMac1)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, vlanId, otherMac2)),
                InvalidateFlowsByTag(tagForVlanPort(
                    bridgeId, otherMac1, vlanId, portId1)),
                InvalidateFlowsByTag(tagForVlanPort(
                    bridgeId, otherMac2, vlanId, portId1)))
        }

        scenario("MAC entries should expire") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver(1)
            val bridge = testBridgeCreated(bridgeId, obs, count = 1, test = 0)

            When("Creating an interior port with a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID
            val vlanId: Short = 1

            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                      vlanId = Some(vlanId))
            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId))

            store.multi(Seq(CreateOp(peerBridge), CreateOp(port),
                            CreateOp(peerPort),
                            UpdateOp(port.setPeerId(peerPortId))))

            Then("The observer should receive the update")
            obs.await(timeout, 1) shouldBe true
            val device = obs.getOnNextEvents.get(1)
            device shouldBeDeviceOf bridge
            device.vlanMacTableMap.keySet should contain allOf(
                UntaggedVlanId, vlanId)

            And("The flow controller should receive the device invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId)),
                InvalidateFlowsByTag(tagForDevice(peerPortId)))

            When("A MAC reference is incremented via the flow count")
            val otherMac = MAC.random
            device.flowCount.increment(otherMac, vlanId, portId)

            Then("The MAC should appear in the MAC learning table")
            device.vlanMacTableMap(vlanId).get(otherMac) shouldBe portId

            And("The flow controller should receive the MAC update invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId)),
                InvalidateFlowsByTag(tagForDevice(peerPortId)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, vlanId, otherMac)))

            When("A MAC reference is decremented via the flow count")
            device.flowCount.decrement(otherMac, vlanId, portId)

            And("Waiting for the bridge mapper to expire the MAC entry")
            Thread.sleep(macExpiration.toMillis)

            Then("Eventually the MAC entry is deleted")
            eventually {
                device.vlanMacTableMap(vlanId).get(otherMac) shouldBe null
            }

            And("The flow controller should receive the MAC update invalidation")
            fc.messages should contain theSameElementsInOrderAs List(
                InvalidateFlowsByTag(tagForDevice(portId)),
                InvalidateFlowsByTag(tagForDevice(peerPortId)),
                InvalidateFlowsByTag(tagForFloodedFlowsByDstMac(
                    bridgeId, vlanId, otherMac)),
                InvalidateFlowsByTag(tagForVlanPort(
                    bridgeId, otherMac, vlanId, portId)))
        }
    }
}
