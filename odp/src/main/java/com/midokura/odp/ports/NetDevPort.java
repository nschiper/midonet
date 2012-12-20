/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.odp.ports;

import javax.annotation.Nonnull;

import com.midokura.odp.Port;

/**
 * Description of a port that maps to a local netdev device.
 */
public class NetDevPort extends Port<NetDevPortOptions, NetDevPort> {

    public NetDevPort(@Nonnull String name) {
        super(name, Type.NetDev);
    }

    @Override
    public NetDevPortOptions newOptions() {
        return new NetDevPortOptions();
    }

    @Override
    protected NetDevPort self() {
        return this;
    }

}